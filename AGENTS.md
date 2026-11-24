# Zonda-Market

- Consultá el issue activo, las [reglas de dominio](docs/dominio.md) que afecte el cambio y el roadmap local cuando esté disponible. Si cambia una regla compartida, actualizá su documentación junto con el código.
- El plan prevé Java 21, Spring Boot y PostgreSQL 16 con Flyway para el backend, y React, TypeScript, Vite, Tailwind y shadcn/ui para el frontend. Conservá los límites entre controlador, servicio y persistencia y ubicá las integraciones externas detrás de interfaces.
- En cambios de interfaz, consultá la [guía de diseño](docs/diseno.md). En el issue indicá qué bloques se reutilizan, los estados que se muestran y cómo se va a revisar la pantalla en móvil y con teclado.
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

## Entornos locales por tarjeta

- Usá un proyecto de Docker Compose separado, con puertos libres, un volumen propio y datos ficticios para las pruebas aisladas. Guardá la evidencia antes de retirar el entorno.
- Con la tarjeta integrada y los checks en verde, sincronizá el `develop` local cuando el árbol de trabajo esté limpio. Desde el repositorio principal, actualizá el proyecto `zonda-market` con `docker compose up --build --wait`, reutilizá el volumen `zonda-market_postgres-data` y verificá el ingreso y la funcionalidad entregada en el puerto habitual `3000`.
- Cuando el entorno principal esté verificado, retirá los contenedores y la red del proyecto de prueba con `docker compose -p <proyecto-de-prueba> down`. Eliminá sus imágenes específicas cuando ya no se utilicen y agregá `-v` solo si confirmaste que el volumen contiene datos de prueba descartables. Conservá el volumen principal y los recursos de otros proyectos.
- Dejá corriendo el proyecto principal para la prueba del usuario. Si una tarjeta requiere conservar un entorno aislado temporalmente, registrá el motivo y cuándo se retirará.
