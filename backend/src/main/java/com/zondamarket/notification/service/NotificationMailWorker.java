package com.zondamarket.notification.service;

import com.zondamarket.notification.repository.NotificationStore;
import com.zondamarket.shared.mail.MailSender;
import com.zondamarket.shared.mail.MailSender.DeliveryException;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationMailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationMailWorker.class);
    private final NotificationStore store;
    private final MailSender sender;
    private final Clock clock;
    private final URI origin;

    NotificationMailWorker(NotificationStore store, MailSender sender, Clock clock,
            @Value("${zonda.mail.public-origin}") URI origin) {
        this.store = store;
        this.sender = sender;
        this.clock = clock;
        this.origin = origin;
    }

    public boolean deliverNext() {
        var now = clock.instant();
        var delivery = store.claimMail(now, now.plusSeconds(60)).orElse(null);
        if (delivery == null) return false;

        String body = delivery.type().message() + "\n\nConsultá el detalle en tu cuenta de Zonda Market:\n"
                + origin.resolve(delivery.href());
        try {
            String providerId = sender.send(delivery.id(), delivery.email(), delivery.type().subject(), body);
            store.sent(delivery, clock.instant(), providerId);
        } catch (DeliveryException failure) {
            long delay = Math.min(300, 5L << Math.min(delivery.attempts() - 1, 6));
            store.retry(delivery, clock.instant().plusSeconds(delay));
            LOG.warn("Correo de compra pendiente notificationId={} intento={}", delivery.id(), delivery.attempts());
        }
        return true;
    }
}
