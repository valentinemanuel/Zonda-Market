# Diseño de Zonda Market

## Prototipos de referencia

Los prototipos de Stitch que acompañaron E0-02 siguen siendo la base visual del marketplace. Usá **Inicio** para la navegación y el descubrimiento de categorías y publicaciones, **Ficha de producto** para la galería, las variantes y la acción de compra, y **Checkout** para la dirección, los grupos por vendedor, el pago y el total. Tomá de esas pantallas la jerarquía visual, la disposición y el orden de lectura. En flujos sin una maqueta propia, reutilizá los bloques y patrones de la pantalla más cercana.

La lámina «Viento de origen» es la fuente del isotipo. Al implementar los prototipos, aplicá la identidad de marca aprobada en esta guía y ajustá los textos promocionales, las garantías y los medios de pago a las capacidades verificadas de cada entrega.

## Identidad y tokens

| Uso | Decisión |
| --- | --- |
| Marca | Naranja claro `#FF7D3B` y azul noche `#12192B`. La cabecera compartida usa el isotipo a color de «Viento de origen» y el nombre en Bricolage Grotesque, en minúsculas. El favicon usa el isotipo monocromático. |
| Acción principal y selección | Azul cobalto `#2563EB` con texto blanco. Las acciones secundarias usan borde y texto azul. |
| Superficies | Fondo `#F8FAFC`, tarjetas blancas y borde `#E2E8F0`. Esquinas de 8 px en tarjetas, campos y botones. |
| Texto | Inter. Texto principal `#0F172A`, secundario `#475569`. Usar números tabulares para precios e importes. |
| Estados | Verde oscuro para disponibilidad y confirmación, rojo oscuro para errores. En promociones usar naranja oscuro sobre fondo cálido. Acompañar el color con texto. |

Declarar los tokens compartidos en `frontend/src/index.css` y consumirlos desde Tailwind y shadcn/ui. Usar texto azul noche sobre el naranja de la cabecera para mantener el contraste.

Las pantallas actuales y futuras de la tienda reutilizan la [cabecera de `SiteLayout`](../frontend/src/app/layouts.tsx). El nombre visible es **zonda market** en Bricolage Grotesque ExtraBold (800). Para el isotipo a color, usar `/brand/zonda-isotipo.svg#color`, con el nombre accesible «Zonda Market» en el enlace. Inter se usa en el contenido y los controles de la interfaz.

## Bloques por flujo

- **Identidad.** Alta, ingreso y cuenta usan la cabecera y una tarjeta angosta con botones compartidos. Los campos tienen etiquetas visibles, foco de validación y errores asociados. En móvil la tarjeta usa una sola columna y deja accesibles la acción principal y el cambio entre alta e ingreso. La restauración de sesión y el envío de formularios anuncian carga. La API rechazada muestra un mensaje y permite volver a intentar.
- **Perfil y venta.** Mi cuenta agrupa el nombre de perfil, el acceso a Direcciones y la activación de vendedor. Direcciones reutiliza el orden de campos del bloque de Checkout, con una lista editable y confirmación de baja. La ficha pública retoma el bloque de vendedor de Ficha de producto y muestra el estado inicial de reputación. En móvil se conserva una columna, los estados de carga y error se anuncian y los formularios y enlaces se recorren con teclado.
- **Verificación y recuperación.** Mi cuenta muestra el estado del correo y el envío o reenvío del enlace mientras esté pendiente. Ingresar enlaza a la recuperación. Las pantallas reutilizan la tarjeta de identidad en una columna, con campos etiquetados, estados de envío y confirmación anunciados y una acción para volver o pedir un enlace nuevo si venció o ya se usó.
- **Acceso con Google.** El alta y el ingreso muestran una acción secundaria cuando Google está habilitado. El retorno anuncia el intercambio y ofrece volver al ingreso o a la cuenta si se cancela o falla. Seguridad de cuenta muestra el estado de vinculación y permite confirmar la contraseña con un formulario en la misma tarjeta.

- **Categoría y catálogo.** Cabecera y navegación compartidas, búsqueda, tarjetas de categoría y una grilla de productos. Las tarjetas de producto reservan una imagen cuadrada, precio, vendedor y disponibilidad. Mostrar descuentos, reputación y envíos cuando existan datos que los respalden.
- **Publicaciones del vendedor.** El panel enlaza al listado de borradores. El editor toma los bloques de descripción y variantes de Ficha de producto y permite alternar con pestañas que admiten las flechas del teclado. Los atributos se eligen según la categoría. En móvil los campos se apilan y la tabla agrupa código con atributos y precio con stock. El guardado anuncia progreso, éxito y errores. Ante un conflicto de versión, la recarga explícita recupera los datos persistidos.
- **Ficha.** La vista previa y la ficha pública comparten galería, miniaturas, descripción, especificaciones y selector de variante con precio y disponibilidad. El bloque público enlaza a la ficha del vendedor. En móvil la galería y el detalle se apilan. Las miniaturas tienen un nombre accesible y anuncian cuál está seleccionada. La pantalla ofrece reintento en errores de carga y una salida al inicio cuando la publicación fue archivada.
- **Imágenes y publicación.** Las pestañas Imágenes y Vista previa reutilizan las tarjetas del panel. La galería permite subir, editar texto alternativo, mover y retirar imágenes con controles de teclado. Se anuncian guardado, éxito y errores recuperables. Vista previa presenta la ficha guardada y las acciones de publicar, abrir el enlace público y archivar.
- **Stock.** Cada fila de Variantes abre el editor de inventario y lleva el foco a su encabezado. En móvil se apilan el ajuste, el motivo y el historial paginado. La interfaz anuncia carga, guardado y error. Una versión obsoleta permite recargar el saldo y conservar los campos para revisarlos.
- **Checkout.** Dirección, grupos de productos por vendedor, medio de pago y resumen con un solo total. Respetar el flujo de pago y los componentes del proveedor definidos en la tarjeta correspondiente.

## Formato para una tarjeta de interfaz

1. Indicá en el issue qué pantalla de Stitch guía el diseño, qué bloques se reutilizan o se agregan y cómo se adapta el flujo al objetivo del usuario.
2. Describí carga, contenido, vacío y error según el flujo. Incluí recuperación, navegación por teclado y cómo cambia la disposición en móvil.
3. Usá los componentes compartidos de shadcn/ui y definí componentes de dominio en su feature. Para lecturas remotas, usá claves de consulta que incluyan la identidad del recurso y propagá la cancelación.
4. Adjuntá al PR una captura o prueba del estado principal y del fallo relevante. Revisá las reglas aplicables de [buenas prácticas de React](https://github.com/vercel-labs/agent-skills/blob/main/skills/react-best-practices/SKILL.md).

E0-02 aplica estos criterios a la lectura de categoría. La navegación, la grilla y los bloques completos del marketplace se incorporan cuando sus tarjetas definan comportamiento y datos.
