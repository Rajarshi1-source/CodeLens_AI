import { test, expect } from './fixtures';

test('dashboard shows risk distribution and the LLM budget', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await expect(page.getByText('Total PRs').locator('..').locator('..')).toContainText('5');
  await expect(page.getByTestId('risk-chart')).toBeVisible();
  const budget = page.getByTestId('budget-card');
  await expect(budget).toContainText('LLM budget today');
  await expect(budget).toContainText(/tokens left/);
});

test('PR list shows a risk band per PR and filters by it', async ({ page }) => {
  await page.goto('/prs');
  const rows = page.getByTestId('pr-row');
  await expect(rows).toHaveCount(5);

  const riskOf = (n: number) => page.locator(`[data-testid="pr-row"][data-number="${n}"] [data-testid="risk-badge"]`);
  await expect(riskOf(1)).toHaveAttribute('data-risk', 'HIGH');
  await expect(riskOf(2)).toHaveAttribute('data-risk', 'HIGH');
  await expect(riskOf(3)).toHaveAttribute('data-risk', 'MEDIUM');
  await expect(riskOf(4)).toHaveAttribute('data-risk', 'LOW');
  await expect(riskOf(5)).toHaveText('Not scored');

  await page.getByRole('combobox', { name: 'Filter by risk' }).click();
  await page.getByRole('option', { name: 'High risk' }).click();
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toHaveAttribute('data-number', /^[12]$/);

  await page.getByRole('combobox', { name: 'Filter by risk' }).click();
  await page.getByRole('option', { name: 'Any risk' }).click();
  await page.getByRole('textbox', { name: 'Search pull requests' }).fill('discount');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toHaveAttribute('data-number', '2');
});
