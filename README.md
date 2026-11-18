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

Para repetir E1-02, ejecutá `npm run test:e2e -- google-auth` desde `frontend/` con Compose arriba. Desde `backend/`, ejecutá `.\mvnw.cmd verify "-Dit.test=GoogleAuthContractIT"` en PowerShell. El contrato prueba PKCE, issuer, audiencia, nonce, alta, vinculación, expiración y consumo único con PostgreSQL 16.
