# Zonda Market

Marketplace C2C/B2C. El [roadmap](docs/roadmap.md) describe las etapas y las entregas.

## Arranque local

Requisitos: Docker con Compose v2 y los puertos locales 3000 y 8080 disponibles.

1. Copiá `.env.example` a `.env` y completá `POSTGRES_PASSWORD` con una contraseña de desarrollo. En PowerShell podés copiarlo con `Copy-Item .env.example .env`.
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

El [contrato OpenAPI](docs/openapi.yaml) describe el diagnóstico, la lectura pública de categorías y los errores REST. La [guía de diseño](docs/diseno.md) define los tokens y los estados de interfaz para las siguientes tarjetas.

## Verificación de la Etapa 0

Desde `backend/`, con Java 21 y Docker, ejecutá `./mvnw verify` (Linux/macOS) o `.\mvnw.cmd verify` (Windows). Las pruebas de integración usan MockMvc y un PostgreSQL 16 de Testcontainers.

El PR ejecuta las pruebas del backend y el smoke con Compose y Chromium. Para repetir el smoke localmente, con el stack saludable y Node.js 24, ejecutá desde `frontend/`:

```sh
npm ci
npx playwright install chromium
npm run test:e2e
```

Usá un entorno local de prueba. El smoke detiene y recrea el contenedor de PostgreSQL, comprueba que el volumen conserva un dato temporal y lo elimina al final. Las capturas se guardan en `frontend/test-results/` y la salida registra el RSS de la JVM.
