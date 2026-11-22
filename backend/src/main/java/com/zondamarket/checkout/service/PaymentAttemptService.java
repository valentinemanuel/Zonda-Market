package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.dto.PaymentDtos.Provider;
import com.zondamarket.checkout.dto.PaymentDtos.CardConfig;
import com.zondamarket.checkout.dto.PaymentDtos.NextAction;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentAttemptResponse;
import com.zondamarket.checkout.dto.PaymentDtos.PaymentOptions;
import com.zondamarket.checkout.dto.PaymentDtos.Status;
import com.zondamarket.checkout.repository.CheckoutStore;
import com.zondamarket.checkout.repository.CheckoutStore.Order;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentAttemptStore.Attempt;
import com.zondamarket.checkout.repository.ReservationStore;
import com.zondamarket.checkout.service.PaymentStrategy.PaymentCommand;
import com.zondamarket.checkout.service.PaymentStrategy.SubmissionUncertain;
import com.zondamarket.checkout.shared.PaymentErrors;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentAttemptService {
    private final CheckoutStore orders;
    private final ReservationStore reservations;
    private final PaymentAttemptStore attempts;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Map<Method, PaymentStrategy> strategies = new EnumMap<>(Method.class);
    private final CardPaymentGateway card;

    PaymentAttemptService(CheckoutStore orders, ReservationStore reservations, PaymentAttemptStore attempts,
                          Clock clock, PlatformTransactionManager manager, List<PaymentStrategy> strategies,
                          ObjectProvider<CardPaymentGateway> card) {
        this.orders = orders;
        this.reservations = reservations;
        this.attempts = attempts;
        this.clock = clock;
        this.card = card.getIfAvailable();
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
        for (var strategy : strategies) {
            for (var method : strategy.methods()) {
                if (this.strategies.putIfAbsent(method, strategy) != null) {
                    throw new IllegalStateException("Hay más de una estrategia para " + method);
                }
            }
        }
    }

    public PaymentOptions options(UUID buyer, UUID orderId, String payerEmail) {
        return transaction.execute(tx -> {
            var order = lockOrder(buyer, orderId);
            var latest = attempts.latest(orderId).orElse(null);
            var available = eligible(order, clock.instant()) && (latest == null || !latest.status().active());
            var methods = available ? availableMethods() : List.<Method>of();
            var config = card == null ? null : new CardConfig(card.publicKey(), order.total().toPlainString(), payerEmail);
            return new PaymentOptions(methods, latest == null ? null : response(latest, !methods.isEmpty()), config);
        });
    }

    public PaymentAttemptResponse get(UUID buyer, UUID orderId, UUID attemptId) {
        return transaction.execute(tx -> {
            var order = lockOrder(buyer, orderId);
            var attempt = find(orderId, attemptId);
            boolean canRetry = attempt.status() == Status.RECHAZADO && !availableMethods().isEmpty()
                    && eligible(order, clock.instant())
                    && attempts.latest(orderId).filter(latest -> latest.status().active()).isEmpty();
            return response(attempt, canRetry);
        });
    }

    public PaymentAttemptResponse start(UUID buyer, UUID orderId, Method method, String key, UUID trace) {
        var attempt = transaction.execute(tx -> {
            var order = lockOrder(buyer, orderId);
            var previous = attempts.byKey(orderId, key);
            if (previous.isPresent()) {
                if (previous.get().method() != method) {
                    throw PaymentErrors.conflict("IDEMPOTENCY_KEY_REUSED", "La clave ya se usó con otro medio.");
                }
                return previous.get();
            }
            var now = clock.instant();
            if (!eligible(order, now)) throw PaymentErrors.ineligible();
            if (attempts.latest(orderId).filter(a -> a.status().active()).isPresent()) {
                throw PaymentErrors.conflict("PAYMENT_ATTEMPT_ACTIVE", "Ya hay un intento activo para esta orden.");
            }
            if (!availableMethods().contains(method)) {
                throw PaymentErrors.conflict("PAYMENT_METHOD_UNAVAILABLE", "El medio de pago no está disponible.");
            }
            var id = UUID.randomUUID();
            var provider = method == Method.CARD && card != null ? Provider.MERCADO_PAGO : Provider.LOCAL;
            var created = new Attempt(id, method, provider, Status.CREADO, UUID.randomUUID(), "zonda-" + id,
                    order.total(), order.currency(), order.expiresAt(), null, null);
            attempts.insert(orderId, created, key, trace, now);
            return created;
        });
        return submit(buyer, orderId, attempt.id(), trace);
    }

    public PaymentAttemptResponse submit(UUID buyer, UUID orderId, UUID attemptId, UUID trace) {
        var claimed = transaction.execute(tx -> {
            var order = lockOrder(buyer, orderId);
            var attempt = find(orderId, attemptId);
            if (attempt.status() != Status.CREADO || attempt.provider() != Provider.LOCAL) return null;
            var now = clock.instant();
            if (!eligible(order, now) || !strategies.containsKey(attempt.method())) {
                attempts.update(orderId, attemptId, Status.RECHAZADO, trace, now);
                return null;
            }
            attempts.update(orderId, attemptId, Status.ENVIADO, trace, now);
            return attempt;
        });
        if (claimed != null) {
            var command = new PaymentCommand(claimed.method(), claimed.providerKey(), claimed.externalReference(),
                    claimed.amount(), claimed.currency(), claimed.expiresAt());
            Status submitted = send(command);
            transaction.executeWithoutResult(tx -> {
                lockOrder(buyer, orderId);
                if (find(orderId, attemptId).status() == Status.ENVIADO) {
                    attempts.update(orderId, attemptId, submitted, trace, clock.instant());
                }
            });
        }
        return get(buyer, orderId, attemptId);
    }

    private Status send(PaymentCommand command) {
        try {
            strategies.get(command.method()).submit(command);
            return Status.PENDIENTE;
        } catch (SubmissionUncertain timeout) {
            return Status.RESULTADO_DESCONOCIDO;
        }
    }

    Order lockOrder(UUID buyer, UUID orderId) {
        return orders.lock(buyer, orderId).orElseThrow(() ->
                PaymentErrors.notFound("ORDER_NOT_FOUND", "No encontramos esa orden."));
    }

    Attempt find(UUID orderId, UUID attemptId) {
        return attempts.find(orderId, attemptId).orElseThrow(PaymentErrors::attemptNotFound);
    }

    boolean eligible(Order order, Instant now) {
        return ("RESERVADA".equals(order.status()) || "PAGO_PENDIENTE".equals(order.status()))
                && now.isBefore(order.expiresAt()) && reservations.hasActive(order.id(), now);
    }

    private List<Method> availableMethods() {
        var methods = EnumSet.noneOf(Method.class);
        methods.addAll(strategies.keySet());
        if (card != null) methods.add(Method.CARD);
        return List.copyOf(methods);
    }

    private PaymentAttemptResponse response(Attempt attempt, boolean canRetry) {
        var next = NextAction.NINGUNA;
        if (attempt.status().active()) next = NextAction.CONSULTAR;
        if (attempt.status() == Status.CREADO && attempt.provider() == Provider.MERCADO_PAGO) {
            next = NextAction.INGRESAR_TARJETA;
        }
        if (attempt.status() == Status.RECHAZADO && canRetry) next = NextAction.ELEGIR_MEDIO;
        return new PaymentAttemptResponse(attempt.id(), attempt.method(), attempt.provider(), attempt.status(),
                next, attempt.expiresAt());
    }
}
