import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  workers: 1,
  timeout: 60000,
  use: {
    baseURL: process.env.BASE_URL || "http://127.0.0.1:8081",
    headless: true,
    viewport: { width: 1512, height: 1100 },
    screenshot: "only-on-failure",
  },
  reporter: "list",
});
