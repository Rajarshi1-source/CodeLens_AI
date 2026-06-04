import type { ReviewComment, ReviewStreamToken, StreamingComment } from '@/types/domain';

const anchorKey = (file: string, line: number) => `${file}:${line}`;

/** A token is new (should be applied) only if its seq is strictly greater than the last seen seq. */
export function isNewSeq(lastSeq: number, seq: number): boolean {
  return seq > lastSeq;
}

/**
 * Append a streaming token to the comment list, keyed by (file, line).
 * A new anchor starts a fresh streaming comment; an existing one accumulates text immutably.
 */
export function appendToken(prev: StreamingComment[], token: ReviewStreamToken): StreamingComment[] {
  const i = prev.findIndex((c) => c.file === token.file && c.line === token.line);
  if (i === -1) {
    return [
      ...prev,
      {
        file: token.file,
        line: token.line,
        severity: token.severity,
        text: token.text,
        isStreaming: true,
      },
    ];
  }
  const next = [...prev];
  next[i] = { ...next[i], text: next[i].text + token.text };
  return next;
}

/**
 * Merge already-persisted REST comments with live-streamed ones, keyed by (file, line).
 * A live comment supersedes a persisted one at the same anchor (it's the same comment, fresher).
 */
export function mergeByAnchor(
  persisted: ReviewComment[],
  live: StreamingComment[],
): StreamingComment[] {
  const byAnchor = new Map<string, StreamingComment>();
  for (const c of persisted) {
    byAnchor.set(anchorKey(c.file, c.line), {
      file: c.file,
      line: c.line,
      severity: c.severity,
      text: c.text,
      confidence: c.confidence,
      isStreaming: false,
    });
  }
  for (const c of live) {
    byAnchor.set(anchorKey(c.file, c.line), c);
  }
  return [...byAnchor.values()];
}
