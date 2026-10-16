import { test, expect, openPr } from './fixtures';

/**
 * PR #5 is seeded PENDING. Re-reviewing it streams findings over STOMP (the demo profile paces the
 * offline model so the stream is observable), then the persisted review replaces the live one.
 */
test.describe.serial('live review', () => {
  test('re-review streams findings, then completes with a risk score', async ({ page }) => {
    await openPr(page, 5);
    await expect(page.getByTestId('risk-badge')).toHaveText('Not scored');

    await page.getByRole('button', { name: 'Re-review' }).click();
    await expect(page.getByRole('button', { name: 'Reviewing…' })).toBeDisabled();

    // A finding arrives while the review is still running…
    await expect(page.locator('[data-testid="inline-comment"][data-streaming="true"]').first()).toBeVisible();
    // …and the review completes.
    await expect(page.getByRole('button', { name: 'Re-review' })).toBeEnabled({ timeout: 30_000 });
    await expect(page.locator('[data-testid="inline-comment"][data-streaming="true"]')).toHaveCount(0);
    await expect(page.getByTestId('risk-badge').first()).toHaveAttribute('data-risk', 'LOW');
    await expect(page.getByRole('tab', { name: 'Comments (2)' })).toBeVisible();
    // Persisted comments carry ids, so they gain feedback controls.
    await expect(page.getByTestId('diff-viewer').getByTestId('comment-feedback').first()).toBeVisible();
  });

  test('reloading mid-stream resumes from the replay buffer without duplicates', async ({ page }) => {
    await openPr(page, 5);
    await page.getByRole('button', { name: 'Re-review' }).click();
    await expect(page.locator('[data-testid="inline-comment"][data-streaming="true"]').first()).toBeVisible();

    await page.reload();
    // The reconnecting client asks /app/pr/{id}/replay for what it missed, then continues live.
    await expect(page.getByRole('button', { name: 'Re-review' })).toBeEnabled({ timeout: 30_000 });
    await expect(page.getByRole('tab', { name: 'Comments (2)' })).toBeVisible();
    await expect(page.getByTestId('diff-viewer').getByTestId('comment-row')).toHaveCount(2);
  });
});
