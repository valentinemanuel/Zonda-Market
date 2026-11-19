# Diseño de Zonda Market

La referencia de interfaz son las tres pantallas de Stitch que acompañaron E0-02: inicio, ficha de producto y checkout. La lámina «Viento de origen» define el logotipo de montaña y viento. Los textos promocionales, garantías y medios de pago de las maquetas deben corresponder al alcance y a las capacidades verificadas de cada entrega.

## Identidad y tokens

| Uso | Decisión |
| --- | --- |
| Marca | Naranja `#EF590C` y azul noche `#12192B`. Cabecera naranja con el isotipo y el nombre Zonda Market, e isotipo solo para el favicon. |
| Acción principal y selección | Azul cobalto `#2563EB` con texto blanco. Las acciones secundarias usan borde y texto azul. |
| Superficies | Fondo `#F8FAFC`, tarjetas blancas y borde `#E2E8F0`. Esquinas de 8 px en tarjetas, campos y botones. |
| Texto | Inter. Texto principal `#0F172A`, secundario `#475569`. Usar números tabulares para precios e importes. |
| Estados | Verde oscuro para disponibilidad y confirmación, rojo oscuro para errores. En promociones usar naranja oscuro sobre fondo cálido. Acompañar el color con texto. |

Declarar los tokens compartidos en `frontend/src/index.css` y consumirlos desde Tailwind y shadcn/ui. Para mantener contraste sobre el naranja de marca, usar texto azul noche y el isotipo monocromático sobre su fondo oscuro.

## Bloques por flujo

- **Identidad.** Alta, ingreso y cuenta usan la cabecera y una tarjeta angosta con botones compartidos. Los campos tienen etiquetas visibles, foco de validación y errores asociados. En móvil la tarjeta usa una sola columna y deja accesibles la acción principal y el cambio entre alta e ingreso. La restauración de sesión y el envío de formularios anuncian carga. La API rechazada muestra un mensaje y permite volver a intentar.
- **Verificación y recuperación.** Mi cuenta muestra el estado del correo y el envío o reenvío del enlace mientras esté pendiente. Ingresar enlaza a la recuperación. Las pantallas reutilizan la tarjeta de identidad en una columna, con campos etiquetados, estados de envío y confirmación anunciados y una acción para volver o pedir un enlace nuevo si venció o ya se usó.
- **Acceso con Google.** El alta y el ingreso muestran una acción secundaria cuando Google está habilitado. El retorno anuncia el intercambio y ofrece volver al ingreso o a la cuenta si se cancela o falla. Seguridad de cuenta muestra el estado de vinculación y permite confirmar la contraseña con un formulario en la misma tarjeta.

- **Categoría y catálogo.** Cabecera y navegación compartidas, búsqueda, tarjetas de categoría y una grilla de productos. Las tarjetas de producto reservan una imagen cuadrada, precio, vendedor y disponibilidad. Mostrar descuentos, reputación y envíos cuando existan datos que los respalden.
- **Ficha.** Galería, información y selección de variantes, acción principal, vendedor, preguntas y reputación. En móvil, ordenar contenido y acciones siguiendo el flujo de lectura.
- **Checkout.** Dirección, grupos de productos por vendedor, medio de pago y resumen con un solo total. Respetar el flujo de pago y los componentes del proveedor definidos en la tarjeta correspondiente.

## Formato para una tarjeta de interfaz

1. Identificá la pantalla de referencia, el objetivo del usuario y los bloques que se reutilizan o se agregan.
2. Describí carga, contenido, vacío y error según el flujo. Incluí recuperación, navegación por teclado y cómo cambia la disposición en móvil.
3. Usá los componentes compartidos de shadcn/ui y definí componentes de dominio en su feature. Para lecturas remotas, usá claves de consulta que incluyan la identidad del recurso y propagá la cancelación.
4. Adjuntá al PR una captura o prueba del estado principal y del fallo relevante. Revisá las reglas aplicables de [buenas prácticas de React](https://github.com/vercel-labs/agent-skills/blob/main/skills/react-best-practices/SKILL.md).

E0-02 aplica estos criterios a la lectura de categoría. La navegación, la grilla y los bloques completos del marketplace se incorporan cuando sus tarjetas definan comportamiento y datos.
