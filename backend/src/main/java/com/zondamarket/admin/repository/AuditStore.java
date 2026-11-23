package com.zondamarket.admin.repository;

import com.zondamarket.admin.dto.AuditDtos.AuditEventResponse;
import com.zondamarket.admin.dto.AuditDtos.IncidentKind;
import com.zondamarket.admin.dto.AuditDtos.PaymentIncident;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditStore {
    private final JdbcClient sql;
    private final ObjectMapper json;

    AuditStore(JdbcClient sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public List<AuditEventResponse> events(UUID actor, String action, UUID resource, UUID trace,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        return sql.sql("""
                SELECT * FROM zonda_market.admin_audit_event
                WHERE (CAST(:actor AS uuid) IS NULL OR actor_id = :actor)
                  AND (CAST(:action AS text) IS NULL OR action = :action)
                  AND (CAST(:resource AS uuid) IS NULL OR resource_id = :resource OR order_id = :resource OR dispute_id = :resource)
                  AND (CAST(:trace AS uuid) IS NULL OR trace_id = :trace OR diff ->> 'originalTraceId' = CAST(:trace AS text))
                  AND (CAST(:from AS timestamptz) IS NULL OR event_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR event_at < :to)
                ORDER BY event_at DESC, id DESC LIMIT :limit OFFSET :offset
                """).param("actor", actor).param("action", action).param("resource", resource).param("trace", trace)
                .param("from", from).param("to", to).param("limit", size + 1).param("offset", (long) page * size)
                .query((rs, row) -> new AuditEventResponse(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("role"), rs.getString("action"),
                        rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getObject("dispute_id", UUID.class),
                        json.readTree(rs.getString("diff")), rs.getString("result"),
                        rs.getObject("event_at", OffsetDateTime.class).toInstant(), rs.getObject("trace_id", UUID.class)))
                .list();
    }

    public List<PaymentIncident> incidents(int page, int size) {
        return sql.sql("""
                SELECT i.*, r.created_at + interval '60 seconds' AS reconcile_available_at
                FROM zonda_market.admin_payment_incident i
                LEFT JOIN LATERAL (
                    SELECT created_at FROM zonda_market.payment_incident_recovery WHERE incident_id = i.id
                    ORDER BY created_at DESC LIMIT 1
                ) r ON TRUE
                ORDER BY COALESCE(r.created_at, i.updated_at) DESC, i.id DESC LIMIT :limit OFFSET :offset
                """).param("limit", size + 1).param("offset", (long) page * size).query(PaymentIncident.class).list();
    }

    public Optional<IncidentKind> kind(UUID id) {
        return sql.sql("SELECT kind FROM zonda_market.admin_payment_incident WHERE id = :id")
                .param("id", id).query(String.class).optional().map(IncidentKind::valueOf);
    }

    public boolean lockIncident(IncidentKind kind, UUID id) {
        String table = switch (kind) {
            case WEBHOOK -> "payment_webhook_inbox";
            case COMPENSATION -> "payment_compensation";
            case DISPUTE_REFUND -> "dispute_resolution";
        };
        return sql.sql("SELECT id FROM zonda_market." + table + " WHERE id = :id FOR UPDATE")
                .param("id", id).query(UUID.class).optional().isPresent();
    }

    public Optional<Recovery> recovery(UUID id, UUID key) {
        return sql.sql("""
                SELECT id, actor_id, reason, trace_id, created_at FROM zonda_market.payment_incident_recovery
                WHERE incident_id = :id AND request_key = :key
                """).param("id", id).param("key", key).query(Recovery.class).optional();
    }

    public boolean coolingDown(UUID id, OffsetDateTime since) {
        return sql.sql("""
                SELECT EXISTS (SELECT FROM zonda_market.payment_incident_recovery
                    WHERE incident_id = :id AND created_at > :since)
                """).param("id", id).param("since", since).query(Boolean.class).single();
    }

    public boolean requeue(IncidentKind kind, UUID id, OffsetDateTime now) {
        String command = switch (kind) {
            case WEBHOOK -> """
                UPDATE zonda_market.payment_webhook_inbox SET finished_at = NULL, manual_retry = TRUE, next_attempt_at = :now
                WHERE id = :id AND finished_at IS NOT NULL AND failure_code IS NOT NULL
                  AND (lease_until IS NULL OR lease_until <= :now)
                """;
            case COMPENSATION -> """
                UPDATE zonda_market.payment_compensation SET reconcile_only = TRUE, next_attempt_at = :now
                WHERE id = :id AND status = 'EN_REVISION' AND NOT reconcile_only
                  AND (lease_until IS NULL OR lease_until <= :now)
                """;
            case DISPUTE_REFUND -> """
                UPDATE zonda_market.dispute_resolution SET reconcile_only = TRUE, next_attempt_at = :now
                WHERE id = :id AND status = 'EN_REVISION' AND NOT reconcile_only AND sent_at IS NOT NULL
                  AND (lease_until IS NULL OR lease_until <= :now)
                """;
        };
        return sql.sql(command).param("id", id).param("now", now).update() == 1;
    }

    public void appendRecovery(UUID recovery, UUID id, UUID key, UUID actor, String reason, UUID trace, OffsetDateTime now) {
        sql.sql("""
                INSERT INTO zonda_market.payment_incident_recovery
                    (id, incident_id, request_key, actor_id, reason, order_id, dispute_id, trace_id, original_trace_id, created_at)
                SELECT :recovery, id, :key, :actor, :reason, order_id, dispute_id, :trace, trace_id, :now
                FROM zonda_market.admin_payment_incident WHERE id = :id
                """).param("recovery", recovery).param("id", id).param("key", key).param("actor", actor)
                .param("reason", reason).param("trace", trace).param("now", now).update();
    }

    public record Recovery(UUID id, UUID actorId, String reason, UUID traceId, OffsetDateTime createdAt) {}
}
