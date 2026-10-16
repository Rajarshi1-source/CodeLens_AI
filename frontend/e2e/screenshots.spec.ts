import path from 'node:path';
import type { Page } from '@playwright/test';
import { test, expect, openPr } from './fixtures';
import { storageStateFor } from './global-setup';

/**
 * Regenerates the README screenshots in docs/images/ (run: npm run e2e:screenshots). Tagged @screenshot so
 * the functional `e2e` project skips it.
 */
const OUT = path.resolve(import.meta.dirname, '..', '..', 'docs', 'images');
const shot = (page: Page, name: string) => page.screenshot({ path: path.join(OUT, `${name}.png`) });

const withTheme = (page: Page, theme: 'light' | 'dark') =>
  page.addInitScript((t) => globalThis.localStorage.setItem("codelens-theme", t), theme);

test.describe('@screenshot README images', () => {
  test('login @screenshot', async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport: { width: 1440, height: 900 } });
    const page = await ctx.newPage();
    await withTheme(page, 'dark');
    await page.goto('/login');
    await expect(page.getByTestId('demo-login')).toBeVisible();
    await shot(page, 'login-dark');
    await ctx.close();
  });

  for (const theme of ['dark', 'light'] as const) {
    test(`dashboard and PR list (${theme}) @screenshot`, async ({ page }) => {
      await withTheme(page, theme);
      await page.goto('/');
      await expect(page.getByTestId('risk-chart')).toBeVisible();
      await page.waitForTimeout(1200); // let the chart bars finish animating
      await shot(page, `dashboard-${theme}`);

      await page.goto('/prs');
      await expect(page.getByTestId('pr-row')).toHaveCount(5);
      await shot(page, `pr-list-${theme}`);
    });

    test(`inline review with suggestion (${theme}) @screenshot`, async ({ page }) => {
      await withTheme(page, theme);
      await openPr(page, 1);
      await expect(page.locator('[data-testid="comment-row"][data-after-line="10"]')).toBeVisible();
      await shot(page, `pr-inline-${theme}`);
    });
  }

  test('risk and cost summary @screenshot', async ({ page }) => {
    await withTheme(page, 'dark');
    await openPr(page, 1);
    await page.getByRole('tab', { name: 'Summary' }).click();
    await expect(page.getByTestId('risk-panel')).toBeVisible();
    await shot(page, 'pr-summary-dark');
  });

  test('cross-file finding from repository context @screenshot', async ({ page }) => {
    await withTheme(page, 'dark');
    await openPr(page, 2);
    await expect(page.getByTestId('diff-viewer')).toContainText(/caller in/);
    await shot(page, 'pr-cross-file-dark');
  });

  test('live streaming review @screenshot', async ({ page }) => {
    await withTheme(page, 'dark');
    await openPr(page, 5);
    await page.getByRole('button', { name: 'Re-review' }).click();
    await expect(page.locator('[data-testid="inline-comment"][data-streaming="true"]').first()).toBeVisible();
    await shot(page, 'pr-live-streaming-dark');
    await expect(page.getByRole('button', { name: 'Re-review' })).toBeEnabled({ timeout: 30_000 });
  });

  test('collaborative presence @screenshot', async ({ page, browser }) => {
    await withTheme(page, 'dark');
    await openPr(page, 3);
    const bobCtx = await browser.newContext({ storageState: storageStateFor('bob') });
    const bob = await bobCtx.newPage();
    await openPr(bob, 3);
    await bob.locator('[data-testid="diff-table"] tr[data-new-line]').nth(4).hover();
    await expect(page.getByTestId('focus-marker')).toBeVisible();
    await page.mouse.move(0, 0);
    await shot(page, 'pr-presence-dark');
    await bobCtx.close();
  });
});
