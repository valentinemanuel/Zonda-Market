package com.zondamarket.checkout.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zonda.checkout.reconciliation.enabled", matchIfMissing = true, havingValue = "true")
class PaymentReconciliationSchedule {
    private final PaymentReconciliationWorker worker;
    private final PaymentCompensationWorker compensation;
    private final DisputeRefundWorker refunds;

    PaymentReconciliationSchedule(PaymentReconciliationWorker worker, PaymentCompensationWorker compensation,
                                  DisputeRefundWorker refunds) {
        this.worker = worker;
        this.compensation = compensation;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${zonda.checkout.reconciliation.delay-ms:3000}")
    void reconcile() {
        for (int i = 0; i < 4 && worker.processNextNotification(); i++) {
            // Bound the work performed in a single tick.
        }
        for (int i = 0; i < 2 && worker.reconcileNextAttempt(); i++) {
            // Give pending attempts time even when the inbox is busy.
        }
        compensation.processNext();
        refunds.processNext();
    }
}
