# Zonda Market

Zonda Market es un marketplace de portfolio donde personas y comercios pueden publicar productos y los compradores pueden hacer un pedido a varios vendedores en una sola compra. Está hecho con React, TypeScript, Spring Boot y PostgreSQL.

[**Abrir la demo pública**](https://zonda-market-demo.pages.dev/) · Probá el catálogo, las fichas de producto y el carrito con datos ficticios.

## Arranque local

Necesitás Docker con Compose v2 y los puertos `3000`, `8080` y `8025` libres. Si vas a clonar el proyecto, usá `develop` para probar la versión más reciente.

```sh
git clone --branch develop https://github.com/valentinemanuel/Zonda-Market.git
cd Zonda-Market
cp .env.example .env
```

1. En `.env`, elegí un `POSTGRES_PASSWORD` y generá `AUTH_JWT_KEY` con `openssl rand -base64 32`. En PowerShell 7 también podés usar `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.
2. Para cargar productos y cuentas de prueba, activá `DEMO_SEED_ENABLED=true` y definí `DEMO_SEED_PASSWORD` y `DEMO_SEED_ADMIN_PASSWORD` con claves distintas de al menos 12 caracteres. Dejá `SPRING_PROFILES_ACTIVE=local` para usar el simulador de pagos.
3. Ejecutá `docker compose up --build --wait` y abrí el [catálogo local](http://localhost:3000/catalogo).
4. Ingresá como `comprador@demo.zonda.test` con `DEMO_SEED_PASSWORD`. Abrí una ficha con stock, elegí una variante y la cantidad, y pulsá **Comprar ahora**. Completá el checkout con **Simular aprobación** y revisá el resultado en **Mis órdenes**. Los correos se ven en [Mailpit](http://localhost:8025).

Para detener el entorno y conservar los datos, ejecutá `docker compose down`.

[Guía de pruebas](docs/pruebas.md) · [Roadmap](docs/roadmap.md) · [Contrato OpenAPI](docs/openapi.yaml)
