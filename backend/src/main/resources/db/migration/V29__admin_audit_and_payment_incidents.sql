ALTER TABLE zonda_market.payment_webhook_inbox ADD COLUMN manual_retry BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE zonda_market.payment_compensation ADD COLUMN reconcile_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE zonda_market.dispute_resolution ADD COLUMN reconcile_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE zonda_market.payment_incident_recovery (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    request_key UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    order_id UUID,
    dispute_id UUID,
    trace_id UUID NOT NULL,
    original_trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (incident_id, request_key)
);
CREATE INDEX payment_incident_recovery_latest_idx ON zonda_market.payment_incident_recovery(incident_id, created_at DESC);

CREATE TABLE zonda_market.payment_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action VARCHAR(24) NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    resource_id UUID NOT NULL,
    order_id UUID,
    dispute_id UUID,
    diff JSONB NOT NULL,
    result VARCHAR(24) NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    trace_id UUID NOT NULL
);
CREATE INDEX payment_audit_time_idx ON zonda_market.payment_audit_event(event_at DESC, id DESC);
CREATE INDEX payment_audit_order_idx ON zonda_market.payment_audit_event(order_id);
CREATE INDEX payment_audit_dispute_idx ON zonda_market.payment_audit_event(dispute_id);
CREATE INDEX payment_audit_trace_idx ON zonda_market.payment_audit_event(trace_id);

CREATE FUNCTION zonda_market.prevent_audit_rewrite() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'El historial de auditoría solo admite inserciones';
END;
$$;
CREATE TRIGGER payment_audit_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON zonda_market.payment_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION zonda_market.prevent_audit_rewrite();
CREATE TRIGGER incident_recovery_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON zonda_market.payment_incident_recovery
    FOR EACH STATEMENT EXECUTE FUNCTION zonda_market.prevent_audit_rewrite();

-- Import the existing state. Future transitions are appended in the same transaction as the domain change.
INSERT INTO zonda_market.payment_audit_event
    (action, resource_type, resource_id, order_id, dispute_id, diff, result, event_at, trace_id)
SELECT 'PAYMENT_VERIFIED', 'ORDER', a.order_id, a.order_id, NULL::uuid,
    jsonb_build_object('amount', p.amount, 'currency', p.currency, 'method', p.method), p.status, p.verified_at, p.trace_id
FROM zonda_market.payment_transaction p JOIN zonda_market.payment_attempt a ON a.id = p.attempt_id
UNION ALL
SELECT 'COMPENSATION_STATUS', 'COMPENSATION', id, order_id, NULL,
    jsonb_build_object('amount', amount, 'cause', reason, 'after', status), status, updated_at, trace_id
FROM zonda_market.payment_compensation
UNION ALL
SELECT 'DISPUTE_REFUND_STATUS', 'DISPUTE', dispute_id, order_id, dispute_id,
    jsonb_build_object('amount', amount, 'after', status), status, updated_at, trace_id
FROM zonda_market.dispute_resolution WHERE outcome = 'REEMBOLSO' AND status <> 'PENDIENTE'
UNION ALL
SELECT 'WEBHOOK_FINISHED', 'INCIDENT', id, NULL, NULL,
    jsonb_build_object('attempts', attempts), 'FALLIDA', finished_at, trace_id
FROM zonda_market.payment_webhook_inbox WHERE finished_at IS NOT NULL AND failure_code IS NOT NULL;

CREATE FUNCTION zonda_market.audit_verified_payment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status = OLD.status THEN
        RETURN NULL;
    END IF;
    INSERT INTO zonda_market.payment_audit_event
        (action, resource_type, resource_id, order_id, diff, result, event_at, trace_id)
    SELECT 'PAYMENT_VERIFIED', 'ORDER', a.order_id, a.order_id,
        jsonb_strip_nulls(jsonb_build_object('amount', NEW.amount, 'currency', NEW.currency, 'method', NEW.method,
            'before', OLD.status, 'after', NEW.status)), NEW.status, NEW.verified_at, NEW.trace_id
    FROM zonda_market.payment_attempt a WHERE a.id = NEW.attempt_id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER payment_verified_audit AFTER INSERT OR UPDATE OF status ON zonda_market.payment_transaction
    FOR EACH ROW EXECUTE FUNCTION zonda_market.audit_verified_payment();

CREATE FUNCTION zonda_market.audit_refund_status() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status = OLD.status THEN
        RETURN NULL;
    END IF;
    IF TG_TABLE_NAME = 'dispute_resolution' THEN
        INSERT INTO zonda_market.payment_audit_event
            (action, resource_type, resource_id, order_id, dispute_id, diff, result, event_at, trace_id)
        VALUES ('DISPUTE_REFUND_STATUS', 'DISPUTE', NEW.dispute_id, NEW.order_id, NEW.dispute_id,
            jsonb_build_object('amount', NEW.amount, 'before', OLD.status, 'after', NEW.status),
            NEW.status, NEW.updated_at, NEW.trace_id);
    ELSE
        INSERT INTO zonda_market.payment_audit_event
            (action, resource_type, resource_id, order_id, diff, result, event_at, trace_id)
        VALUES ('COMPENSATION_STATUS', 'COMPENSATION', NEW.id, NEW.order_id,
            jsonb_strip_nulls(jsonb_build_object('amount', NEW.amount, 'cause', NEW.reason,
                'before', OLD.status, 'after', NEW.status)), NEW.status, NEW.updated_at, NEW.trace_id);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER compensation_audit AFTER INSERT OR UPDATE OF status ON zonda_market.payment_compensation
    FOR EACH ROW EXECUTE FUNCTION zonda_market.audit_refund_status();
CREATE TRIGGER dispute_refund_audit AFTER UPDATE OF status ON zonda_market.dispute_resolution
    FOR EACH ROW WHEN (NEW.outcome = 'REEMBOLSO') EXECUTE FUNCTION zonda_market.audit_refund_status();

CREATE FUNCTION zonda_market.audit_webhook_result() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zonda_market.payment_audit_event
        (action, resource_type, resource_id, diff, result, event_at, trace_id)
    VALUES ('WEBHOOK_FINISHED', 'INCIDENT', NEW.id, jsonb_build_object('attempts', NEW.attempts),
        CASE WHEN NEW.failure_code IS NULL THEN 'CONCILIADA' ELSE 'FALLIDA' END, NEW.finished_at, NEW.trace_id);
    RETURN NULL;
END;
$$;
CREATE TRIGGER webhook_result_audit AFTER INSERT OR UPDATE OF finished_at ON zonda_market.payment_webhook_inbox
    FOR EACH ROW WHEN (NEW.finished_at IS NOT NULL) EXECUTE FUNCTION zonda_market.audit_webhook_result();

-- Project only the immutable fields of existing journals.
CREATE VIEW zonda_market.admin_audit_event
    (id, actor_id, role, action, resource_type, resource_id, order_id, dispute_id, diff, result, event_at, trace_id) AS
SELECT id, actor_id, 'ADMIN', 'MODERATION_' || action,
    CASE WHEN listing_id IS NULL THEN 'ACCOUNT' ELSE 'LISTING' END, COALESCE(listing_id, account_id),
    NULL::uuid, NULL::uuid, jsonb_build_object('before', before_state, 'after', after_state), 'APPLIED', created_at, trace_id
FROM zonda_market.moderation_audit
UNION ALL
SELECT m.id, m.actor_id, 'SELLER', 'STOCK_ADJUSTED', 'SKU', m.sku_id, NULL, NULL,
    jsonb_build_object('before', m.before_stock, 'after', m.after_stock, 'delta', m.delta), 'APPLIED', m.created_at, m.trace_id
FROM zonda_market.stock_movement m
UNION ALL
SELECT md5('commission:' || category_id || ':' || category_version)::uuid, actor_id, 'ADMIN', 'COMMISSION_CHANGED',
    'CATEGORY', category_id, NULL, NULL, jsonb_strip_nulls(jsonb_build_object('beforeBps', previous_bps, 'afterBps', commission_bps)),
    'APPLIED', valid_from, trace_id
FROM (SELECT *, lag(commission_bps) OVER (PARTITION BY category_id ORDER BY category_version) AS previous_bps
      FROM zonda_market.category_commission_period) periods
WHERE actor_id IS NOT NULL
UNION ALL
SELECT md5('checkout-outcome:' || order_id)::uuid, NULL, 'SYSTEM', 'CHECKOUT_PAYMENT_OUTCOME', 'ORDER',
    order_id, order_id, NULL, jsonb_build_object('amount', amount, 'currency', currency), result, created_at, trace_id
FROM zonda_market.checkout_payment_outcome
UNION ALL
SELECT o.id, o.actor_id, CASE WHEN o.event_type = 'DISPUTE_OPENED' THEN 'BUYER' ELSE 'ADMIN' END, o.event_type,
    'DISPUTE', d.id, o.order_id, d.id, '{}'::jsonb, 'APPLIED', o.created_at, o.trace_id
FROM zonda_market.checkout_outbox o JOIN zonda_market.dispute d ON d.sub_order_id = o.sub_order_id
WHERE o.event_type IN ('DISPUTE_OPENED', 'DISPUTE_ASSIGNED')
UNION ALL
SELECT id, actor_id, 'ADMIN', 'DISPUTE_DECIDED', 'DISPUTE', dispute_id, order_id, dispute_id,
    jsonb_strip_nulls(jsonb_build_object('amount', amount)), outcome, created_at, trace_id
FROM zonda_market.dispute_resolution
UNION ALL
SELECT id, NULL, 'SYSTEM', action, resource_type, resource_id, order_id, dispute_id, diff, result, event_at, trace_id
FROM zonda_market.payment_audit_event
UNION ALL
SELECT id, actor_id, 'ADMIN', 'INCIDENT_RECONCILE_REQUESTED', 'INCIDENT', incident_id, order_id, dispute_id,
    jsonb_build_object('originalTraceId', original_trace_id), 'QUEUED', created_at, trace_id
FROM zonda_market.payment_incident_recovery;

CREATE VIEW zonda_market.admin_payment_incident
    (id, kind, status, order_id, dispute_id, trace_id, attempts, updated_at, can_reconcile) AS
SELECT id, 'WEBHOOK', CASE WHEN manual_retry THEN 'EN_COLA' WHEN finished_at IS NULL THEN 'PENDIENTE'
        WHEN failure_code IS NOT NULL THEN 'FALLIDA' ELSE 'CONCILIADA' END,
    NULL::uuid, NULL::uuid, trace_id, attempts, COALESCE(finished_at, received_at),
    finished_at IS NOT NULL AND failure_code IS NOT NULL
FROM zonda_market.payment_webhook_inbox w
WHERE failure_code IS NOT NULL OR manual_retry
    OR EXISTS (SELECT FROM zonda_market.payment_incident_recovery r WHERE r.incident_id = w.id)
UNION ALL
SELECT id, 'COMPENSATION', CASE WHEN reconcile_only THEN 'EN_COLA' ELSE status END,
    order_id, NULL, trace_id, attempts, updated_at, status = 'EN_REVISION' AND NOT reconcile_only
FROM zonda_market.payment_compensation
UNION ALL
SELECT id, 'DISPUTE_REFUND', CASE WHEN reconcile_only THEN 'EN_COLA' ELSE status END,
    order_id, dispute_id, trace_id, attempts, updated_at, status = 'EN_REVISION' AND NOT reconcile_only AND sent_at IS NOT NULL
FROM zonda_market.dispute_resolution WHERE outcome = 'REEMBOLSO';
