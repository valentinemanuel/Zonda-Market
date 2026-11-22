package com.zondamarket.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zonda.notifications.worker-enabled", havingValue = "true", matchIfMissing = true)
class NotificationSchedule {
    private final NotificationService notifications;
    private final NotificationMailWorker mail;
    private final boolean mailEnabled;

    NotificationSchedule(NotificationService notifications, NotificationMailWorker mail,
            @Value("${zonda.mail.worker-enabled:true}") boolean mailEnabled) {
        this.notifications = notifications;
        this.mail = mail;
        this.mailEnabled = mailEnabled;
    }

    @Scheduled(fixedDelay = 1000)
    void project() {
        for (int i = 0; i < 10 && notifications.projectNext(); i++) {
            // Process a bounded batch between polls.
        }
    }

    @Scheduled(fixedDelay = 1000)
    void deliver() {
        if (!mailEnabled) return;
        for (int i = 0; i < 5 && mail.deliverNext(); i++) {
            // Process a bounded batch between polls.
        }
    }
}
