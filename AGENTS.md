# Zonda-Market

- Consultá [el roadmap](docs/roadmap.md), el issue activo y las [reglas de dominio](docs/dominio.md) que afecte el cambio. El [plan original](docs/Plan-de-Trabajo-Zonda-MARKET-Marketplace-C2C-B2C.pdf) conserva el detalle de las tarjetas. Si cambia una regla compartida, actualizá su documentación junto con el código.
- El plan prevé Java 21, Spring Boot y PostgreSQL 16 con Flyway para el backend, y React, TypeScript, Vite, Tailwind y shadcn/ui para el frontend. Conservá los límites entre controlador, servicio y persistencia y ubicá las integraciones externas detrás de interfaces.
- Usá la cuenta de GitHub `valentinemanuel` para las operaciones remotas. Antes de publicar, verificá `gh api user --jq .login` y la identidad Git local. Los commits deben quedar atribuidos a esa misma cuenta.

## Entrega por issue

1. Tomá una tarjeta cuyas dependencias estén integradas. Mantené un issue de implementación activo a la vez. Si hay un impedimento, registralo en el issue.
2. Confirmá qué comportamiento se entrega, el contrato afectado, las restricciones y los criterios que se van a probar. Usá la [plantilla](.github/ISSUE_TEMPLATE/entrega-vertical.yml).
3. Creá `feature/EX-XX-descripcion` desde `develop` y abrí el PR hacia `develop`. Referenciá el issue con `#N`. Incluí los comandos ejecutados, resultados y evidencia reproducible del caso exitoso y del fallo relevante.
4. Con la revisión y los checks en verde, integrá por squash en `develop` y cerrá manualmente el issue con un enlace al PR. Reuní las tarjetas de una etapa en un PR de `develop` a `main`, repetí el smoke y, cuando se cumplan los criterios de la etapa, publicá el tag anotado definido en el roadmap. Reintegrá en `develop` cualquier corrección hecha para el release.

## Verificación

- Usá los comandos documentados por el código y CI a medida que se incorporen. Informá el resultado real y qué criterios quedan pendientes de verificar.
- Para permisos, ejercitá el caso autorizado y el acceso ajeno por API. Para inventario y dinero, probá concurrencia, idempotencia y recuperación cuando el cambio afecte esas reglas.
- Demostrá estados de carga y error, y revisá teclado cuando cambie una pantalla. Usá datos ficticios y evidencia sin secretos.
- Una revisión independiente de solo lectura puede ayudar en identidad, concurrencia, pagos y reembolsos. El agente principal integra los hallazgos y ejecuta la verificación final.
