import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end suite against the real backend in the `demo` profile (seeded repo + 5 PRs, password-less
 * demo login, offline LLM paced so streaming is observable). Start the backend first
 * (scripts/run-demo-backend.ps1 locally; the CI `e2e` job boots the jar); Playwright serves the production
 * build with `vite preview` on :4173, which proxies /api and /ws to the backend.
 *
 *   npm run e2e               # functional specs
 *   npm run e2e:screenshots   # regenerate docs/images/*.png for the README
 */
const PORT = 4173;
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  // Specs share one seeded database (reset once in global setup) and some mutate it, so run serially.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 900 },
  },
  projects: [
    {
      name: 'e2e',
      grepInvert: /@screenshot/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'screenshots',
      grep: /@screenshot/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `npm run build && npm run preview -- --port ${PORT} --strictPort`,
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
        stdout: 'ignore',
        stderr: 'pipe',
      },
});
