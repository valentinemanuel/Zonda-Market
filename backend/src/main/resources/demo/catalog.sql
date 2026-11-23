-- Deterministic identifiers are scoped to this fixture version.
CREATE FUNCTION pg_temp.demo_id(label TEXT) RETURNS UUID
LANGUAGE SQL IMMUTABLE STRICT RETURN md5('zonda-demo-v1:' || label)::UUID;

CREATE TEMP TABLE demo_sellers (idx INTEGER, slug TEXT, name TEXT, kind TEXT, bio TEXT, shipping NUMERIC(19,2))
ON COMMIT DROP;

INSERT INTO demo_sellers VALUES
    (0, 'taller-puerto', 'Taller Puerto', 'COMERCIO', 'Tecnología y artículos seleccionados en La Plata.', 2800),
    (1, 'casa-jarilla', 'Casa Jarilla', 'COMERCIO', 'Diseño cotidiano para la casa y el escritorio.', 2400),
    (2, 'ropero-sur', 'Ropero del Sur', 'PERSONAL', 'Prendas, accesorios y objetos que pueden seguir en uso.', 1850),
    (3, 'estacion-norte', 'Estación Norte', 'PERSONAL', 'Equipo de uso personal para deportes y salidas cortas.', 2100),
    (4, 'segunda-vuelta', 'Segunda Vuelta', 'PERSONAL', 'Objetos cuidados para estudio, trabajo y tiempo libre.', 1600);

INSERT INTO zonda_market.user_account
    (id, email, password_hash, role, email_verified, display_name, created_at, updated_at, deleted)
SELECT pg_temp.demo_id('account:' || actor.slug), actor.slug || '@demo.zonda.test',
       CASE WHEN actor.role = 'ADMIN' THEN ctx.admin_hash ELSE ctx.participant_hash END,
       actor.role, TRUE, actor.name, ctx.seeded_at - interval '45 days', ctx.seeded_at - interval '45 days', FALSE
FROM (
    SELECT slug, name, 'SELLER' AS role FROM demo_sellers
    UNION ALL VALUES ('comprador', 'Lucía de la demo', 'USER'), ('admin', 'Administración demo', 'ADMIN')
) actor CROSS JOIN demo_context ctx;

INSERT INTO zonda_market.seller_profile
    (id, account_id, type, display_name, slug, bio, shipping_cost, activation_trace_id, created_at, updated_at, deleted)
SELECT pg_temp.demo_id('seller:' || s.slug), pg_temp.demo_id('account:' || s.slug),
       s.kind, s.name, 'demo-' || s.slug, s.bio, s.shipping, pg_temp.demo_id('activation:' || s.slug),
       seeded_at - interval '44 days', seeded_at - interval '44 days', FALSE
FROM demo_sellers s CROSS JOIN demo_context;

INSERT INTO zonda_market.category (id, name, slug, attribute_schema, commission_bps, created_at, updated_at)
SELECT pg_temp.demo_id('category:' || c.slug), c.name, c.slug, c.attributes::jsonb, c.bps,
       ctx.seeded_at - interval '45 days', ctx.seeded_at - interval '45 days'
FROM (VALUES
    ('demo-hogar', 'Hogar y decoración', '{"medida":["250 ml","300 ml","350 ml","30 cm","35 cm","40 cm","Baja","Estándar","Alta","22 cm","24 cm","26 cm","12 cm","15 cm","18 cm"]}', 800),
    ('demo-deportes', 'Deportes y aire libre', '{"formato":["4 mm","6 mm","8 mm","2 kg","3 kg","4 kg","500 ml","750 ml","1 L"]}', 650),
    ('demo-calzado', 'Calzado', '{"talle":["38","39","40"]}', 900),
    ('demo-accesorios', 'Accesorios', '{}', 750)
) c(slug, name, attributes, bps) CROSS JOIN demo_context ctx;

INSERT INTO zonda_market.category_commission_period (category_id, category_version, commission_bps, valid_from)
SELECT c.id, c.version, c.commission_bps, ctx.seeded_at - interval '45 days'
FROM zonda_market.category c CROSS JOIN demo_context ctx
WHERE c.id = pg_temp.demo_id('category:' || c.slug);

CREATE TEMP TABLE demo_products (
    idx INTEGER GENERATED ALWAYS AS IDENTITY,
    slug TEXT, category TEXT, title TEXT, description TEXT, price NUMERIC(19,2), photo INTEGER,
    option_key TEXT, options TEXT[]
) ON COMMIT DROP;

INSERT INTO demo_products (slug, category, title, description, price, photo, option_key, options) VALUES
    ('auriculares', 'tecnologia', 'Auriculares de estudio', 'Copa circumaural y vincha acolchada. Pensados para edición y escucha en escritorio.', 68000, 30428610, 'conexion', ARRAY['3,5 mm', '6,35 mm', 'USB-C']),
    ('teclado', 'tecnologia', 'Teclado mecánico compacto', 'Distribución compacta y teclas desmontables para escritorio.', 88000, 15372894, 'switch', ARRAY['Lineal', 'Táctil', 'Sonoro']),
    ('camara', 'tecnologia', 'Cámara digital', 'Cuerpo compacto para fotografía de viaje, incluye correa de mano.', 350000, 5613269, 'kit', ARRAY['Cuerpo', 'Con estuche', 'Con memoria']),
    ('notebook', 'tecnologia', 'Notebook portátil', 'Equipo de 14 pulgadas para estudio y trabajo, con cargador.', 680000, 7670739, 'memoria', ARRAY['8 GB', '16 GB', '24 GB']),
    ('zapatillas', 'demo-calzado', 'Zapatillas urbanas', 'Par de uso diario con suela de goma y cordones.', 97000, 18946896, 'talle', ARRAY['38', '39', '40']),
    ('remera', 'indumentaria', 'Remera básica de algodón', 'Corte recto y cuello redondo para combinar en capas.', 21500, 37704838, 'talle', ARRAY['S', 'M', 'L']),
    ('buzo', 'indumentaria', 'Buzo con capucha', 'Abrigo de media estación con bolsillo frontal y cordones.', 47500, 6786666, 'talle', ARRAY['S', 'M', 'L']),
    ('mochila', 'demo-accesorios', 'Mochila de trabajo', 'Compartimento principal con cierre y espacio para una notebook.', 64000, 15246346, 'capacidad', ARRAY['15 L', '20 L', '25 L']),
    ('taza', 'demo-hogar', 'Taza de cerámica', 'Pieza esmaltada con asa y terminación mate.', 17500, 31046284, 'medida', ARRAY['250 ml', '300 ml', '350 ml']),
    ('lampara', 'demo-hogar', 'Lámpara de mesa', 'Luz de apoyo para escritorio o mesa de luz.', 48000, 12801238, 'medida', ARRAY['30 cm', '35 cm', '40 cm']),
    ('silla', 'demo-hogar', 'Silla de madera', 'Asiento de madera para comedor o escritorio.', 93000, 20206663, 'medida', ARRAY['Baja', 'Estándar', 'Alta']),
    ('sarten', 'demo-hogar', 'Sartén antiadherente', 'Base plana y mango de agarre térmico.', 35000, 8629104, 'medida', ARRAY['22 cm', '24 cm', '26 cm']),
    ('mat', 'demo-deportes', 'Colchoneta para yoga', 'Superficie antideslizante y formato enrollable.', 29800, 6193621, 'formato', ARRAY['4 mm', '6 mm', '8 mm']),
    ('mancuernas', 'demo-deportes', 'Par de mancuernas', 'Peso fijo y agarre revestido para entrenamiento doméstico.', 42500, 4959808, 'formato', ARRAY['2 kg', '3 kg', '4 kg']),
    ('botella', 'demo-deportes', 'Botella reutilizable', 'Botella para entrenamiento o caminatas, con tapa roscada.', 18500, 20292101, 'formato', ARRAY['500 ml', '750 ml', '1 L']),
    ('bolso', 'demo-accesorios', 'Bolso mochila para escapadas', 'Correas ajustables y bolsillo exterior para viajes cortos.', 52000, 16400143, 'capacidad', ARRAY['15 L', '20 L', '25 L']),
    ('maceta', 'demo-hogar', 'Maceta decorativa', 'Contenedor para plantas de interior con acabado liso.', 21000, 2326115, 'medida', ARRAY['12 cm', '15 cm', '18 cm']),
    ('lentes', 'demo-accesorios', 'Anteojos de sol', 'Marco liviano con estuche para guardado.', 27500, 9733595, 'capacidad', ARRAY['Sin estuche', 'Estuche blando', 'Estuche rígido']),
    ('gorra', 'demo-accesorios', 'Gorra deportiva ajustable', 'Visera curva y ajuste trasero para actividades al aire libre.', 16500, 16151001, 'capacidad', ARRAY['Ajuste corto', 'Ajuste medio', 'Ajuste largo']),
    ('tableta', 'tecnologia', 'Tableta digital', 'Pantalla táctil para lectura y notas con conexión inalámbrica.', 269000, 4523063, 'kit', ARRAY['Tableta', 'Con funda', 'Con teclado']);

CREATE TEMP TABLE demo_listings ON COMMIT DROP AS
SELECT pg_temp.demo_id('listing:' || p.slug || ':' || offer.slug) AS id,
       pg_temp.demo_id('seller:' || seller.slug) AS seller_id,
       'demo-' || p.slug || '-' || offer.slug AS slug, category.id AS category_id,
       p.title || ' · ' || offer.label AS title,
       p.description || ' Publicación ficticia para la demo. Foto ilustrativa.' AS description,
       jsonb_build_object('Condición', offer.label) AS specs,
       round(p.price * offer.factor, 2) AS price, p.photo, p.title AS alt,
       p.option_key, p.options, offer.num, ctx.seeded_at - make_interval(days => 25 - ((p.idx + offer.num) % 20)) AS created_at
FROM demo_products p
JOIN zonda_market.category category ON category.slug = p.category AND NOT category.deleted
CROSS JOIN (VALUES
    (0, 'nuevo', 'nuevo', 1.00),
    (1, 'como-nuevo', 'como nuevo', 0.87),
    (2, 'buen-estado', 'buen estado', 0.73),
    (3, 'segunda-mano', 'segunda mano', 0.62)
) offer(num, slug, label, factor)
JOIN demo_sellers seller ON seller.idx = (p.idx - 1 + offer.num) % 5
CROSS JOIN demo_context ctx;

INSERT INTO zonda_market.listing
    (id, seller_id, category_id, slug, title, description, specs, status, created_at, updated_at)
SELECT id, seller_id, category_id, slug, title, description, specs, 'ACTIVA', created_at, created_at
FROM demo_listings;

INSERT INTO zonda_market.listing_sku
    (id, listing_id, code, attributes, price, available_stock, created_at, updated_at)
SELECT pg_temp.demo_id('sku:' || l.slug || ':' || opt.idx), l.id,
       upper(l.slug) || '-' || opt.idx, jsonb_build_object(l.option_key, opt.value),
       l.price + (opt.idx - 1) * round(l.price * 0.04, 2),
       CASE WHEN l.num = 0 AND opt.idx = 1 THEN 12 WHEN l.num = 3 AND opt.idx = 3 THEN 0 ELSE 6 - l.num END,
       l.created_at, l.created_at
FROM demo_listings l CROSS JOIN LATERAL unnest(l.options) WITH ORDINALITY opt(value, idx);

INSERT INTO zonda_market.listing_image
    (id, listing_id, provider, public_id, url, thumbnail_url, alt, position, created_at, updated_at)
SELECT pg_temp.demo_id('image:' || slug), id, 'external', pg_temp.demo_id('photo:' || slug),
       format('https://images.pexels.com/photos/%s/pexels-photo-%s.jpeg?auto=compress&cs=tinysrgb&w=1200', photo, photo),
       format('https://images.pexels.com/photos/%s/pexels-photo-%s.jpeg?auto=compress&cs=tinysrgb&w=480', photo, photo),
       alt, 0, created_at, created_at
FROM demo_listings;
