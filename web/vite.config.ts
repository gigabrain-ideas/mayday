import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": new URL("./src", import.meta.url).pathname } },
  server: {
    port: 5173,
    proxy: Object.fromEntries(["/mayday.v1.", "/auth", "/oauth", "/mcp", "/api"].map((p) => [p, "http://localhost:8080"])),
  },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 800, assetsInlineLimit: 0 },
});
