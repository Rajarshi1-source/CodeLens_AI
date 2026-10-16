import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InlineComment } from './InlineComment';
import { renderWithProviders } from '@/test/render';
import { PrId, type StreamingComment } from '@/types/domain';

const prId = PrId(7);

const persisted = (over: Partial<StreamingComment> = {}): StreamingComment => ({
  key: 'k1',
  id: 42,
  file: 'src/a.ts',
  line: 3,
  severity: 'WARNING',
  text: 'Use a parameterized query',
  codeSuggestion: 'db.query("SELECT * FROM t WHERE id = ?", [id])',
  ruleKey: 'local.sql-injection',
  feedback: { up: 1, down: 0, myVote: null },
  apply: null,
  isStreaming: false,
  ...over,
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => vi.unstubAllGlobals());

describe('InlineComment actions', () => {
  it('shows the suggestion and exposes vote state with aria-pressed', () => {
    renderWithProviders(
      <InlineComment comment={persisted({ feedback: { up: 1, down: 2, myVote: -1 } })} prId={prId} />,
    );
    expect(screen.getByText(/parameterized query/)).toBeInTheDocument();
    expect(screen.getByText(/SELECT \* FROM t/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not helpful' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Helpful' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('hides feedback and apply while the comment is still streaming / unsaved', () => {
    renderWithProviders(<InlineComment comment={persisted({ id: undefined, isStreaming: true })} prId={prId} />);
    expect(screen.queryByTestId('comment-feedback')).not.toBeInTheDocument();
    expect(screen.queryByTestId('apply-suggestion')).not.toBeInTheDocument();
  });

  it('PUTs a vote', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ up: 2, down: 0, myVote: 1 }));
    vi.stubGlobal('fetch', fetchMock);
    renderWithProviders(<InlineComment comment={persisted()} prId={prId} />);
    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/comments/42/feedback');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(String(init.body))).toEqual({ vote: 1 });
  });

  it('POSTs apply', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ state: 'APPLIED', githubCommentId: 1, htmlUrl: 'https://github.test/c/1' }));
    vi.stubGlobal('fetch', fetchMock);
    renderWithProviders(<InlineComment comment={persisted()} prId={prId} />);
    await userEvent.click(screen.getByTestId('apply-suggestion'));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('/api/comments/42/apply', expect.objectContaining({ method: 'POST' })),
    );
  });

  it('renders the Applied link for an already-applied suggestion', () => {
    renderWithProviders(
      <InlineComment comment={persisted({ apply: { state: 'APPLIED', htmlUrl: 'https://github.test/c/1' } })} prId={prId} />,
    );
    expect(screen.getByTestId('applied-link')).toHaveAttribute('href', 'https://github.test/c/1');
  });
});
