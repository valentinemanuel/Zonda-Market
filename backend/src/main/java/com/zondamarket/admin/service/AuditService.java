package com.zondamarket.admin.service;

import com.zondamarket.admin.dto.AuditDtos.AuditEventResponse;
import com.zondamarket.admin.dto.AuditDtos.Page;
import com.zondamarket.admin.dto.AuditDtos.PaymentIncident;
import com.zondamarket.admin.dto.AuditDtos.RecoveryResponse;
import com.zondamarket.admin.repository.AuditStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private final AuditStore store;
    private final Clock clock;

    AuditService(AuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Page<AuditEventResponse> events(UUID actor, String action, UUID resource, UUID trace,
            LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "AUDIT_INVALID_RANGE", "La fecha Desde debe ser anterior o igual a Hasta.");
        }
        return Page.of(store.events(actor, action, resource, trace, startOfDay(from),
                to == null ? null : startOfDay(to.plusDays(1)), page, size), page, size);
    }

    public Page<PaymentIncident> incidents(int page, int size) {
        return Page.of(store.incidents(page, size), page, size);
    }

    @Transactional
    public RecoveryResponse reconcile(UUID id, UUID key, UUID actor, String reason, UUID trace) {
        var kind = store.kind(id).orElseThrow(AuditService::notFound);
        if (!store.lockIncident(kind, id)) throw notFound();

        var previous = store.recovery(id, key).orElse(null);
        if (previous != null) {
            if (!previous.actorId().equals(actor) || !previous.reason().equals(reason)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "RECOVERY_KEY_REUSED", "La clave ya se usó con otra solicitud.");
            }
            return new RecoveryResponse(previous.id(), id, previous.traceId(), previous.createdAt().toInstant());
        }

        var now = clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (store.coolingDown(id, now.minusSeconds(60))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "RECOVERY_COOLDOWN", "Esperá un minuto entre solicitudes de recuperación.");
        }
        if (!store.requeue(kind, id, now)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "INCIDENT_NOT_RECOVERABLE", "La incidencia no admite una recuperación en este estado.");
        }

        var recovery = UUID.randomUUID();
        store.appendRecovery(recovery, id, key, actor, reason, trace, now);
        return new RecoveryResponse(recovery, id, trace, now.toInstant());
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(BUENOS_AIRES).toOffsetDateTime();
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No encontramos la incidencia.");
    }
}
