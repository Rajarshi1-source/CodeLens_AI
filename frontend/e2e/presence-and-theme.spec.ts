import { test, expect, openPr } from './fixtures';
import { storageStateFor } from './global-setup';

test('two reviewers see each other and the line each is looking at (G5)', async ({ page, browser }) => {
  await openPr(page, 3);

  const bobContext = await browser.newContext({ storageState: storageStateFor('bob') });
  const bob = await bobContext.newPage();
  await openPr(bob, 3);

  // Alice sees Bob (and not herself).
  const bobAvatar = page.locator('[data-testid="presence-avatar"][data-login="demo-bob"]');
  await expect(bobAvatar).toBeVisible();
  await expect(page.locator('[data-testid="presence-avatar"][data-login="demo-alice"]')).toHaveCount(0);
  // …and Bob sees Alice.
  await expect(bob.locator('[data-testid="presence-avatar"][data-login="demo-alice"]')).toBeVisible();

  // Bob points at a line: Alice gets a focus marker in that line's gutter.
  await bob.locator('[data-testid="diff-table"] tr[data-new-line]').nth(3).hover();
  await expect(page.getByTestId('focus-marker')).toHaveAttribute('title', 'demo-bob looking here');

  // Bob leaves: his avatar disappears.
  await bobContext.close();
  await expect(bobAvatar).toHaveCount(0, { timeout: 30_000 });
});

test('theme toggle switches and persists', async ({ page }) => {
  await page.goto('/');
  const html = page.locator('html');
  const wasDark = ((await html.getAttribute('class')) ?? '').includes('dark');
  await page.getByRole('button', { name: /switch to (light|dark) mode/i }).click();
  await expect(html).toHaveClass(wasDark ? /^(?!.*\bdark\b).*$/ : /\bdark\b/);
  await page.reload();
  await expect(html).toHaveClass(wasDark ? /^(?!.*\bdark\b).*$/ : /\bdark\b/);
});

test('repositories page shows the retrieval index and can re-index', async ({ page }) => {
  await page.goto('/repos');
  const row = page.getByTestId('repo-row').filter({ hasText: 'codelens-demo/storefront' });
  await expect(row.getByTestId('index-status')).toContainText(/Indexed · \d+ chunks/);
  await row.getByRole('button', { name: /re-index/i }).click();
  await expect(page.getByText('Re-index queued')).toBeVisible();
  await expect(row.getByTestId('index-status')).toContainText(/Indexed · \d+ chunks/, { timeout: 30_000 });
});
