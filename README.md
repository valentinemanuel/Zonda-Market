# Zonda Market

Marketplace C2C/B2C. El [roadmap](docs/roadmap.md) describe las etapas y las entregas.

## Arranque local

Requisitos: Docker con Compose v2 y los puertos locales 3000 y 8080 disponibles.

1. Copiá `.env.example` a `.env` y completá `POSTGRES_PASSWORD` con una contraseña de desarrollo. Generá `AUTH_JWT_KEY` con `openssl rand -base64 32` o, en PowerShell 7, con `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`. Guardá la clave en `.env`.
2. Desde la raíz, ejecutá `docker compose up --build --wait`.
3. Abrí [Estado del sistema](http://localhost:3000/estado). También podés comprobar que `docker compose ps` liste los servicios como saludables.

| Ruta local | Resultado |
| --- | --- |
| `http://localhost:3000/estado` | Versión, disponibilidad y acción de actualizar o reintentar |
| `http://localhost:8080/api/v1/status` | Estado de la API y versión del build |
| `http://localhost:8080/actuator/health/readiness` | Estado de preparación, incluida la conexión a PostgreSQL |
| [Categoría Tecnología](http://localhost:3000/categorias/e0020000-0000-4000-8000-000000000001) | Categoría raíz sembrada por Flyway |
| [Swagger UI](http://localhost:3000/swagger-ui/index.html) | Operaciones y respuestas de la API |

Para reproducir un fallo, ejecutá `docker compose stop postgres` y actualizá la pantalla. Readiness devuelve HTTP 503 y la interfaz ofrece **Reintentar**. Ejecutá `docker compose start postgres` y reintentá desde la pantalla. `docker compose down` detiene la aplicación y conserva el volumen.

## Contrato y diseño

El [contrato OpenAPI](docs/openapi.yaml) describe el diagnóstico, la lectura pública de categorías, la autenticación y los errores REST. La [guía de diseño](docs/diseno.md) define los tokens y los estados de interfaz.

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

Para repetir E2-02, ejecutá `npm run test:e2e -- publication` desde `frontend/` con Compose, Mailpit y el proveedor local. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ListingPublicationIT"` en PowerShell. La prueba `CloudinaryListingMediaTest` verifica el cliente HTTP con respuestas de prueba.

Para repetir E2-01, ejecutá `npm run test:e2e -- listings` desde `frontend/` con Compose y Mailpit arriba. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=ListingContractIT"` en PowerShell. Para probar el flujo manualmente, activá un perfil de vendedor con correo verificado y abrí **Panel vendedor → Mis publicaciones → Crear publicación**. Elegí **Indumentaria**, guardá el borrador y agregá dos combinaciones de **Color** y **Talle** con códigos distintos. Reabrí la publicación y editá una variante. Intentá repetir una combinación para comprobar el error y cambiarla sin perder el resto de los datos.

Para repetir E1-04, ejecutá `npm run test:e2e -- profile` desde `frontend/` con Compose y Mailpit arriba. Desde `backend/`, ejecutá `.\\mvnw.cmd verify "-Dit.test=ProfileContractIT"` en PowerShell. El contrato prueba permisos, baja lógica, activación concurrente, renovación, conflicto de slug y campos públicos.

Para repetir E1-03, ejecutá `npm run test:e2e -- identity-email` desde `frontend/` con Compose y Mailpit arriba. Si usás puertos alternativos, configurá `ZONDA_SMOKE_URL` y `ZONDA_MAILPIT_URL` para Playwright. Desde `backend/`, ejecutá `.\\mvnw.cmd verify "-Dit.test=IdentityEmailContractIT"` en PowerShell. El contrato cubre expiración, consumo concurrente, revocación, límites y reintento con el mismo evento.

Para repetir E1-02, ejecutá `npm run test:e2e -- google-auth` desde `frontend/` con Compose arriba. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=GoogleAuthContractIT"` en PowerShell. El contrato prueba PKCE, issuer, audiencia, nonce, alta, vinculación, expiración y consumo único con PostgreSQL 16.
