import { describe, expect, it } from 'vitest';
import { appendToken, isNewSeq, mergeByAnchor } from './reviewStream';
import type { ReviewComment, ReviewStreamToken, StreamingComment } from '@/types/domain';

const token = (over: Partial<ReviewStreamToken>): ReviewStreamToken => ({
  seq: 1,
  file: 'src/a.ts',
  line: 10,
  severity: 'WARNING',
  text: 'x',
  ...over,
});

describe('isNewSeq (replay/dedup guard)', () => {
  it('accepts strictly increasing seq', () => {
    expect(isNewSeq(0, 1)).toBe(true);
    expect(isNewSeq(5, 6)).toBe(true);
  });

  it('rejects replayed or duplicate seq', () => {
    expect(isNewSeq(5, 5)).toBe(false);
    expect(isNewSeq(5, 3)).toBe(false);
  });
});

describe('appendToken (append by (file,line) anchor)', () => {
  it('creates a new streaming comment for a new anchor', () => {
    const next = appendToken([], token({ text: 'Hello ' }));
    expect(next).toHaveLength(1);
    expect(next[0]).toMatchObject({ file: 'src/a.ts', line: 10, text: 'Hello ', isStreaming: true });
  });

  it('accumulates text on the same anchor immutably', () => {
    const first = appendToken([], token({ text: 'Hello ' }));
    const second = appendToken(first, token({ seq: 2, text: 'world' }));
    expect(second[0].text).toBe('Hello world');
    expect(first[0].text).toBe('Hello '); // original not mutated
    expect(second).not.toBe(first);
  });

  it('keeps separate comments for different anchors', () => {
    const a = appendToken([], token({ line: 10, text: 'a' }));
    const b = appendToken(a, token({ seq: 2, line: 20, text: 'b' }));
    expect(b).toHaveLength(2);
  });
});

describe('mergeByAnchor (REST persisted + live stream)', () => {
  const persisted: ReviewComment[] = [
    { file: 'src/a.ts', line: 10, severity: 'CRITICAL', text: 'persisted' },
    { file: 'src/b.ts', line: 5, severity: 'SUGGESTION', text: 'only-persisted' },
  ];

  it('lets a live comment supersede a persisted one at the same anchor', () => {
    const live: StreamingComment[] = [
      { file: 'src/a.ts', line: 10, severity: 'CRITICAL', text: 'live-fresh', isStreaming: true },
    ];
    const merged = mergeByAnchor(persisted, live);
    const a = merged.find((c) => c.file === 'src/a.ts' && c.line === 10);
    expect(a?.text).toBe('live-fresh');
    expect(a?.isStreaming).toBe(true);
  });

  it('keeps persisted comments that have no live counterpart', () => {
    const merged = mergeByAnchor(persisted, []);
    expect(merged).toHaveLength(2);
    expect(merged.every((c) => c.isStreaming === false)).toBe(true);
  });
});
