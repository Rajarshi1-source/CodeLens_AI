import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DiffViewer } from './DiffViewer';
import { renderWithProviders } from '@/test/render';
import type { StreamingComment } from '@/types/domain';

const DIFF = [
  'diff --git a/src/Login.java b/src/Login.java',
  '--- a/src/Login.java',
  '+++ b/src/Login.java',
  '@@ -10,3 +10,4 @@ class Login {',
  '   void a() {}',
  '-  String pw = "x";',
  '+  String pw = "hunter2";',
  '+  String q = "SELECT " + id;',
  '   void b() {}',
  '',
].join('\n');

const comment = (over: Partial<StreamingComment>): StreamingComment => ({
  key: 'k',
  file: 'src/Login.java',
  line: 11,
  severity: 'CRITICAL',
  text: 'Hard-coded secret',
  isStreaming: false,
  ...over,
});

describe('DiffViewer', () => {
  it('renders a finding in a row directly after the line it is anchored to', () => {
    renderWithProviders(<DiffViewer diff={DIFF} comments={[comment({})]} />);
    const rows = [...screen.getByTestId('diff-table').querySelectorAll('tr')];
    const anchorIdx = rows.findIndex((r) => r.getAttribute('data-new-line') === '11');
    expect(anchorIdx).toBeGreaterThan(0);
    const next = rows[anchorIdx + 1];
    expect(next).toHaveAttribute('data-testid', 'comment-row');
    expect(within(next).getByText('Hard-coded secret')).toBeInTheDocument();
  });

  it('lists findings whose line is not in the diff under "Other findings"', () => {
    renderWithProviders(
      <DiffViewer diff={DIFF} comments={[comment({ key: 'far', line: 300, text: 'Elsewhere in file' })]} />,
    );
    const other = screen.getByTestId('other-findings');
    expect(within(other).getByText('Elsewhere in file')).toBeInTheDocument();
    expect(screen.queryByTestId('comment-row')).not.toBeInTheDocument();
  });

  it('switches between split and unified mode', async () => {
    renderWithProviders(<DiffViewer diff={DIFF} comments={[]} />);
    await userEvent.click(screen.getByRole('button', { name: /unified/i }));
    expect(screen.getByTestId('diff-table')).toHaveAttribute('data-mode', 'unified');
    await userEvent.click(screen.getByRole('button', { name: /split/i }));
    expect(screen.getByTestId('diff-table')).toHaveAttribute('data-mode', 'split');
  });

  it('falls back to a findings list grouped by file when there is no diff', () => {
    renderWithProviders(
      <DiffViewer diff="" comments={[comment({}), comment({ key: 'b', file: 'src/B.java', line: 2 })]} />,
    );
    const list = screen.getByTestId('findings-list');
    expect(within(list).getByText('src/Login.java')).toBeInTheDocument();
    expect(within(list).getByText('src/B.java')).toBeInTheDocument();
    expect(screen.queryByTestId('diff-viewer')).not.toBeInTheDocument();
  });

  it("shows another viewer's focused line as a gutter marker", () => {
    renderWithProviders(
      <DiffViewer
        diff={DIFF}
        comments={[]}
        presence={[{ login: 'bob', avatarUrl: null, tabs: 1, file: 'src/Login.java', line: 12, typing: false }]}
      />,
    );
    expect(screen.getByTestId('focus-marker')).toHaveAttribute('title', 'bob looking here');
  });
});
