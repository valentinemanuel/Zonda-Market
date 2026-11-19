import { request } from "@/shared/api/http-client";

export type EmailPurpose = "email-verification" | "password-reset";

export function requestEmailLink(purpose: EmailPurpose, email: string) {
  return emailCommand(purpose, "request", { email });
}

export function confirmEmailLink(purpose: EmailPurpose, token: string, password?: string) {
  return emailCommand(purpose, "confirm", { token, password });
}

function emailCommand(purpose: EmailPurpose, action: "request" | "confirm", body: Record<string, string | undefined>) {
  return request(`/api/v1/auth/${purpose}/${action}`, {
    method: "POST",
    credentials: "omit",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
