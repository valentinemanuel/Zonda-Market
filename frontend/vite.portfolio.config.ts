import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import baseConfig from "./vite.config.ts";

export default defineConfig({
  ...baseConfig,
  root: fileURLToPath(new URL("./portfolio", import.meta.url)),
  publicDir: fileURLToPath(new URL("./public", import.meta.url)),
  server: {},
});
