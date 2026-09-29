import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In development the API is proxied, so the browser sees a single origin (no CORS).
const api = process.env.STONKS_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: api, ws: true, changeOrigin: false },
    },
  },
  test: {
    environment: "jsdom",
  },
});
