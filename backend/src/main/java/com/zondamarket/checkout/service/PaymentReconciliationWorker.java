package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentDtos.Method;
import com.zondamarket.checkout.repository.PaymentAttemptStore;
import com.zondamarket.checkout.repository.PaymentWebhookInbox;
import com.zondamarket.checkout.repository.PaymentWebhookInbox.Delivery;
import com.zondamarket.checkout.service.PaymentStrategy.VerifiedPayment;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PaymentReconciliationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationWorker.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 10;
    private final PaymentWebhookInbox inbox;
    private final PaymentAttemptStore attempts;
    private final PaymentReconciler reconciler;
    private final CardPaymentService cardService;
    private final CheckoutProService proService;
    private final CardPaymentGateway card;
    private final CheckoutProGateway pro;
    private final Clock clock;

    PaymentReconciliationWorker(PaymentWebhookInbox inbox, PaymentAttemptStore attempts, PaymentReconciler reconciler,
                                CardPaymentService cardService, CheckoutProService proService,
                                ObjectProvider<CardPaymentGateway> card, ObjectProvider<CheckoutProGateway> pro,
                                Clock clock) {
        this.inbox = inbox;
        this.attempts = attempts;
        this.reconciler = reconciler;
        this.cardService = cardService;
        this.proService = proService;
        this.card = card.getIfAvailable();
        this.pro = pro.getIfAvailable();
        this.clock = clock;
    }

    public boolean processNextNotification() {
        var delivery = inbox.claim(clock.instant()).orElse(null);
        if (delivery == null) return false;
        try {
            if (!delivery.manualRetry() && delivery.attempts() > MAX_DELIVERY_ATTEMPTS) {
                failure(delivery, "RETRY_LIMIT_REACHED", false);
            } else {
                process(delivery);
            }
        } catch (PaymentProviderReadFailure error) {
            failure(delivery, error.code(), error.retryable());
        } catch (ApiProblemException conflict) {
            failure(delivery, String.valueOf(conflict.getBody().getProperties().get("code")), false);
        } catch (RuntimeException error) {
            failure(delivery, "RECONCILIATION_ERROR", true);
        }
        return true;
    }

    private void process(Delivery delivery) throws PaymentProviderReadFailure {
        var method = delivery.topic().method();
        if ((method == Method.CARD && card == null) || (method == Method.CHECKOUT_PRO && pro == null)) {
            failure(delivery, "PROVIDER_NOT_CONFIGURED", false);
            return;
        }
        Optional<VerifiedPayment> result = switch (method) {
            case CARD -> card.lookupOrder(delivery.resourceId()).map(VerifiedPayment::from);
            case CHECKOUT_PRO -> pro.lookupPayment(delivery.resourceId()).map(VerifiedPayment::from);
        };
        if (result.isEmpty()) {
            failure(delivery, "RESOURCE_NOT_VERIFIED", false);
            return;
        }
        var payment = result.get();
        var target = attempts.forExternalReference(method, payment.externalReference()).orElse(null);
        if (target == null) {
            failure(delivery, "ATTEMPT_NOT_FOUND", false);
            return;
        }
        reconciler.apply(target.buyerId(), target.orderId(), target.attemptId(), payment, delivery.traceId());
        inbox.finish(delivery, clock.instant(), null);
    }

    private void failure(Delivery delivery, String code, boolean retryable) {
        var now = clock.instant();
        if (retryable && !delivery.manualRetry() && delivery.attempts() < MAX_DELIVERY_ATTEMPTS) {
            long delay = Math.min(900, 5L << (delivery.attempts() - 1));
            inbox.retry(delivery, now.plusSeconds(delay), code);
            LOG.info("Webhook de Mercado Pago pendiente traceId={} inboxId={} intento={} motivo={}",
                    delivery.traceId(), delivery.id(), delivery.attempts(), code);
        } else {
            inbox.finish(delivery, now, code);
            LOG.warn("Webhook de Mercado Pago sin resolver traceId={} inboxId={} motivo={}",
                    delivery.traceId(), delivery.id(), code);
        }
    }

    public boolean reconcileNextAttempt() {
        if (card == null && pro == null) return false;
        var due = attempts.claimReconciliation(card != null, pro != null, clock.instant()).orElse(null);
        if (due == null) return false;
        try {
            switch (due.method()) {
                case CARD -> cardService.consult(due.buyerId(), due.orderId(), due.attemptId(), due.traceId());
                case CHECKOUT_PRO -> proService.consult(due.buyerId(), due.orderId(), due.attemptId(), due.traceId());
            }
        } catch (RuntimeException error) {
            LOG.warn("No se pudo conciliar el intento traceId={} attemptId={} error={}",
                    due.traceId(), due.attemptId(), error.getClass().getSimpleName());
        }
        return true;
    }
}
