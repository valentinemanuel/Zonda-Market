export const categories = ["Tecnología", "Hogar y decoración", "Indumentaria", "Deportes y aire libre"] as const;
type Category = typeof categories[number];

type Product = {
  slug: string;
  title: string;
  category: Category;
  seller: string;
  description: string;
  price: number;
  photo: number;
  option: string;
  variants: string[];
};

// Selección del catálogo ficticio de E7-02, con las mismas fotos de referencia.
export const products: Product[] = [
  { slug: "auriculares", title: "Auriculares de estudio", category: "Tecnología", seller: "Taller Puerto",
    description: "Copa circumaural y vincha acolchada para edición y escucha en escritorio.", price: 68000,
    photo: 30428610, option: "Conexión", variants: ["3,5 mm", "6,35 mm", "USB-C"] },
  { slug: "taza", title: "Taza de cerámica", category: "Hogar y decoración", seller: "Casa Jarilla",
    description: "Pieza esmaltada con asa y terminación mate.", price: 17500,
    photo: 31046284, option: "Capacidad", variants: ["250 ml", "300 ml", "350 ml"] },
  { slug: "zapatillas", title: "Zapatillas urbanas", category: "Indumentaria", seller: "Ropero del Sur",
    description: "Par de uso diario con suela de goma y cordones.", price: 97000,
    photo: 18946896, option: "Talle", variants: ["38", "39", "40"] },
  { slug: "mat", title: "Colchoneta para yoga", category: "Deportes y aire libre", seller: "Estación Norte",
    description: "Superficie antideslizante y formato enrollable.", price: 29800,
    photo: 6193621, option: "Grosor", variants: ["4 mm", "6 mm", "8 mm"] },
  { slug: "teclado", title: "Teclado mecánico compacto", category: "Tecnología", seller: "Taller Puerto",
    description: "Diseño compacto y teclas desmontables para escritorio.", price: 88000,
    photo: 15372894, option: "Switch", variants: ["Lineal", "Táctil", "Sonoro"] },
  { slug: "lampara", title: "Lámpara de mesa", category: "Hogar y decoración", seller: "Casa Jarilla",
    description: "Luz de apoyo para escritorio o mesa de luz.", price: 48000,
    photo: 12801238, option: "Altura", variants: ["30 cm", "35 cm", "40 cm"] },
  { slug: "remera", title: "Remera básica de algodón", category: "Indumentaria", seller: "Ropero del Sur",
    description: "Corte recto y cuello redondo para combinar en capas.", price: 21500,
    photo: 37704838, option: "Talle", variants: ["S", "M", "L"] },
  { slug: "mancuernas", title: "Par de mancuernas", category: "Deportes y aire libre", seller: "Estación Norte",
    description: "Peso fijo y agarre revestido para entrenamiento doméstico.", price: 42500,
    photo: 4959808, option: "Peso", variants: ["2 kg", "3 kg", "4 kg"] },
  { slug: "camara", title: "Cámara digital", category: "Tecnología", seller: "Segunda Vuelta",
    description: "Cuerpo compacto para fotografía de viaje con correa de mano.", price: 350000,
    photo: 5613269, option: "Kit", variants: ["Cuerpo", "Con estuche", "Con memoria"] },
  { slug: "maceta", title: "Maceta decorativa", category: "Hogar y decoración", seller: "Casa Jarilla",
    description: "Contenedor para plantas de interior con acabado liso.", price: 21000,
    photo: 2326115, option: "Diámetro", variants: ["12 cm", "15 cm", "18 cm"] },
  { slug: "buzo", title: "Buzo con capucha", category: "Indumentaria", seller: "Ropero del Sur",
    description: "Abrigo de media estación con bolsillo frontal y cordones.", price: 47500,
    photo: 6786666, option: "Talle", variants: ["S", "M", "L"] },
  { slug: "botella", title: "Botella reutilizable", category: "Deportes y aire libre", seller: "Estación Norte",
    description: "Botella para entrenamiento o caminatas, con tapa roscada.", price: 18500,
    photo: 20292101, option: "Capacidad", variants: ["500 ml", "750 ml", "1 L"] },
];

export function photoUrl(photo: number, width: number) {
  return `https://images.pexels.com/photos/${photo}/pexels-photo-${photo}.jpeg?auto=compress&cs=tinysrgb&w=${width}`;
}
