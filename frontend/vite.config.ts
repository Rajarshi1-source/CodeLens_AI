import { configDefaults, defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

// Dev server proxies same-origin paths to the Spring Boot backend (:8080) so the JSESSIONID
// session cookie and the GitHub OAuth redirect work without CORS. /ws is proxied with ws:true
// so the SockJS/STOMP upgrade reaches the backend.
const BACKEND = process.env.CODELENS_BACKEND_URL ?? 'http://localhost:8080';

const PROXY = {
  '/api': { target: BACKEND, changeOrigin: true },
  '/ws': { target: BACKEND, changeOrigin: true, ws: true },
  '/oauth2': { target: BACKEND, changeOrigin: true },
  // Only the OAuth2 callback — '/login' itself is the SPA's login route.
  '/login/oauth2': { target: BACKEND, changeOrigin: true },
  '/logout': { target: BACKEND, changeOrigin: true },
};

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
    proxy: PROXY,
  },
  // `vite preview` (used by the Playwright E2E suite) serves the production build with the same proxy.
  preview: {
    port: 4173,
    proxy: PROXY,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    // Playwright specs live in e2e/ and run under @playwright/test, not Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
