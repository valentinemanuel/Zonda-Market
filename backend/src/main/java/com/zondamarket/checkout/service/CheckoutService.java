package com.zondamarket.checkout.service;

import com.zondamarket.cart.repository.CartStore;
import com.zondamarket.cart.service.CartService;
import com.zondamarket.catalog.repository.CategoryLock;
import com.zondamarket.checkout.dto.CheckoutDtos.Group;
import com.zondamarket.checkout.dto.CheckoutDtos.Line;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderPage;
import com.zondamarket.checkout.dto.CheckoutDtos.OrderResponse;
import com.zondamarket.checkout.dto.CheckoutDtos.QuoteResponse;
import com.zondamarket.checkout.dto.CheckoutDtos.Snapshot;
import com.zondamarket.checkout.dto.CheckoutDtos.SubOrder;
import com.zondamarket.checkout.dto.CheckoutDtos.TestPayment;
import com.zondamarket.checkout.dto.CompensationDtos.Status;
import com.zondamarket.checkout.dto.PaymentDtos;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.CheckoutStore.Order;
import com.zondamarket.checkout.repository.PaymentOutcomeStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentCompensationStore;
import com.zondamarket.checkout.repository.ReservationStore;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.dto.ProfileDtos.AddressResponse;
import com.zondamarket.shared.errors.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private static final Duration QUOTE_TTL = Duration.ofMinutes(5);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final CartStore carts;
    private final CheckoutStore store;
    private final PaymentOutcomeStore outcomes;
    private final PaymentCompensationStore compensations;
    private final PaymentAttemptStore attempts;
    private final ReservationStore reservations;
    private final ObjectProvider<LocalPaymentPort> localPayment;
    private final Clock clock;
    private final CategoryLock categoryLock;

    CheckoutService(CartStore carts, CheckoutStore store, PaymentOutcomeStore outcomes, PaymentCompensationStore compensations,
                    PaymentAttemptStore attempts, ReservationStore reservations,
                    ObjectProvider<LocalPaymentPort> localPayment, Clock clock, CategoryLock categoryLock) {
        this.carts = carts;
        this.store = store;
        this.outcomes = outcomes;
        this.compensations = compensations;
        this.attempts = attempts;
        this.reservations = reservations;
        this.localPayment = localPayment;
        this.clock = clock;
        this.categoryLock = categoryLock;
    }

    @Transactional
    public QuoteResponse quote(AccountResponse account, UUID addressId, long version) {
        requireVerified(account);
        var cart = carts.lock(account.id());
        requireNoActiveOrder(account.id());
        requireVersion(cart.version(), version);
        var address = address(account.id(), addressId, false);
        var snapshot = snapshot(account.id(), cart.id(), address);
        var id = UUID.randomUUID();
        var now = now();
        var expiry = now.plus(QUOTE_TTL);
        store.saveQuote(id, cart.id(), version, snapshot, now, expiry);
        return new QuoteResponse(id, expiry, now, version, address, snapshot.groups(), snapshot.total(), "ARS");
    }

    @Transactional
    public OrderResponse confirm(AccountResponse account, UUID quoteId, String key, UUID trace) {
        requireVerified(account);
        var cart = carts.lock(account.id());
        String hash = requestHash(quoteId);
        var replay = store.replay(account.id(), key);
        if (replay.isPresent()) {
            var order = replay.get();
            if (!order.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya se usó para otra cotización.");
            }
            return response(order);
        }
        var quote = store.quote(cart.id(), quoteId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "No encontramos esa cotización."));
        requireNoActiveOrder(account.id());
        store.confirmedStatus(account.id(), quoteId).ifPresent(status -> {
            if ("EXPIRADA".equals(status)) throw conflict("CHECKOUT_EXPIRED", "La reserva anterior venció.");
            throw conflict("QUOTE_ALREADY_CONFIRMED", "La cotización ya confirmó una orden.");
        });
        requireVersion(cart.version(), quote.cartVersion());
        requireCurrentQuote(quote.expiresAt(), now());
        var address = address(account.id(), quote.snapshot().address().id(), true);

        // Serialize new reservations with moderation and lock listings before SKUs, as catalog writes do.
        categoryLock.readForWrite();
        store.lockOfferVisibility(cart.id());
        store.lockSkus(cart.id());
        var snapshot = snapshot(account.id(), cart.id(), address);
        var now = now();
        requireCurrentQuote(quote.expiresAt(), now);
        if (!quote.snapshot().equals(snapshot)) {
            throw conflict("QUOTE_CHANGED", "Cambiaron los datos de la cotización. Volvé a cotizar.");
        }
        var order = new Order(UUID.randomUUID(), "RESERVADA", new BigDecimal(snapshot.total()), "ARS",
                now.plus(RESERVATION_TTL), hash);
        store.insertOrder(order, account.id(), cart.id(), quoteId, key, trace, now);
        for (var group : snapshot.groups()) {
            var sub = new SubOrder(UUID.randomUUID(), "ESPERA_PAGO", address, group, 0, null, false, null);
            store.insertSubOrder(order.id(), sub, now);
            for (var line : group.items()) {
                if (!store.reserve(order, line, now)) throw stockChanged();
                store.insertLine(sub.id(), line, now);
            }
        }
        store.recordReserved(order.id(), trace, now);
        return response(order);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderResponse get(UUID buyer, UUID id) {
        return store.find(buyer, id).map(this::response).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No encontramos esa orden."));
    }

    @Transactional(timeout = 5)
    public void cancelReservation(UUID buyer, UUID id, UUID trace) {
        var order = store.lock(buyer, id).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No encontramos esa orden."));
        if (List.of("CANCELADA", "EXPIRADA", "FALLIDA").contains(order.status())) return;
        if (!List.of("RESERVADA", "PAGO_PENDIENTE").contains(order.status())) {
            throw conflict("ORDER_ALREADY_SETTLED", "Esta orden ya tiene un resultado definitivo.");
        }
        var attempt = attempts.latest(id).orElse(null);
        var now = now();
        if (attempt != null) {
            switch (attempt.status()) {
                case CREADO -> attempts.update(id, attempt.id(), PaymentDtos.Status.RECHAZADO, trace, now);
                case RECHAZADO -> { }
                default -> throw conflict("PAYMENT_IN_PROGRESS",
                        "El pago está en proceso. Consultá su estado antes de volver a Envío.");
            }
        }
        reservations.lockResources(id);
        reservations.settle(id, false, "CANCELADA", "CANCELADA", "CHECKOUT_CANCELLED", trace, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<OrderResponse> active(UUID buyer) {
        return store.active(buyer).map(this::response);
    }

    @Transactional(readOnly = true)
    public OrderPage history(UUID buyer, int page, int size) {
        var orders = store.history(buyer, page, size);
        return new OrderPage(orders.subList(0, Math.min(size, orders.size())), page, size, orders.size() > size);
    }

    private OrderResponse response(Order order) {
        var subOrders = store.subOrders(order.id());
        TestPayment fixture = localPayment.getIfAvailable() == null ? null
                : new TestPayment(LocalPaymentPort.reference(order.id()),
                    outcomes.find(order.id()).map(saved -> saved.result().name()).orElse(null));
        var refunds = compensations.forOrder(order.id());
        var refunded = refunds.stream().filter(refund -> refund.status() == Status.CONFIRMADA)
                .map(refund -> new BigDecimal(refund.amount())).reduce(ZERO, BigDecimal::add);
        return new OrderResponse(order.id(), order.expiresAt(), now(), order.status(),
                subOrders, order.total().toPlainString(), order.currency(), fixture, refunds, refunded.toPlainString());
    }

    private Snapshot snapshot(UUID buyer, UUID cart, AddressResponse address) {
        var lines = carts.lines(cart);
        if (lines.isEmpty()) throw conflict("CART_EMPTY", "Agregá productos para cotizar la compra.");

        var groups = new LinkedHashMap<UUID, GroupAmounts>();
        for (var line : lines) {
            var offer = line.offer();
            if (CartService.unavailable(buyer, offer, line.quantity()) != null) throw stockChanged();

            var subtotal = offer.price().multiply(BigDecimal.valueOf(line.quantity()));
            var commission = subtotal.multiply(BigDecimal.valueOf(offer.commissionBps(), 4))
                    .setScale(2, RoundingMode.HALF_UP);
            var group = groups.computeIfAbsent(offer.sellerId(), ignored -> new GroupAmounts(offer));
            group.items.add(new Line(offer.skuId(), offer.code(), offer.title(), offer.attributes(), line.quantity(),
                    offer.price().toPlainString(), subtotal.toPlainString(), offer.commissionBps(), commission.toPlainString()));
            group.merchandise = group.merchandise.add(subtotal);
            group.commission = group.commission.add(commission);
        }
        var total = groups.values().stream().map(GroupAmounts::subtotal).reduce(ZERO, BigDecimal::add);
        if (total.precision() > 19) throw conflict("CHECKOUT_TOTAL_LIMIT", "El importe supera el máximo de una orden.");
        return new Snapshot(address, groups.values().stream().map(GroupAmounts::group).toList(), total.toPlainString());
    }

    private AddressResponse address(UUID buyer, UUID id, boolean lock) {
        return store.address(buyer, id, lock).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Elegí una dirección vigente de tu cuenta."));
    }

    private void requireNoActiveOrder(UUID buyer) {
        store.active(buyer).ifPresent(order -> {
            if (!now().isBefore(order.expiresAt())) {
                throw conflict("CHECKOUT_EXPIRED", "La reserva anterior venció y está pendiente de liberación.");
            }
            throw conflict("ORDER_ALREADY_ACTIVE", "Ya tenés una reserva para esta cuenta.");
        });
    }

    private static void requireVerified(AccountResponse account) {
        if (!account.emailVerified()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verificá tu correo para confirmar la compra.");
        }
    }

    private static void requireVersion(long current, long expected) {
        if (current != expected) throw conflict("CART_VERSION_CONFLICT", "El carrito cambió. Volvé a cotizar.");
    }

    private static void requireCurrentQuote(Instant expiry, Instant now) {
        if (!now.isBefore(expiry)) throw conflict("QUOTE_EXPIRED", "La cotización venció. Volvé a cotizar.");
    }

    private static ApiProblemException stockChanged() {
        return conflict("STOCK_CHANGED", "Cambió la disponibilidad del carrito. Revisá los productos y volvé a cotizar.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requestHash(UUID quoteId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(quoteId.toString().getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class GroupAmounts {
        private final CartStore.Offer seller;
        private final List<Line> items = new ArrayList<>();
        private BigDecimal merchandise = ZERO;
        private BigDecimal commission = ZERO;

        private GroupAmounts(CartStore.Offer seller) {
            this.seller = seller;
        }

        private BigDecimal subtotal() {
            return merchandise.add(seller.shipping());
        }

        private Group group() {
            return new Group(seller.sellerId(), seller.sellerName(), items, merchandise.toPlainString(),
                    seller.shipping().toPlainString(), commission.toPlainString(), subtotal().toPlainString());
        }
    }
}
