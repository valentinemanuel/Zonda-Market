package com.zondamarket.identity.service;

import com.zondamarket.identity.repository.IdentityEmailStore;
import com.zondamarket.shared.mail.MailSender;
import com.zondamarket.shared.mail.MailSender.DeliveryException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IdentityMailOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityMailOutbox.class);
    private final IdentityEmailStore store;
    private final EmailLinkTokens tokens;
    private final MailSender sender;
    private final Clock clock;

    IdentityMailOutbox(IdentityEmailStore store, EmailLinkTokens tokens, MailSender sender, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.sender = sender;
        this.clock = clock;
    }

    public boolean deliverNext() {
        Instant now = clock.instant();
        var delivery = store.claim(now, now.plusSeconds(60)).orElse(null);
        if (delivery == null) {
            return false;
        }

        String token = tokens.forEvent(delivery.id(), delivery.purpose());
        String link = delivery.linkBase() + "#token=" + token;
        String body = "Abrí este enlace para continuar:\n\n" + link
                + "\n\nSi no hiciste esta solicitud, podés ignorar este mensaje.";
        try {
            String providerId = sender.send(delivery.id(), delivery.recipient(), delivery.purpose().subject(), body);
            store.sent(delivery, clock.instant(), providerId);
        } catch (DeliveryException failure) {
            long delay = Math.min(300, 5L << Math.min(delivery.attempts() - 1, 6));
            store.retry(delivery, clock.instant().plusSeconds(delay));
            LOG.warn("Envío pendiente eventId={} intento={}", delivery.id(), delivery.attempts());
        }
        return true;
    }
}
