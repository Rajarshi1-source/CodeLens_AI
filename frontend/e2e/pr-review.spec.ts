import { test, expect, openPr, prIdOf } from './fixtures';

test.describe('reviewed PR', () => {
  test('findings render inline, directly under the line they anchor to', async ({ page }) => {
    await openPr(page, 1);
    const table = page.getByTestId('diff-table').first();
    const commentRow = table.locator('[data-testid="comment-row"][data-after-line="10"]');
    await expect(commentRow).toContainText('Hardcoded secret');
    await expect(commentRow.getByTestId('suggestion-block')).toContainText('System.getenv("API_KEY")');

    // The row immediately before the comment is new-file line 10 (the committed key).
    const anchor = commentRow.locator('xpath=preceding-sibling::tr[1]');
    await expect(anchor).toHaveAttribute('data-new-line', '10');
    await expect(anchor).toContainText('sk-live-');

    await page.getByRole('button', { name: 'Unified' }).click();
    await expect(table).toHaveAttribute('data-mode', 'unified');
    await expect(table.locator('[data-testid="comment-row"][data-after-line="10"]')).toBeVisible();
  });

  test('the cross-file finding names the caller from the repository index (G1)', async ({ page }) => {
    await openPr(page, 2);
    await expect(page.getByTestId('diff-viewer')).toContainText(
      /`calculateTotal` now takes 2 parameter\(s\), but the caller in src\/main\/java\/\S+\.java still passes 1/,
    );
  });

  test('summary tab explains the risk score and the cost', async ({ page }) => {
    await openPr(page, 1);
    await page.getByRole('tab', { name: 'Summary' }).click();
    const risk = page.getByTestId('risk-panel');
    await expect(risk.getByTestId('risk-badge')).toHaveAttribute('data-risk', 'HIGH');
    await expect(risk.getByTestId('risk-reasons')).toContainText('Touches sensitive code');
    await expect(risk).toContainText('Recommended');
    const cost = page.getByTestId('cost-panel');
    await expect(cost).toContainText('local:local-mock');
    await expect(cost).toContainText('$0.00');
  });

  test('falls back to a findings list when no diff is available', async ({ page }) => {
    await page.route('**/api/prs/*/diff', async (route) => {
      const res = await route.fetch();
      const body = await res.json();
      await route.fulfill({ response: res, json: { ...body, diff: '' } });
    });
    await openPr(page, 1);
    await expect(page.getByTestId('findings-list')).toContainText('LoginService.java');
    await expect(page.getByTestId('diff-viewer')).toHaveCount(0);
  });

  test('a thumbs-down persists across reloads (feedback flywheel)', async ({ page }) => {
    await openPr(page, 2);
    const comment = page.getByTestId('diff-viewer').getByTestId('inline-comment').first();
    const down = comment.getByRole('button', { name: 'Not helpful' });
    await expect(down).toHaveAttribute('aria-pressed', 'false');
    await down.click();
    await expect(down).toHaveAttribute('aria-pressed', 'true');
    await expect(down).toHaveText('1');

    await page.reload();
    const again = page.getByTestId('diff-viewer').getByTestId('inline-comment').first();
    await expect(again.getByRole('button', { name: 'Not helpful' })).toHaveAttribute('aria-pressed', 'true');

    // Clicking the active vote again clears it.
    await again.getByRole('button', { name: 'Not helpful' }).click();
    await expect(again.getByRole('button', { name: 'Not helpful' })).toHaveAttribute('aria-pressed', 'false');
  });

  test('apply suggestion posts once and is idempotent', async ({ page }) => {
    await openPr(page, 1);
    const comment = page.getByTestId('diff-viewer').locator('[data-testid="inline-comment"][data-line="10"]');
    await comment.getByTestId('apply-suggestion').click();
    const link = comment.getByTestId('applied-link');
    await expect(link).toBeVisible();
    const href = await link.getAttribute('href');
    expect(href).toContain('/pull/1#discussion_r');

    // A second POST (double click, retry) returns the same suggestion instead of posting another.
    const prId = await prIdOf(page, 1);
    const comments = await (await page.request.get(`/api/prs/${prId}/comments`)).json();
    const secret = comments.find((c: { lineNumber: number }) => c.lineNumber === 10);
    const again = await (await page.request.post(`/api/comments/${secret.id}/apply`)).json();
    expect(again).toMatchObject({ state: 'APPLIED', htmlUrl: href });
  });
});
