import { Link, useLoaderData } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { googleReturnLoader } from "./google-auth";

export function GoogleReturnPage() {
  const { message } = useLoaderData<typeof googleReturnLoader>();
  return (
    <>
      <h1 className="text-2xl font-semibold">Acceso con Google</h1>
      <p role="alert" className="mt-4 text-destructive">{message}</p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Button asChild><Link to="/ingresar">Volver al ingreso</Link></Button>
        <Button variant="outline" asChild><Link to="/cuenta">Ir a mi cuenta</Link></Button>
      </div>
    </>
  );
}
