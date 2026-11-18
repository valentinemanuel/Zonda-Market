import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/oauth2/authorization/google": "http://localhost:8080",
      "/login/oauth2/code/google": "http://localhost:8080",
      "/actuator/health/readiness": "http://localhost:8080",
      "/v3/api-docs": "http://localhost:8080",
      "/swagger-ui": "http://localhost:8080",
    },
  },
});
