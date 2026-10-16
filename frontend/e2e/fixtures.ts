import { test as base, expect, type Page } from '@playwright/test';
import { storageStateFor } from './global-setup';

/** Console noise that is expected and harmless (the unauthenticated /api/auth/me probe on the login page). */
const IGNORED = [/status of 401/, /Download the React DevTools/];

type Fixtures = { consoleErrors: string[] };

/**
 * `test` signed in as Alice, failing any test whose page logged a console error or threw — the suite doubles
 * as a smoke test for runtime errors the unit tests cannot see (proxying, WebSocket, Radix refs...).
 */
export const test = base.extend<Fixtures>({
  storageState: storageStateFor('alice'),
  consoleErrors: [
    async ({ page }, run) => {
      const errors: string[] = [];
      page.on('console', (m) => {
        if (m.type() === 'error' && !IGNORED.some((re) => re.test(m.text()))) errors.push(m.text());
      });
      page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
      await run(errors);
      expect(errors, 'console errors').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/** Open a seeded PR by its number from the PR list (ids change on every demo reset). */
export async function openPr(page: Page, number: number) {
  await page.goto('/prs');
  await page.locator(`[data-testid="pr-row"][data-number="${number}"]`).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(`#${number}`);
}

/** The id of a seeded PR, read from the PR list API. */
export async function prIdOf(page: Page, number: number): Promise<number> {
  const res = await page.request.get('/api/prs');
  const prs = (await res.json()) as { id: number; number: number }[];
  const pr = prs.find((p) => p.number === number);
  if (!pr) throw new Error(`PR #${number} not found`);
  return pr.id;
}
