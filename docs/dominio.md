# Reglas de dominio

Referencia inicial de las invariantes de la v1.0, basada en las secciones 2 y 3 del [plan original](Plan-de-Trabajo-Zonda-MARKET-Marketplace-C2C-B2C.pdf). El [modelo UML](uml.uxf) muestra las entidades y sus relaciones.

## Identidad y catálogo

- Un visitante puede consultar catálogo, reputación y preguntas públicas. Un usuario registrado puede comprar. Un vendedor conserva los permisos de comprador y gestiona sus propias publicaciones, variantes y subórdenes. El rol `ADMIN` se asigna fuera del autorregistro.
- Cada operación privada comprueba rol y relación con el recurso. Activar un perfil `PERSONAL` o `COMERCIO` y confirmar una compra requiere correo verificado.
- El autorregistro asigna `USER` y conserva el correo sin verificar. La unicidad se aplica al correo sin espacios exteriores y en minúsculas.
- Los enlaces de verificación caducan a las 12 horas y los de recuperación a los 30 minutos. Cada enlace pertenece a una sola cuenta y finalidad, se persiste su hash y se consume una vez. Completar el flujo invalida los demás enlaces de la misma finalidad. Recuperar una cuenta con contraseña revoca todas sus sesiones sin cambiar el estado de verificación del correo. Las cuentas que solo usan Google recuperan el acceso mediante Google.
- Las solicitudes de enlace devuelven la misma aceptación para cualquier correo válido, incluso cuando se alcanza el límite. El presupuesto compartido entre finalidades es de 30 por IP y 5 por correo normalizado por hora UTC. Las confirmaciones admiten 60 intentos por IP por hora UTC.
- Cada inicio de sesión abre una familia con vigencia máxima de 14 días. Renovar consume el refresh actual y emite otro bajo el mismo bloqueo. La reutilización de un refresh de esa familia revoca también el acceso de esa sesión. Cerrar sesión revoca la familia del navegador.
- Google identifica al titular por emisor y sujeto. Un correo coincidente exige una vinculación explícita con contraseña, desde una sesión vigente de la misma cuenta y con el mismo correo.
- El acceso con Google exige un correo declarado verificado por el proveedor. En altas nuevas Zonda marca como verificados los correos Gmail. Los demás quedan pendientes de la verificación propia de Zonda y vincular una cuenta existente conserva su estado.
- Una publicación pertenece a una categoría hoja y puede venderse cuando está activa y su vendedor está habilitado. La categoría define el esquema de variantes y la comisión aplicable al confirmar la compra.
- El código SKU es único, inmutable y conserva unicidad histórica. La combinación canónica de atributos es única por publicación. El stock disponible y reservado son no negativos.

## Carrito, orden y reserva

- El checkout confirma una `OrdenPago` y una `SubOrden` por vendedor en la misma transacción. Se congelan SKU, nombre, cantidad, precio, comisión, dirección y costo de envío. Los históricos conservan esos valores.
- Los importes se calculan en servidor en ARS con `BigDecimal` y `NUMERIC(19,2)`. La comisión se calcula sobre mercadería y redondea por línea con `HALF_UP`. El envío tiene un costo fijo configurable por vendedor y su valor por defecto es cero.
- La reserva dura 15 minutos. Pasa cantidad de disponible a reservado, se consume una vez al aplicar el cobro elegible y se libera una vez al vencer o fallar definitivamente. El carrito se conserva hasta el pago y se descuenta solo lo comprado.
- Para confirmar el checkout, bloquear primero el carrito y después los SKU en orden determinista. Para operaciones sobre la orden existente, adquirir bloqueos en el orden `OrdenPago → SubOrden → VarianteSKU`, ordenando por UUID dentro de cada conjunto. Los comandos validan el plazo aun cuando el scheduler esté suspendido.

## Pago y conciliación

- Se admiten intentos secuenciales con historial, uno activo a la vez y un solo cobro ganador por orden. Cada intento se persiste antes de llamar a Mercado Pago y tiene una clave de idempotencia estable.
- El estado de pago se aplica tras comprobar en la API de Mercado Pago la referencia externa, la moneda, el importe, la cuenta colectora y el estado actual. El retorno web es una señal de correlación. Los webhooks se guardan antes de responder y se deduplican los efectos por transición y clave financiera.
- Ante un cobro confirmado fuera de plazo, tramitar una compensación íntegra. Si se detecta un segundo cobro en una orden válida, compensar ese cobro por separado y preservar la logística del cobro ganador.
- Card Payment Brick y Checkout Pro comparten conciliación. En la demo los cobros usan una cuenta de prueba colectora y la liquidación por vendedor se registra internamente.

## Despacho, disputas y devoluciones

- Un pago verificado habilita las subórdenes en espera. Cada vendedor despacha su propia suborden. El comprador confirma la entrega y puede dejar una reseña por suborden entregada.
- El comprador puede abrir una disputa por suborden mientras se prepara o después del despacho. Una vez entregada, dispone de siete días corridos. Una disputa activa bloquea despacho y confirmación de entrega, y la conversación es privada entre las partes y administración.
- Reservar el importe de un reembolso antes de enviarlo. El tope es el saldo cobrado menos los reembolsos confirmados y las reservas de reembolso, tanto para el pago como para la suborden cuando corresponda.
- Los importes cambian al confirmar la devolución. Una devolución parcial conserva el estado logístico y revierte la comisión que corresponda sobre mercadería. Una devolución postventa no repone inventario automáticamente. Si el resultado externo es incierto, conservar la reserva de reembolso y conciliar.

## Históricos y eventos

- Las entidades persistidas usan UUID, timestamps UTC y baja lógica. Las bajas conservan snapshots y registros financieros. Las transiciones relevantes guardan actor, instante y correlación.
- Registrar el evento de outbox en la transacción del cambio de negocio. Los consumidores reintentan con espera progresiva y registran la confirmación. El estado visible en la UI se apoya en datos de negocio ya persistidos.
