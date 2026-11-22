# Roadmap de Zonda-Market

Marketplace C2C/B2C. La demo v1.0 recorre una compra con dos vendedores y un único cobro de prueba, despachos independientes, reseña de una entrega y mediación con reembolso.

Cada tarjeta se trabaja en un issue. El issue registra su estado, criterios y evidencia. Las columnas de dependencias indican qué tarjetas deben estar integradas antes de comenzar. Los tags se publican al completar el smoke de su etapa.

El [plan original](Plan-de-Trabajo-Zonda-MARKET-Marketplace-C2C-B2C.pdf) contiene el detalle de las tarjetas y los guiones de aceptación. El [modelo UML](uml.uxf) acompaña las [reglas de dominio](dominio.md).

## Etapa 0 · Cimientos · v0.1

| ID | Entrega | Depende de |
| --- | --- | --- |
| E0-01 | Arranque reproducible y diagnóstico | — |
| E0-02 | Contrato REST y categoría de lectura | E0-01 |

Smoke de etapa: levantar un entorno limpio, comprobar diagnóstico y readiness, y consultar una categoría semilla mediante la UI y Swagger.

## Etapa 1 · Identidad · v0.2

| ID | Entrega | Depende de |
| --- | --- | --- |
| E1-01 | Registro, login y sesión rotativa | E0-02 |
| E1-02 | Inicio con Google y vinculación de identidad | E1-01 |
| E1-03 | Verificación de correo y recuperación de acceso | E1-01 |
| E1-04 | Perfil, direcciones y activación de vendedor | E1-01, E1-03 |

Smoke de etapa: registrar una cuenta, comprobar sesión y Google, verificar correo, recuperar acceso, administrar una dirección y activar un perfil vendedor. Consultar la ficha pública y comprobar los permisos de direcciones y panel.

## Etapa 2 · Catálogo · v0.3

| ID | Entrega | Depende de |
| --- | --- | --- |
| E2-01 | Crear publicación y variantes editables | E0-02, E1-04 |
| E2-02 | Adjuntar imágenes y publicar ficha visible | E2-01 |
| E2-03 | Ajustar stock disponible con trazabilidad | E2-01, E2-02 |
| E2-04 | Descubrir publicaciones y conversar en público | E2-02, E1-01 |

Smoke de etapa: crear una ficha con dos variantes y una foto, publicarla y ajustar el stock de una variante. Encontrarla por un término flexionado y combinar filtros sobre una única variante. Preguntar con una cuenta distinta, responder desde el panel del titular y comprobar como visitante que el texto es visible. Archivar la ficha y comprobar que desaparece del catálogo y que el hilo deja de estar disponible.

## Etapa 3 · Carrito, subórdenes y reserva · v0.4

| ID | Entrega | Depende de |
| --- | --- | --- |
| E3-01 | Carrito persistente agrupado por vendedor | E1-01, E2-02, E2-03 |
| E3-02 | Confirmar checkout y congelar subórdenes | E3-01, E1-04, E1-03 |
| E3-03 | Liberar reservas y conservar el carrito | E3-02 |
| E3-04 | Demostrar consumo atómico de reserva en local | E3-03 |

Smoke de etapa: cotizar un carrito de dos vendedores, reservar ambos SKU y simular un pago. Reintentar la aprobación y comprobar una sola transición de inventario. Repetir con rechazo y con una orden vencida, y verificar que el carrito siga disponible para recotizar. Verificar el arranque demo y el 404 del simulador con una sesión válida.

## Etapa 4 · Pagos y webhooks · v0.5

| ID | Entrega | Depende de |
| --- | --- | --- |
| E4-01 | Gestionar intentos, estados y controles de elegibilidad | E3-04 |
| E4-02 | Pagar con crédito y débito con Card Payment Brick | E4-01 |
| E4-03 | Pagar con saldo mediante Checkout Pro | E4-01 |
| E4-04 | Validar webhooks y aplicar efectos idempotentes | E4-01, E4-02, E4-03 |
| E4-05 | Compensar un cobro tardío o duplicado | E4-04 |
| E4-06 | Certificar checkout dual con dos vendedores | E4-05 |

Card Payment Brick usa Orders API. La conciliación y las notificaciones de tarjetas deben consultar la orden de Mercado Pago y su transacción.

## Etapa 5 · Envíos, reputación y métricas · v0.6

| ID | Entrega | Depende de |
| --- | --- | --- |
| E5-01 | Despachar suborden y registrar entrega | E4-06 |
| E5-02 | Notificar hitos de compra y envío | E1-03, E4-06, E5-01 |
| E5-03 | Calificar una entrega y mostrar reputación | E5-01 |
| E5-04 | Visualizar ventas y neto estimado | E5-01, E4-06 |

## Etapa 6 · Administración y disputas · v0.7

| ID | Entrega | Depende de |
| --- | --- | --- |
| E6-01 | Moderar publicaciones y suspender cuentas | E2-04, E5-03 |
| E6-02 | Administrar árbol de categorías y comisiones | E6-01 |
| E6-03 | Abrir y tramitar un reclamo privado | E5-02, E6-01 |
| E6-04 | Resolver una disputa y conciliar su devolución | E4-05, E6-03 |
| E6-05 | Consultar auditoría y recuperar incidencias | E6-01, E6-04 |
| E6-06 | Medir operación global y exportar resultados | E5-04, E6-04, E6-05 |

## Etapa 7 · Regresión, CI/CD y demo · v1.0

| ID | Entrega | Depende de |
| --- | --- | --- |
| E7-01 | Automatizar regresión end-to-end | E6-06 |
| E7-02 | Sembrar datos realistas y preparar la demostración | E7-01 |
| E7-03 | Construir imágenes y CI/CD con gates | E7-01, E7-02 |
| E7-04 | Desplegar demo $0 y verificar restauración | E7-03 |
