import { defineConfig } from "@playwright/test";

const url = "http://127.0.0.1:4179";

export default defineConfig({
  testDir: "./portfolio",
  outputDir: "test-results/portfolio",
  use: { baseURL: url, trace: "retain-on-failure" },
  webServer: {
    command: "npm run preview:portfolio -- --host 127.0.0.1 --port 4179 --strictPort",
    url,
    reuseExistingServer: !process.env.CI,
  },
});
