import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

// Dev server proxies same-origin paths to the Spring Boot backend (:8080) so the JSESSIONID
// session cookie and the GitHub OAuth redirect work without CORS. /ws is proxied with ws:true
// so the SockJS/STOMP upgrade reaches the backend.
const BACKEND = 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // sockjs-client references a Node-style `global`; map it to globalThis for dev + build.
  define: { global: 'globalThis' },
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  build: {
    rollupOptions: {
      output: {
        // Split heavy, independently-cacheable vendors out of the main bundle.
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          charts: ['recharts'],
          ws: ['@stomp/stompjs', 'sockjs-client'],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/ws': { target: BACKEND, changeOrigin: true, ws: true },
      '/oauth2': { target: BACKEND, changeOrigin: true },
      '/login': { target: BACKEND, changeOrigin: true },
      '/logout': { target: BACKEND, changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
  },
});
