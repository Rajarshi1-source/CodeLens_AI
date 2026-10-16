import { test, expect } from './fixtures';

test.describe('authentication', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('an anonymous visitor is sent to the login page, which offers the demo', async ({ page }) => {
    await page.goto('/prs');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByTestId('demo-login')).toBeVisible();
    // No real OAuth app in the demo profile, so no GitHub button that could only fail.
    await expect(page.getByRole('button', { name: /sign in with github/i })).toHaveCount(0);
  });

  test('demo login as Bob, then sign out', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Demo as Bob' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

    await page.getByRole('button', { name: 'Account menu' }).click();
    await expect(page.getByRole('menu')).toContainText('Bob (demo)');
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('protected APIs answer 401 without a session', async ({ request }) => {
    expect((await request.get('/api/prs')).status()).toBe(401);
    expect((await request.get('/api/auth/me')).status()).toBe(401);
  });
});
