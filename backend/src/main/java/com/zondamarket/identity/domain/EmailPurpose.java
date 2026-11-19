package com.zondamarket.identity.domain;

import java.time.Duration;

public enum EmailPurpose {
    VERIFY_EMAIL("/verificar-correo", "Verificá tu correo en Zonda Market", Duration.ofHours(12)),
    RESET_PASSWORD("/restablecer-clave", "Restablecé tu contraseña en Zonda Market", Duration.ofMinutes(30));

    private final String path;
    private final String subject;
    private final Duration ttl;

    EmailPurpose(String path, String subject, Duration ttl) {
        this.path = path;
        this.subject = subject;
        this.ttl = ttl;
    }

    public String path() { return path; }
    public String subject() { return subject; }
    public Duration ttl() { return ttl; }
}
