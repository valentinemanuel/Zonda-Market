import { ArrowRight } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { Card, CardContent } from "@/shared/ui/card";

const topics = {
  ayuda: {
    title: "Centro de ayuda",
    sections: [
      { title: "Seguí tus compras", text: "Abrí el detalle de una orden para consultar el estado del pago, ver el seguimiento o registrar un problema con la compra.", link: ["Ir a Mis órdenes", "/mis-ordenes"] },
      { title: "Entrá a tu cuenta", text: "Si olvidaste la contraseña, solicitá un enlace al correo de tu cuenta.", link: ["Recuperar el acceso", "/recuperar-acceso"] },
      { title: "Actualizá tus direcciones", text: "Elegí una dirección vigente antes de cotizar el envío.", link: ["Administrar direcciones", "/cuenta/direcciones"] },
    ],
  },
  "guia-vendedores": {
    title: "Guía para vendedores",
    sections: [
      { title: "Activá tu perfil", text: "Ingresá a Mi cuenta, verificá el correo y activá tu perfil de vendedor.", link: ["Ir a Mi cuenta", "/cuenta"] },
      { title: "Prepará tu publicación", text: "Elegí categoría, cargá fotos y completá el precio y el stock de cada variante antes de publicar.", link: ["Crear una publicación", "/vendedor/publicaciones/nueva"] },
      { title: "Gestioná tus pedidos", text: "Cuando se acredita el pago, prepará el paquete y cargá el transportista y el código de seguimiento.", link: ["Ver pedidos recibidos", "/vendedor/pedidos"] },
    ],
  },
  terminos: {
    title: "Términos y condiciones",
    introduction: "Información sobre el funcionamiento de Zonda Market en su versión actual.",
    sections: [
      { title: "Precios y reserva", text: "El checkout confirma el precio y el costo de envío para la dirección seleccionada. La reserva tiene un plazo visible para pagar." },
      { title: "Envío y entrega", text: "Cada vendedor tramita su despacho. El comprador puede consultar el seguimiento y confirmar la recepción en el detalle de la orden." },
      { title: "Problemas con una compra", text: "El comprador puede abrir un reclamo desde el detalle de la orden. La conversación queda disponible para las partes y el equipo de moderación.", link: ["Consultar tus compras", "/mis-ordenes"] },
    ],
  },
  privacidad: {
    title: "Privacidad",
    introduction: "Información sobre los datos que usa la versión actual de Zonda Market.",
    sections: [
      { title: "Cuenta y sesión", text: "Usamos el correo para identificar y verificar tu cuenta. La sesión usa una cookie protegida. Podés cerrarla desde Mi cuenta.", link: ["Ir a Mi cuenta", "/cuenta"] },
      { title: "Direcciones y pedidos", text: "La dirección elegida se conserva con la orden para gestionar la entrega. El seguimiento, los avisos y los reclamos se consultan desde una sesión autorizada." },
      { title: "Tarjetas", text: "Mercado Pago recibe los datos de la tarjeta en sus campos seguros. Zonda puede mostrar los últimos cuatro dígitos en tu comprobante." },
    ],
  },
};

export function StoreInformation() {
  const { topic = "" } = useParams();
  const entry = Object.hasOwn(topics, topic) ? topics[topic as keyof typeof topics] : undefined;
  if (!entry) return <h1 className="font-wordmark text-3xl font-bold">Página no encontrada</h1>;
  return <div className="mx-auto max-w-3xl space-y-5">
    <h1 className="font-wordmark text-3xl font-extrabold sm:text-4xl">{entry.title}</h1>
    {"introduction" in entry && <p className="text-muted-foreground">{entry.introduction}</p>}
    {entry.sections.map((section) => <Card key={section.title} className="gap-0">
      <CardContent className="space-y-2">
        <h2 className="text-lg font-bold">{section.title}</h2>
        <p className="text-sm leading-relaxed text-muted-foreground">{section.text}</p>
        {"link" in section && section.link && <Link className="inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-primary hover:underline"
          to={section.link[1]}>{section.link[0]} <ArrowRight aria-hidden="true" className="size-4" /></Link>}
      </CardContent>
    </Card>)}
  </div>;
}
