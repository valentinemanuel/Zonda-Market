# Zonda Market

Marketplace C2C/B2C. El [roadmap](docs/roadmap.md) describe las etapas y las entregas.

## Arranque local

Requisitos: Docker con Compose v2 y los puertos locales 3000 y 8080 disponibles.

1. Copiá `.env.example` a `.env` y completá `POSTGRES_PASSWORD` con una contraseña de desarrollo. Generá `AUTH_JWT_KEY` con `openssl rand -base64 32` o, en PowerShell 7, con `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`. Guardá la clave en `.env`.
2. Desde la raíz, ejecutá `docker compose up --build --wait`.
3. Abrí [Estado del sistema](http://localhost:3000/estado). También podés comprobar que `docker compose ps` liste los servicios como saludables.

| Ruta local | Resultado |
| --- | --- |
| [Catálogo](http://localhost:3000/catalogo) | Búsqueda, filtros y acceso a las fichas y sus preguntas públicas |
| `http://localhost:3000/estado` | Versión, disponibilidad y acción de actualizar o reintentar |
| `http://localhost:8080/api/v1/status` | Estado de la API y versión del build |
| `http://localhost:8080/actuator/health/readiness` | Estado de preparación, incluida la conexión a PostgreSQL |
| [Categoría Tecnología](http://localhost:3000/categorias/e0020000-0000-4000-8000-000000000001) | Categoría raíz sembrada por Flyway |
| [Swagger UI](http://localhost:3000/swagger-ui/index.html) | Operaciones y respuestas de la API |

Para reproducir un fallo, ejecutá `docker compose stop postgres` y actualizá la pantalla. Readiness devuelve HTTP 503 y la interfaz ofrece **Reintentar**. Ejecutá `docker compose start postgres` y reintentá desde la pantalla. `docker compose down` detiene la aplicación y conserva el volumen.

## Contrato y diseño

El [contrato OpenAPI](docs/openapi.yaml) describe el diagnóstico, las categorías, el catálogo, las preguntas públicas, la autenticación y los errores REST. La [guía de diseño](docs/diseno.md) define los tokens y los estados de interfaz.

Desde la UI, abrí **Mi cuenta** para ingresar o crear una cuenta. El frontend y la API comparten origen por el proxy. Compose habilita `localhost` y `127.0.0.1` en el puerto web configurado. En otros entornos definí `AUTH_ALLOWED_ORIGINS` con los orígenes exactos separados por comas y usá HTTPS para la cookie segura. Si usás Vite con el backend de Compose, agregá el origen local de Vite a esa lista.

### Inicio con Google

1. En Google Cloud, creá un cliente OAuth de tipo **Aplicación web** y autorizá exactamente `http://localhost:3000/login/oauth2/code/google` como URI de redirección. Si la pantalla de consentimiento está en modo de prueba, agregá el usuario de prueba.
2. Guardá `GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET` en el `.env` local. Si cambiás el puerto o el host, actualizá también `GOOGLE_REDIRECT_URI`, el origen permitido y la URI registrada en Google. Ejecutá `docker compose up --build --wait`.
3. Abrí [Ingresar](http://localhost:3000/ingresar) desde `localhost` y elegí **Continuar con Google**. Después de aceptar, comprobá que se abra **Mi cuenta**. Cerrá sesión y repetí el ingreso para confirmar que volvés a la misma cuenta.
4. Para probar vinculación, registrá primero una cuenta de Zonda con el correo de otro usuario de prueba de Google. El acceso con Google te indicará que el correo ya existe. Ingresá con la contraseña y elegí **Seguridad de cuenta → Vincular Google**. Confirmá la contraseña y seleccioná ese mismo correo en Google. Al volver, comprobá que figure **Google vinculado**.

Google es opcional. Sin el cliente configurado, el ingreso con correo y contraseña sigue disponible. Las pruebas automatizadas usan un emisor OIDC de prueba y respuestas de interfaz simuladas.

#### Perfil, direcciones y vendedor

1. Ingresá a [Mi cuenta](http://localhost:3000/cuenta), guardá tu nombre de perfil y abrí **Mis direcciones**. Agregá una dirección de Argentina. Probá editarla y darla de baja.
2. Verificá tu correo desde Mi cuenta con Mailpit. En **Vender en Zonda**, elegí **Personal** o **Comercio**, completá el nombre y un identificador único y activá el perfil.
3. En **Panel vendedor**, abrí **Ver ficha pública**. La ficha se puede abrir fuera de sesión. Con otra cuenta verificada, intentá usar el mismo identificador para comprobar el conflicto.

## Verificación de correo y recuperación

1. Con Compose arriba y `MAIL_PROVIDER=mailpit`, abrí [Zonda Market](http://localhost:3000/registro) y registrá un correo ficticio como `comprador@example.test`.
2. En **Mi cuenta**, elegí **Enviar enlace de verificación**. Abrí el mensaje en [Mailpit](http://127.0.0.1:8025), seguí el enlace y elegí **Confirmar correo**. Volvé a **Mi cuenta** y comprobá que figure **Verificado**.
3. En **Ingresar**, elegí **Olvidé mi contraseña**, pedí el enlace y abrilo desde Mailpit. Guardá una nueva contraseña. Comprobá que la anterior falla, que las sesiones anteriores deben volver a ingresar y que reutilizar el enlace muestra la opción de pedir uno nuevo. El enlace de recuperación vence a los 30 minutos y el de verificación a las 12 horas.

Si cambiás el origen de navegación, configurá `MAIL_PUBLIC_ORIGIN` en `.env` con ese mismo origen. Para la demo con [Resend](https://resend.com/docs/api-reference/emails/send-email), configurá `MAIL_PROVIDER=resend`, `RESEND_API_KEY`, `MAIL_FROM` con un remitente autorizado y `MAIL_PUBLIC_ORIGIN` con el origen HTTPS. Mantené `.env` local y el backend detrás del proxy que fija la IP del cliente. El envío registra el ID lógico del evento, los intentos y el ID que devuelve el proveedor.

### Perfil, direcciones y vendedor

1. Ingresá a [Mi cuenta](http://localhost:3000/cuenta), guardá tu nombre de perfil y abrí **Mis direcciones**. Agregá una dirección de Argentina. Probá editarla y darla de baja.
2. Verificá tu correo desde Mi cuenta con Mailpit. En **Vender en Zonda**, elegí **Personal** o **Comercio**, completá el nombre y un identificador único y activá el perfil.
3. En **Panel vendedor**, abrí **Ver ficha pública**. La ficha se puede abrir fuera de sesión. Con otra cuenta verificada, intentá usar el mismo identificador para comprobar el conflicto.

### Imágenes y ficha pública

En **Panel vendedor → Mis publicaciones**, abrí un borrador con una variante, cargá una foto JPEG, PNG o WebP en **Imágenes** y elegí **Vista previa → Publicar ficha**. Abrí **Ver ficha pública** en una ventana privada. Desde el panel, archivá la ficha y recargá el enlace para comprobar que dejó de estar disponible.

Compose usa `MEDIA_PROVIDER=local` por defecto para desarrollo y pruebas. PostgreSQL conserva las imágenes junto al entorno local. Para usar Cloudinary, configurá `MEDIA_PROVIDER=cloudinary`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY` y `CLOUDINARY_API_SECRET` en el `.env` del servidor antes de levantar el stack. Usá un proveedor fijo por entorno. Cloudinary genera las miniaturas y recibe la invalidación del CDN al retirar una imagen. [Referencia de Cloudinary](https://cloudinary.com/documentation/image_upload_api_reference).

## Verificación

Desde `backend/`, con Java 21 y Docker, ejecutá `./mvnw verify` (Linux/macOS) o `.\mvnw.cmd verify` (Windows). Las pruebas de integración usan MockMvc y un PostgreSQL 16 de Testcontainers.

El PR ejecuta las pruebas del backend y el smoke con Compose y Chromium. Para repetir el smoke localmente, con el stack saludable y Node.js 24, ejecutá desde `frontend/`:

```sh
npm ci
npx playwright install chromium
npm run test:e2e
```

Usá un entorno local de prueba. El smoke detiene y recrea el contenedor de PostgreSQL, comprueba que el volumen conserva un dato temporal y lo elimina al final. Las capturas se guardan en `frontend/test-results/` y la salida registra el RSS de la JVM.

Para repetir la verificación de identidad, ejecutá `npm run test:e2e -- auth` desde `frontend/` con el stack arriba. Cubre alta, recarga en dos pestañas, cierre, ingreso con una contraseña incorrecta y reintento. `AuthContractIT` cubre expiración, reutilización y carrera de refresh, credenciales inválidas y protección por origen.

Para repetir E2-03, ejecutá `npm run test:e2e -- stock` desde `frontend/` con Compose, Mailpit y el proveedor local. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=StockContractIT"` en PowerShell. En **Mis publicaciones → Ajustar stock** o desde **Inventario**, sumá cinco unidades, descontá dos y revisá el historial. Intentá descontar más del saldo disponible para comprobar el rechazo.

Para repetir E2-04, ejecutá `npm run test:e2e -- discovery` con Compose, Mailpit y el proveedor local. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=DiscoveryContractIT"` en PowerShell. El contrato imprime el plan de búsqueda con 12.001 publicaciones ficticias y comprueba el uso del índice GIN con la configuración normal de PostgreSQL.

Para repetir E3-01, ejecutá `npm run test:e2e -- cart` desde `frontend/` con Compose, Mailpit y el proveedor local. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=CartContractIT"` en PowerShell. Para probarlo manualmente, ingresá con una cuenta compradora y agregá desde el catálogo dos variantes disponibles de vendedores distintos. Abrí **Carrito**, editá una cantidad con **Guardar** y recargá para confirmar que se conserva. Archivá una de las publicaciones como su vendedor y volvé a cargar el carrito. La línea aparece en **Productos no disponibles** y podés quitarla.

Para repetir E3-02, ejecutá `npm run test:e2e -- checkout` desde `frontend/` o `.\mvnw.cmd verify "-Dit.test=CheckoutContractIT"` desde `backend/`. En la tienda, usá una cuenta con correo verificado, una dirección vigente y productos con stock. Desde **Carrito → Continuar al checkout**, elegí la dirección, cotizá y confirmá la reserva. Recargá la pantalla y comprobá el detalle congelado y el contador. Para probar el rechazo, cambiá el precio de una variante como vendedor después de cotizar y volvé a confirmar como comprador.

Para repetir E3-03, usá los mismos comandos de checkout en un proyecto Compose aislado. Playwright detiene el backend, adelanta el vencimiento de su propia orden ficticia y verifica que el worker la libere al arrancar. En una prueba manual, dejá vencer una reserva de prueba y abrí **Mi cuenta → Mis órdenes**. Desde el detalle **Vencida**, usá **Volver a cotizar** para recuperar las mismas cantidades. El contrato Maven comprueba el límite a +14:59 y +15:00 con reloj controlado. Micrometer registra `zonda.checkout.expiry.released` y `zonda.checkout.expiry.failures`.

Para E3-04, confirmá una reserva con datos ficticios en el perfil `local` de Compose. En **Pago de prueba**, pulsá **Simular aprobación** para consumir el inventario reservado y descontar las líneas compradas, o **Simular rechazo** para liberar el inventario y conservar el carrito. Desde `frontend/` en un entorno aislado ejecutá `npm run test:e2e -- checkout`. Desde `backend/` ejecutá `.\mvnw.cmd verify "-Dit.test=CheckoutContractIT,DemoPaymentFixtureIT"`. El contrato comprueba el reintento, la carrera con expiración, la autorización y el 404 con una sesión válida en `demo`. Para arrancar demo en Compose, configurá `SPRING_PROFILES_ACTIVE=demo`.

Para E4-01, confirmá una reserva con datos ficticios y elegí **Card Payment** o **Checkout Pro** en **Medios de pago**. El proveedor local simula una espera agotada. Recargá y comprobá que sigue el mismo intento y que no se ofrece otro medio. Usá **Consultar estado** y **Marcar rechazado** para volver a elegir mientras siga vigente la reserva. En el segundo intento, **Marcar aprobado** confirma la orden y consume el stock reservado. El smoke móvil y el contrato de la sección anterior cubren este flujo.

### E4-02 · Card Payment Brick

1. En Mercado Pago Developers, entrá a **Tus integraciones** y creá o seleccioná una aplicación argentina de **Checkout API → Orders API**. Seleccioná **Prueba** y copiá la **Public Key** y el **Access Token** de ese entorno. Anotá también el **User ID** de la cuenta vendedora de prueba asociada. [Credenciales](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-orders/resources/credentials).
2. Cargá esos valores en el `.env` local como `MERCADO_PAGO_PUBLIC_KEY`, `MERCADO_PAGO_ACCESS_TOKEN` y `MERCADO_PAGO_TEST_COLLECTOR_ID`. Activá `MERCADO_PAGO_TEST_ENABLED=true` y ejecutá `docker compose up --build --wait`. El backend valida la identidad del colector de prueba antes de enviar un pago. Las credenciales privadas quedan en el backend.
3. En una reserva propia vigente elegí **Pagar con tarjeta**. Probá una tarjeta de crédito, una de débito y un rechazo con una reserva nueva para cada aprobación. Un intento incierto se consulta desde **Consultar estado** sin volver a enviar la tarjeta. [Compra de prueba con Orders API](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-orders/integration-test/cards).

Con `MERCADO_PAGO_TEST_ENABLED=false`, el perfil local conserva el simulador de E4-01. En un entorno aislado, ejecutá `npm run test:e2e -- card-payment` desde `frontend/` para probar carga, teclado, doble envío, recarga, consulta y rechazo con respuestas ficticias. Desde `backend/`, `.\mvnw.cmd verify "-Dit.test=CheckoutContractIT,CardPaymentContractIT,DemoPaymentFixtureIT"` prueba el contrato, el consumo de stock y la reconciliación, junto con el transporte ficticio del SDK Java.

### Checkout Pro con saldo de prueba

1. En una aplicación argentina de **Checkout Pro → Preferences API**, obtené el Access Token y el ID de su colector de prueba. Creá un comprador argentino de prueba con saldo ficticio suficiente. [Cuentas de prueba](https://www.mercadopago.com.ar/developers/es/docs/checkout-pro-preferences/test-accounts).
2. En `.env`, configurá `MERCADO_PAGO_PRO_TEST_ENABLED=true`, `MERCADO_PAGO_PRO_ACCESS_TOKEN`, `MERCADO_PAGO_PRO_TEST_COLLECTOR_ID` y `MERCADO_PAGO_PRO_PUBLIC_ORIGIN`. El origen debe ser HTTPS, sin barra final, y estar incluido en `AUTH_ALLOWED_ORIGINS`. Debe abrir la misma tienda desde el navegador. Iniciá Compose y entrá a la tienda desde ese origen.
3. Con una reserva vigente, elegí **Pagar con saldo de Mercado Pago**. Ingresá con el comprador de prueba, elegí dinero en cuenta y usá **Volver al sitio** si Mercado Pago no vuelve automáticamente. El resultado consulta el pago oficial y ofrece **Consultar estado** si sigue pendiente.

En un entorno local aislado, `npm run test:e2e -- checkout-pro` desde `frontend/` prueba con respuestas ficticias la salida, el retorno manipulado y la consulta posterior. Desde `backend/`, `.\mvnw.cmd verify "-Dit.test=CheckoutProContractIT"` prueba concurrencia, permisos, recuperación de la preferencia y un único consumo de stock.

### Webhooks y conciliación de Mercado Pago

1. En las aplicaciones de prueba de Mercado Pago, configurá como URL de notificación `https://tu-origen/api/v1/webhooks/mercadopago` en un entorno de prueba accesible por HTTPS. Seleccioná **Order (Mercado Pago)** para Card Payment Brick vía Orders API y **Pagos** para Checkout Pro vía preferencias. [Notificaciones de Orders API](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-orders/notifications) · [Notificaciones de Checkout Pro](https://www.mercadopago.com.ar/developers/es/docs/checkout-pro-preferences/payment-notifications)
2. En `.env` completá `MERCADO_PAGO_CARD_WEBHOOK_SECRET` y/o `MERCADO_PAGO_PRO_WEBHOOK_SECRET` con la **firma secreta de Webhooks** de cada aplicación. El adaptador correspondiente necesita también las credenciales de prueba descritas arriba. Recreá el entorno con `docker compose up --build --wait`.
3. Hacé una compra de prueba y verificá que la orden pase a **Pagada** al abrir la reserva o **Mis órdenes**. La notificación responde 200 tras guardar su entrega. El cobro se confirma al verificar la consulta al proveedor. También podés completar un pago con las notificaciones temporalmente deshabilitadas para observar que el barrido recupera el resultado.

Para reproducir con datos ficticios, ejecutá `.\mvnw.cmd verify "-Dit.test=PaymentWebhookContractIT"` desde `backend/` y `npm run test:e2e -- payment-reconciliation` desde `frontend/`. El contrato comprueba diez reenvíos, firma inválida, un estado fuera de orden, reintentos y la recuperación sin entrega.

Para investigar un fallo, buscá el `traceId` en los logs del backend y consultá el inbox. Una fila con `finished_at` y `failure_code` indica un fallo terminal. Una fila sin `finished_at` puede tener un próximo reintento.

```sql
SELECT trace_id, topic, resource_id, attempts, next_attempt_at, finished_at, failure_code
FROM zonda_market.payment_webhook_inbox
WHERE failure_code IS NOT NULL
ORDER BY received_at DESC
LIMIT 50;
```

### Devolución de un cobro tardío o duplicado

Cuando Mercado Pago confirma un cobro sin una reserva válida o un cargo adicional, Zonda reserva una devolución por pago. El worker consulta el historial antes de enviar o reintentar. Los reembolsos de tarjeta usan Orders API y los de saldo usan Payments API. [Reembolsos de tarjeta](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-orders/payment-management/refunds-cancellations) · [Reembolsos de Checkout Pro](https://www.mercadopago.com.ar/developers/en/docs/checkout-pro-preferences/additional-settings/refunds-and-cancellations)

En **Mis órdenes**, abrí el detalle para consultar el motivo, el importe y el estado de la devolución. Si la orden figura **Pagada**, las subórdenes siguen su curso con el cobro que confirmó ese pedido. El botón de pago se deshabilita mientras Zonda procesa el primer envío.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=PaymentWebhookContractIT"` para probar un pago tardío tras la reventa del SKU, un pago extra y la recuperación tras una respuesta perdida. Desde `frontend/`, ejecutá `npm run test:e2e -- card-payment checkout-pro payment-reconciliation` con Compose activo para probar el bloqueo del envío y los estados de devolución en móvil.

Operaciones puede consultar devoluciones en revisión y fallos transitorios por `trace_id`.

```sql
SELECT id, order_id, status, trace_id, attempts, next_attempt_at, failure_code
FROM zonda_market.payment_compensation
WHERE status = 'EN_REVISION' OR failure_code IS NOT NULL
ORDER BY updated_at DESC
LIMIT 50;
```

### E4-06 · Checkout con dos vendedores

1. Prepará dos cuentas vendedoras ficticias, cada una con una publicación activa y al menos dos unidades disponibles. Usá una tercera cuenta como comprador, verificá su correo y agregale una dirección.
2. Agregá una unidad de cada vendedor al carrito. Confirmá una reserva y pagá con tarjeta de prueba. Abrí **Seguir mi compra** y comprobá que la orden está **Pagada**, muestra un total y que cada vendedor tiene una suborden en preparación con su propia referencia.
3. Con el mismo comprador y los mismos dos vendedores, armá otra reserva. Pagá con **Pagar con saldo de Mercado Pago** desde el origen HTTPS de prueba. Si el retorno sigue pendiente, usá **Consultar estado**. Al acreditarse, revisá la segunda orden y las dos subórdenes. Usá las credenciales de cada aplicación y la configuración de Webhooks detalladas arriba. La cuenta compradora de Mercado Pago se usa en el checkout externo.
4. Creá una tercera reserva, dejala vencer y volvé a abrirla. Las subórdenes deben figurar canceladas y los productos deben poder cotizarse de nuevo. En otra reserva, probá un rechazo y confirmá que la reserva vigente sigue disponible.

El smoke automático del backend usa un comprador y dos vendedores reales de la base de prueba, webhooks firmados y notificaciones repetidas. Los contratos de los adaptadores usan respuestas ficticias versionadas en `backend/src/test/resources/mercadopago/`. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=PaymentWebhookContractIT"` para reproducir ambos medios con fixtures. Desde `frontend/`, `npm run test:e2e -- checkout.spec.ts checkout-pro.spec.ts` cubre el resumen móvil, el teclado, el retorno pendiente y la expiración.

Para la certificación externa, anotá por cada compra el método, ID de la orden Zonda, ID del pago de Mercado Pago, correlación `trace_id` y los dos estados de suborden. Capturá el resumen sin credenciales ni datos personales. Compará los asientos con el total de la orden y verificá que ambos corresponden al cobro ganador. En el retorno pendiente, guardá también una captura anterior a la acreditación. Para la consulta, configurá `order_id` como variable de `psql`.

```sql
SELECT paid.order_id, paid.payment_method, paid.provider_payment_id AS payment_id,
       paid.trace_id, sub.id AS sub_order_id, sub.status, entry.merchandise,
       entry.shipping, entry.commission, entry.seller_net, entry.currency
FROM zonda_market.checkout_payment_outcome paid
JOIN zonda_market.checkout_sub_order sub ON sub.order_id = paid.order_id
JOIN zonda_market.checkout_sub_order_settlement entry ON entry.sub_order_id = sub.id
WHERE paid.order_id = :'order_id'::uuid
ORDER BY sub.id;

SELECT method, verified_day, approved, rejected, pending, avg_terminal_seconds
FROM zonda_market.payment_reconciliation_metrics
ORDER BY verified_day DESC, method;
```

La vista agrupa por método y día UTC de la última transición verificada de cada pago externo. La latencia terminal mide desde la creación del intento hasta la aprobación o el rechazo verificado e incluye el tiempo en el checkout y en la conciliación. Los reenvíos con el mismo estado conservan la medición.

### E5-01 · Despacho y entrega

1. En [la tienda local](http://localhost:3000), comprá en un solo pedido un producto de cada uno de dos vendedores con cuentas ficticias. En el perfil `local`, usá **Pago de prueba → Simular aprobación** si el simulador está habilitado.
2. Ingresá como uno de los vendedores y abrí **Panel vendedor → Pedidos recibidos**. En una suborden **En preparación**, elegí **Registrar despacho → Andreani** y usá el código ficticio `360000000000000`. Confirmá y filtrá por **Despachada** para ver el código y la fecha.
3. Volvé como comprador a **Mis órdenes** y abrí la orden. Confirmá la recepción de la suborden despachada. Al recargar, esa suborden debe mostrar **Entregada** con las dos fechas, y la otra debe seguir **En preparación**.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=CheckoutContractIT"` para verificar permisos, estados, reintentos simultáneos y rollback del evento. Desde `frontend/`, ejecutá `npm run test:e2e -- fulfillment` con un Compose de prueba en perfil `local`, Mailpit y pagos simulados. El smoke crea cuentas ficticias y verifica el flujo a 390 px, foco, teclado y recuperación tras perder la respuesta del despacho.

### E5-02 · Notificaciones de compra y envío

1. Aprobá el pago de una compra ficticia nueva con dos vendedores siguiendo el flujo anterior. En la campana de cada cuenta aparece el aviso **Pago aprobado**.
2. Despachá una suborden. Como comprador, abrí la campana, marcá **Pedido despachado** como leída y usá **Ver compra** para ir a esa suborden. Confirmá la recepción.
3. Como vendedor de esa suborden, abrí **Entrega confirmada**. En [Mailpit](http://localhost:8025) buscá los avisos por el correo ficticio de cada cuenta. La bandeja puede demorar hasta diez segundos en refrescar el contador con la pestaña abierta.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=NotificationContractIT"` para probar permisos, rollback, reenvío y recuperación del correo. Desde `frontend/`, ejecutá `npm run test:e2e -- notifications.spec.ts` contra un Compose de prueba con Mailpit. El smoke cubre notificaciones por destinatario, foco, contador y reintento en móvil.

### E5-03 · Reseñas y reputación

1. Como comprador, abrí **Mis órdenes** y entrá a una suborden **Entregada**. Elegí las estrellas con flechas o pulsando la opción, agregá un comentario si querés y elegí **Publicar reseña**.
2. Recargá la orden y comprobá que la suborden muestra **Reseña publicada**. Las subórdenes que todavía están en preparación o despachadas siguen mostrando su estado.
3. Abrí la publicación comprada y consultá el bloque **Vendedor**. El enlace del vendedor lleva al perfil público con el mismo promedio y cantidad, y la reseña muestra un alias.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ReviewContractIT"` para probar permisos, duplicados simultáneos, paginación, promedio y exclusión de reseñas ocultas. Desde `frontend/`, ejecutá `npm run test:e2e -- reviews.spec.ts` contra un Compose local de prueba con Mailpit. El smoke cubre teclado a 390 px, pérdida de respuesta, reintento y texto plano en el perfil público.

### E5-04 · Ventas y neto estimado

1. Como vendedor, abrí **Panel vendedor → Ventas y neto estimado**. El período inicial incluye los últimos 30 días en horario de Buenos Aires. Elegí **Desde** y **Hasta** para consultar hasta 90 días inclusivos.
2. Compará las tarjetas con **Actividad diaria → Ver importes por día** y abrí una suborden en **Detalle por suborden**. El bruto incluye mercadería y envío. El neto resta comisiones y devoluciones confirmadas y suma la comisión revertida.
3. Elegí un día anterior a tus ventas para comprobar el estado vacío. Probá un rango invertido para ver la validación.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=SellerMetricsContractIT,PaymentWebhookContractIT"` para repetir una devolución postventa de prueba, los límites de fecha, la privacidad y la compensación de un cobro adicional. Desde `frontend/`, ejecutá `npm run test:e2e -- seller-sales.spec.ts` con un Compose de prueba en perfil `local` y Mailpit. El smoke de la etapa agrega `fulfillment.spec.ts notifications.spec.ts reviews.spec.ts`.

### E6-01 · Moderación

En un Compose local de prueba, registrá una cuenta ficticia dedicada a administración. Desde el repositorio, abrí `docker compose exec postgres psql -U zonda -d zonda_market` con el usuario y la base configurados para ese entorno. Confirmá el correo exacto de la cuenta de prueba antes de asignar el rol:

```sql
UPDATE zonda_market.user_account
SET role = 'ADMIN', version = version + 1, updated_at = now()
WHERE email = 'admin-moderacion@example.test' AND role = 'USER' AND NOT deleted AND NOT suspended
RETURNING id, email, role;
```

Volvé a ingresar y abrí **Mi cuenta → Moderación**. En **Publicaciones** buscá un producto, ocultalo con un motivo y verificá su salida del catálogo. En **Cuentas**, suspendé al vendedor de prueba y comprobá que se cierra su sesión. Si tenía una compra pendiente, abrí **Ver detalle** para ver la auditoría y el seguimiento. El comprador conserva su orden en **Mis órdenes**.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ModerationContractIT"`. Desde `frontend/`, ejecutá `npm run test:e2e -- moderation.spec.ts` con un Compose aislado en perfil `local`, Mailpit y pagos simulados. El smoke crea su propio administrador ficticio y prueba el panel a 390 px con teclado.

Para probar E6-02, ingresá con una cuenta `ADMIN` y abrí **Mi cuenta → Moderación → Categorías**. Creá una categoría raíz de prueba y una subcategoría con un slug nuevo, 1250 puntos básicos (12,5 %) y un atributo con un valor por línea. Revisá la vista previa y guardá. Intentá archivar la raíz mientras tiene una hija. Después mové la hija al nivel raíz, ajustá su tasa y archivala desde el árbol. Podés consultar el registro en **Archivadas**.

Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=CategoryAdminContractIT"` para comprobar permisos, movimientos simultáneos, referencias, vigencias, redondeo y el neto congelado. Desde `frontend/`, ejecutá `npm run test:e2e -- category-admin.spec.ts` con un Compose aislado para reproducir la navegación a 390 px, el conflicto de slug y la recuperación con teclado.

Para repetir E2-02, ejecutá `npm run test:e2e -- publication` desde `frontend/` con Compose, Mailpit y el proveedor local. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ListingPublicationIT"` en PowerShell. La prueba `CloudinaryListingMediaTest` verifica el cliente HTTP con respuestas de prueba.

Para repetir E2-01, ejecutá `npm run test:e2e -- listings` desde `frontend/` con Compose y Mailpit arriba. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ListingContractIT"` en PowerShell. Para probar el flujo manualmente, activá un perfil de vendedor con correo verificado y abrí **Panel vendedor → Mis publicaciones → Crear publicación**. Elegí **Indumentaria**, guardá el borrador y agregá dos combinaciones de **Color** y **Talle** con códigos distintos. Reabrí la publicación y editá una variante. Intentá repetir una combinación para comprobar el error y cambiarla sin perder el resto de los datos.

Para repetir E1-04, ejecutá `npm run test:e2e -- profile` desde `frontend/` con Compose y Mailpit arriba. Desde `backend/`, ejecutá `.\\mvnw.cmd verify "-Dit.test=ProfileContractIT"` en PowerShell. El contrato prueba permisos, baja lógica, activación concurrente, renovación, conflicto de slug y campos públicos.

Para repetir E1-03, ejecutá `npm run test:e2e -- identity-email` desde `frontend/` con Compose y Mailpit arriba. Si usás puertos alternativos, configurá `ZONDA_SMOKE_URL` y `ZONDA_MAILPIT_URL` para Playwright. Desde `backend/`, ejecutá `.\\mvnw.cmd verify "-Dit.test=IdentityEmailContractIT"` en PowerShell. El contrato cubre expiración, consumo concurrente, revocación, límites y reintento con el mismo evento.

Para repetir E1-02, ejecutá `npm run test:e2e -- google-auth` desde `frontend/` con Compose arriba. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=GoogleAuthContractIT"` en PowerShell. El contrato prueba PKCE, issuer, audiencia, nonce, alta, vinculación, expiración y consumo único con PostgreSQL 16.
