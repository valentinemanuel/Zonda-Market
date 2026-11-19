package com.zondamarket.shared.mail;

import java.util.UUID;

public interface MailSender {
    String send(UUID eventId, String recipient, String subject, String text);

    class DeliveryException extends RuntimeException {
        public DeliveryException(String message) {
            super(message);
        }
    }
}
