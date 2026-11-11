/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/** Spring Boot, in dev: everything outside /app/ (the API, logging in, the old pages) goes there. */
const spring = "http://localhost:8080";

export default defineConfig({
  // Served by Spring at /app/; the old server-rendered pages keep their addresses until replaced.
  base: "/app/",
  plugins: [react()],
  build: {
    // Into the jar, next to the old pages' static files (the Maven build runs this).
    outDir: "../target/classes/static/app",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // The app reuses the dashboard's stylesheet and icons from src/main/resources.
    fs: { allow: [".."] },
    proxy: {
      "^/(?!app(/|$)).*": { target: spring },
    },
  },
  test: {
    environment: "jsdom",
  },
});
