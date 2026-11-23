INSERT INTO zonda_market.account_address (id, account_id, street, city, province_code, postal_code, country, created_at, updated_at, deleted)
SELECT pg_temp.demo_id('address:comprador'), pg_temp.demo_id('account:comprador'),
       'Calle Demostración 450', 'La Plata', 'AR-B', '1900', 'AR',
       seeded_at - interval '40 days', seeded_at - interval '40 days', FALSE
FROM demo_context;

INSERT INTO zonda_market.cart (id, account_id, version, created_at, updated_at)
SELECT pg_temp.demo_id('cart:comprador'), pg_temp.demo_id('account:comprador'), 9,
       seeded_at - interval '40 days', seeded_at - interval '1 day'
FROM demo_context;

CREATE TEMP TABLE demo_order_items ON COMMIT DROP AS
SELECT scenario.name, scenario.order_status, scenario.sub_status,
       pg_temp.demo_id('order:' || scenario.name) AS order_id,
       pg_temp.demo_id('sub:' || scenario.name || ':' || listing.slug) AS sub_id,
       pg_temp.demo_id('quote:' || scenario.name) AS quote_id,
       pg_temp.demo_id('trace:' || scenario.name) AS trace_id,
       ctx.seeded_at - make_interval(days => scenario.days_ago) AS created_at,
       listing.title, listing.category_id, sku.id AS sku_id, sku.code, sku.attributes, sku.price,
       seller.id AS seller_id, seller.display_name AS seller_name,
       seller.shipping_cost AS shipping, category.commission_bps AS bps,
       round(sku.price * category.commission_bps / 10000, 2) AS commission
FROM (VALUES
    ('dividida', 'PAGADA', 'ENTREGADA', 'demo-auriculares-nuevo', 6),
    ('dividida', 'PAGADA', 'PREPARANDO', 'demo-taza-nuevo', 6),
    ('parcial', 'REEMBOLSO_PARCIAL', 'DESPACHADA', 'demo-lampara-nuevo', 10),
    ('vencida', 'EXPIRADA', 'CANCELADA', 'demo-botella-nuevo', 1)
) scenario(name, order_status, sub_status, slug, days_ago)
JOIN zonda_market.listing listing ON listing.slug = scenario.slug
JOIN zonda_market.seller_profile seller ON seller.id = listing.seller_id
JOIN zonda_market.category category ON category.id = listing.category_id
JOIN zonda_market.listing_sku sku ON sku.id = pg_temp.demo_id('sku:' || listing.slug || ':1')
CROSS JOIN demo_context ctx;

CREATE TEMP TABLE demo_orders ON COMMIT DROP AS
SELECT name, order_id, quote_id, trace_id, order_status, created_at, sum(price + shipping) AS total,
       jsonb_agg(jsonb_build_object(
           'sellerId', seller_id, 'sellerName', seller_name,
           'merchandise', price::text, 'shipping', shipping::text, 'commission', commission::text,
           'subtotal', (price + shipping)::text, 'items', jsonb_build_array(jsonb_build_object(
               'skuId', sku_id, 'skuCode', code, 'title', title, 'attributes', attributes, 'quantity', 1,
               'unitPrice', price::text, 'subtotal', price::text, 'commissionBps', bps, 'commission', commission::text
           ))
       ) ORDER BY seller_id) AS groups
FROM demo_order_items
GROUP BY name, order_id, quote_id, trace_id, order_status, created_at;

CREATE TEMP TABLE demo_address ON COMMIT DROP AS
SELECT id, jsonb_build_object('id', id, 'street', street, 'city', city, 'provinceCode', province_code,
                              'postalCode', postal_code, 'country', country) AS snapshot
FROM zonda_market.account_address WHERE id = pg_temp.demo_id('address:comprador');

INSERT INTO zonda_market.checkout_quote (id, cart_id, cart_version, address_id, snapshot, created_at, expires_at)
SELECT quote_id, pg_temp.demo_id('cart:comprador'), 1, a.id,
       jsonb_build_object('address', a.snapshot, 'groups', o.groups, 'total', o.total::text),
       o.created_at - interval '1 minute', o.created_at + interval '4 minutes'
FROM demo_orders o CROSS JOIN demo_address a;

INSERT INTO zonda_market.checkout_order
    (id, buyer_id, cart_id, quote_id, status, total, currency, idempotency_key, request_hash, trace_id, created_at, updated_at, expires_at)
SELECT order_id, pg_temp.demo_id('account:comprador'), pg_temp.demo_id('cart:comprador'), quote_id, order_status,
       total, 'ARS', 'demo-' || name, encode(sha256(convert_to(quote_id::text, 'UTF8')), 'hex'), trace_id, created_at,
       created_at + CASE order_status WHEN 'REEMBOLSO_PARCIAL' THEN interval '3 days'
                        WHEN 'EXPIRADA' THEN interval '15 minutes' ELSE interval '1 minute' END,
       created_at + interval '15 minutes'
FROM demo_orders;

INSERT INTO zonda_market.checkout_sub_order
    (id, order_id, seller_id, seller_name, status, address_snapshot, merchandise, shipping, commission,
     created_at, updated_at, fulfillment_version, carrier, tracking_code, shipped_at, delivered_at)
SELECT sub_id, order_id, seller_id, seller_name, sub_status, a.snapshot, price, shipping, commission,
       i.created_at, i.created_at + CASE sub_status WHEN 'ENTREGADA' THEN interval '2 days'
                           WHEN 'DESPACHADA' THEN interval '1 day' WHEN 'CANCELADA' THEN interval '15 minutes'
                           ELSE interval '1 minute' END,
       CASE sub_status WHEN 'ENTREGADA' THEN 2 WHEN 'DESPACHADA' THEN 1 ELSE 0 END,
       CASE WHEN sub_status IN ('ENTREGADA', 'DESPACHADA') THEN 'ANDREANI' END,
       CASE WHEN sub_status IN ('ENTREGADA', 'DESPACHADA') THEN 'DEMO-' || upper(name) END,
       CASE WHEN sub_status IN ('ENTREGADA', 'DESPACHADA') THEN i.created_at + interval '1 day' END,
       CASE WHEN sub_status = 'ENTREGADA' THEN i.created_at + interval '2 days' END
FROM demo_order_items i CROSS JOIN demo_address a;

INSERT INTO zonda_market.checkout_order_line
    (id, sub_order_id, sku_id, sku_code, title, attributes, quantity, unit_price, commission_bps, commission, created_at, category_id)
SELECT pg_temp.demo_id('line:' || sub_id), sub_id, sku_id, code, title, attributes, 1, price, bps, commission, created_at, category_id
FROM demo_order_items;

INSERT INTO zonda_market.stock_reservation (id, order_id, sku_id, quantity, status, created_at, updated_at, expires_at)
SELECT pg_temp.demo_id('reservation:' || sub_id), order_id, sku_id, 1,
       CASE WHEN order_status = 'EXPIRADA' THEN 'LIBERADA' ELSE 'CONSUMIDA' END, created_at,
       created_at + CASE WHEN order_status = 'EXPIRADA' THEN interval '15 minutes' ELSE interval '1 minute' END,
       created_at + interval '15 minutes'
FROM demo_order_items;

UPDATE zonda_market.listing_sku sku
SET available_stock = available_stock - sold.quantity, version = version + 2
FROM (
    SELECT sku_id, count(*)::int AS quantity FROM demo_order_items WHERE order_status <> 'EXPIRADA' GROUP BY sku_id
) sold WHERE sku.id = sold.sku_id;

INSERT INTO zonda_market.cart_item (id, cart_id, sku_id, quantity, reference_price, reference_title, reference_attributes, created_at, updated_at)
SELECT pg_temp.demo_id('cart-item:vencida'), pg_temp.demo_id('cart:comprador'), sku_id, 1, price, title, attributes,
       created_at - interval '2 minutes', created_at - interval '2 minutes'
FROM demo_order_items WHERE order_status = 'EXPIRADA';

INSERT INTO zonda_market.checkout_payment_outcome (order_id, reference, outcome, result, amount, currency, trace_id, created_at)
SELECT order_id, 'local-' || order_id, 'APPROVED', 'APPROVED', total, 'ARS', trace_id, created_at + interval '1 minute'
FROM demo_orders WHERE order_status <> 'EXPIRADA';

INSERT INTO zonda_market.checkout_sub_order_settlement (sub_order_id, merchandise, shipping, commission, currency, recorded_at)
SELECT sub_id, price, shipping, commission, 'ARS', created_at + interval '1 minute'
FROM demo_order_items WHERE order_status <> 'EXPIRADA';

INSERT INTO zonda_market.seller_review (id, sub_order_id, author_id, rating, comment, created_at, trace_id)
SELECT pg_temp.demo_id('review:dividida'), sub_id, pg_temp.demo_id('account:comprador'), 5,
       'Buen embalaje y despacho rápido. Los auriculares llegaron en buen estado.',
       created_at + interval '2 days 3 hours', trace_id
FROM demo_order_items WHERE sub_status = 'ENTREGADA';

INSERT INTO zonda_market.dispute
    (id, sub_order_id, status, reason, description, opened_at, assigned_by, assigned_at, version)
SELECT pg_temp.demo_id('dispute:' || name), sub_id,
       CASE name WHEN 'parcial' THEN 'CERRADA_REEMBOLSADA' ELSE 'ABIERTA' END,
       CASE name WHEN 'parcial' THEN 'Lámpara con base marcada' ELSE 'Consulta por un accesorio faltante' END,
       CASE name WHEN 'parcial' THEN 'La base llegó con una marca y la lámpara funciona.'
                 ELSE 'La caja llegó cerrada, pero faltaba el adaptador incluido en el pedido.' END,
       created_at + interval '2 days 4 hours',
       CASE WHEN name = 'parcial' THEN pg_temp.demo_id('account:admin') END,
       CASE WHEN name = 'parcial' THEN created_at + interval '2 days 6 hours' END,
       CASE WHEN name = 'parcial' THEN 3 ELSE 0 END
FROM demo_order_items WHERE name = 'parcial' OR sub_status = 'ENTREGADA';

INSERT INTO zonda_market.dispute_message (id, dispute_id, author_id, author_role, body, created_at, request_key)
SELECT pg_temp.demo_id('message:parcial'), pg_temp.demo_id('dispute:parcial'), seller.account_id, 'SELLER',
       'Podemos devolver una parte del importe por la marca de la base.',
       item.created_at + interval '2 days 7 hours', pg_temp.demo_id('message-key:parcial')
FROM demo_order_items item JOIN zonda_market.seller_profile seller ON seller.id = item.seller_id
WHERE item.name = 'parcial';

INSERT INTO zonda_market.dispute_resolution
    (id, dispute_id, order_id, sub_order_id, actor_id, request_key, expected_version, reason, outcome, status,
     amount, payment_reference, captured, provider_refund_id, trace_id, created_at, updated_at, next_attempt_at, attempts)
SELECT pg_temp.demo_id('refund:parcial'), pg_temp.demo_id('dispute:parcial'), order_id, sub_id,
       pg_temp.demo_id('account:admin'), pg_temp.demo_id('refund-key:parcial'), 1,
       'Se acordó una devolución parcial por el daño estético.', 'REEMBOLSO', 'CONFIRMADA',
       12000, 'local-' || order_id, price + shipping, 'local-refund-demo-parcial', trace_id,
       created_at + interval '2 days 8 hours', created_at + interval '3 days', created_at + interval '3 days', 1
FROM demo_order_items WHERE name = 'parcial';

INSERT INTO zonda_market.checkout_sub_order_refund
    (refund_id, sub_order_id, merchandise_refunded, shipping_refunded, commission_reversed, confirmed_at, trace_id)
SELECT pg_temp.demo_id('refund:parcial'), sub_id, 12000, 0, round(12000 * bps / 10000.0, 2),
       created_at + interval '3 days', trace_id
FROM demo_order_items WHERE name = 'parcial';

INSERT INTO zonda_market.checkout_outbox
    (id, order_id, event_type, sub_order_id, actor_id, trace_id, created_at, delivered_at, notification_pending)
SELECT pg_temp.demo_id('event:' || o.name || ':' || e.kind), o.order_id, e.kind,
       NULL, pg_temp.demo_id('account:comprador'), o.trace_id,
       o.created_at + e.delay, o.created_at + e.delay, FALSE
FROM demo_orders o
CROSS JOIN LATERAL (VALUES
    ('CHECKOUT_RESERVED', interval '0'),
    (CASE WHEN o.order_status = 'EXPIRADA' THEN 'CHECKOUT_EXPIRED' ELSE 'CHECKOUT_PAID' END,
     CASE WHEN o.order_status = 'EXPIRADA' THEN interval '15 minutes' ELSE interval '1 minute' END)
) e(kind, delay);

INSERT INTO zonda_market.checkout_outbox
    (id, order_id, event_type, sub_order_id, actor_id, trace_id, created_at, delivered_at, notification_pending)
SELECT pg_temp.demo_id('event:' || i.sub_id || ':' || event.kind), i.order_id, event.kind, i.sub_id, event.actor,
       i.trace_id, i.created_at + event.delay, i.created_at + event.delay, FALSE
FROM demo_order_items i JOIN zonda_market.seller_profile seller ON seller.id = i.seller_id
CROSS JOIN LATERAL (VALUES
    ('SUB_ORDER_SHIPPED', seller.account_id, interval '1 day', i.sub_status IN ('DESPACHADA', 'ENTREGADA')),
    ('DELIVERY_CONFIRMED', pg_temp.demo_id('account:comprador'), interval '2 days', i.sub_status = 'ENTREGADA'),
    ('DISPUTE_OPENED', pg_temp.demo_id('account:comprador'), interval '2 days 4 hours', i.sub_status = 'ENTREGADA' OR i.name = 'parcial'),
    ('DISPUTE_ASSIGNED', pg_temp.demo_id('account:admin'), interval '2 days 6 hours', i.name = 'parcial'),
    ('DISPUTE_RESOLVED', pg_temp.demo_id('account:admin'), interval '2 days 8 hours', i.name = 'parcial')
) event(kind, actor, delay, included)
WHERE event.included;

INSERT INTO zonda_market.payment_audit_event
    (id, action, resource_type, resource_id, order_id, dispute_id, diff, result, event_at, trace_id)
SELECT pg_temp.demo_id('audit:refund:parcial'), 'DISPUTE_REFUND_STATUS', 'DISPUTE', dispute_id, order_id, dispute_id,
       jsonb_build_object('amount', amount, 'before', 'PENDIENTE', 'after', status), status, updated_at, trace_id
FROM zonda_market.dispute_resolution WHERE id = pg_temp.demo_id('refund:parcial');

INSERT INTO zonda_market.purchase_notification (id, event_id, recipient_id, type, resource_id, target_path, created_at)
SELECT pg_temp.demo_id('notification:' || event.id), event.id, pg_temp.demo_id('account:comprador'),
       CASE event.event_type WHEN 'CHECKOUT_PAID' THEN 'PAYMENT_APPROVED' ELSE event.event_type END,
       COALESCE(event.sub_order_id, event.order_id), '/checkout/reserva/' || event.order_id, event.created_at
FROM zonda_market.checkout_outbox event JOIN demo_orders o ON o.order_id = event.order_id
WHERE event.event_type IN ('CHECKOUT_PAID', 'SUB_ORDER_SHIPPED');

DROP FUNCTION pg_temp.demo_id(TEXT);
