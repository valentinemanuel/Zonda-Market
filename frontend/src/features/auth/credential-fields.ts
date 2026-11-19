import { z } from "zod";

export const emailField = z.string().trim().max(254).pipe(z.email("Ingresá un correo válido."));

export const passwordField = z.string()
  .refine((value) => value.trim().length > 0, "Ingresá tu contraseña.")
  .refine((value) => new TextEncoder().encode(value).length <= 72, "Usá hasta 72 bytes UTF-8.");

export const newPasswordField = passwordField.min(12, "Usá al menos 12 caracteres.");
