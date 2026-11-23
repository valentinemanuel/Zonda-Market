package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.FulfillmentDtos.*;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.DisputeStore;
import com.zondamarket.checkout.repository.FulfillmentStore;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.repository.SellerProfileRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentService {
    private final CheckoutStore checkout;
    private final FulfillmentStore store;
    private final DisputeStore disputes;
    private final SellerProfileRepository sellers;
    private final Clock clock;

    FulfillmentService(CheckoutStore checkout, FulfillmentStore store, DisputeStore disputes,
                       SellerProfileRepository sellers, Clock clock) {
        this.checkout = checkout;
        this.store = store;
        this.disputes = disputes;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SellerOrderPage sellerOrders(AccountResponse account, SellerOrderStatus status, UUID subOrderId, int page, int size) {
        var seller = sellerId(account).orElseThrow(() -> new ApiProblemException(
                HttpStatus.FORBIDDEN, "SELLER_REQUIRED", "Activá tu perfil de vendedor."));
        var orders = checkout.sellerOrders(seller, status, subOrderId, page, size);
        boolean hasNext = orders.size() > size;
        return new SellerOrderPage(hasNext ? orders.subList(0, size) : orders, page, size, hasNext);
    }

    @Transactional
    public ShipmentResponse ship(AccountResponse account, UUID id, ShipRequest request, UUID trace) {
        var seller = sellerId(account).orElseThrow(FulfillmentService::notFound);
        var order = store.lockParent(id, seller, true).orElseThrow(FulfillmentService::notFound);
        var sub = store.lockSubOrder(id);
        var carrier = Carrier.valueOf(request.carrier());
        int version = request.expectedVersion().intValueExact();
        if ("DESPACHADA".equals(sub.status()) || "ENTREGADA".equals(sub.status())) {
            var shipment = sub.shipment();
            if (shipment.carrier() == carrier && shipment.trackingCode().equals(request.trackingCode())
                    && version == sub.version() - (shipment.deliveredAt() == null ? 1 : 2)) {
                return sub;
            }
            throw conflict("SUB_ORDER_ALREADY_SHIPPED", "La suborden ya tiene un despacho registrado.");
        }
        requireState(order.status(), sub, "PREPARANDO", version);
        requireNoDispute(id);

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        store.ship(id, carrier, request.trackingCode(), now);
        store.event(order.id(), id, "SUB_ORDER_SHIPPED", account.id(), trace, now);
        return store.lockSubOrder(id);
    }

    @Transactional
    public ShipmentResponse confirmDelivery(UUID buyer, UUID id, DeliveryConfirmRequest request, UUID trace) {
        var order = store.lockParent(id, buyer, false).orElseThrow(FulfillmentService::notFound);
        var sub = store.lockSubOrder(id);
        int version = request.expectedVersion().intValueExact();
        if ("ENTREGADA".equals(sub.status()) && version == sub.version() - 1) return sub;
        requireState(order.status(), sub, "DESPACHADA", version);
        requireNoDispute(id);

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(sub.shipment().shippedAt())) now = sub.shipment().shippedAt();
        store.deliver(id, now);
        store.event(order.id(), id, "DELIVERY_CONFIRMED", buyer, trace, now);
        return store.lockSubOrder(id);
    }

    private void requireNoDispute(UUID id) {
        if (disputes.active(id)) {
            throw conflict("SUB_ORDER_DISPUTED", "La suborden tiene un reclamo activo.");
        }
    }

    private Optional<UUID> sellerId(AccountResponse account) {
        if (!account.roles().contains(Role.SELLER)) return Optional.empty();
        return sellers.findByAccountIdAndDeletedFalseAndAccountDeletedFalseAndAccountRole(account.id(), Role.SELLER)
                .map(seller -> seller.getId());
    }

    private static void requireState(String orderStatus, ShipmentResponse sub, String expectedStatus, int version) {
        if (!"PAGADA".equals(orderStatus) || !expectedStatus.equals(sub.status())) {
            throw conflict("SUB_ORDER_STATE_CONFLICT", "El estado actual de la suborden no permite esa acción.");
        }
        if (version != sub.version()) {
            throw conflict("SUB_ORDER_VERSION_CONFLICT", "La suborden cambió. Consultá el estado vigente.");
        }
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "SUB_ORDER_NOT_FOUND", "No encontramos esa suborden.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
