import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  // The status scenario deliberately stops PostgreSQL.
  workers: 1,
  reporter: "list",
  use: {
    browserName: "chromium",
    baseURL: process.env.ZONDA_SMOKE_URL ?? "http://127.0.0.1:3000",
    viewport: { width: 1280, height: 900 },
    trace: "retain-on-failure",
  },
});
