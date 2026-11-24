# Zonda Market

Zonda Market es un marketplace argentino donde personas y comercios publican productos y los compradores pueden hacer un pedido a varios vendedores en una sola compra. Usa React, TypeScript, Spring Boot y PostgreSQL.

En la [demo pública](https://zonda-market-demo.pages.dev/) podés explorar el catálogo, abrir fichas y agregar productos al carrito con datos ficticios.

## Ejecutar en local

Necesitás Docker con Compose v2 y los puertos `3000`, `8080` y `8025` libres. Cloná la versión ejecutable con los datos de demostración.

```sh
git clone --branch develop https://github.com/valentinemanuel/Zonda-Market.git
cd Zonda-Market
```

1. Creá `.env` a partir de `.env.example` con `cp .env.example .env` en Bash o `Copy-Item .env.example .env` en PowerShell.
2. Elegí una contraseña local para `POSTGRES_PASSWORD`. Generá `AUTH_JWT_KEY` con `openssl rand -base64 32` o, en PowerShell 7, con `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.
3. Activá `DEMO_SEED_ENABLED=true` y definí `DEMO_SEED_PASSWORD` y `DEMO_SEED_ADMIN_PASSWORD` con claves distintas de al menos 12 caracteres. Usá `SPRING_PROFILES_ACTIVE=local` para habilitar el simulador de pagos.
4. Ejecutá `docker compose up --build --wait` y abrí el [catálogo local](http://localhost:3000/catalogo).

Para detener el entorno y conservar los datos, ejecutá `docker compose down`.

## Probar una compra

1. Ingresá como `comprador@demo.zonda.test` con la clave que configuraste en `DEMO_SEED_PASSWORD`.
2. Abrí una ficha con stock, elegí una variante y la cantidad. Pulsá **Comprar ahora**, confirmá el envío y usá **Simular aprobación**.
3. Abrí **Mis órdenes** para revisar la compra. Podés ver los correos locales en [Mailpit](http://localhost:8025) y el estado de los servicios en [Disponibilidad de Zonda Market](http://localhost:3000/estado).
