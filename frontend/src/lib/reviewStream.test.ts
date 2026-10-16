import { describe, expect, it } from 'vitest';
import {
  EMPTY_STREAM,
  appendToken,
  applyMessage,
  applyReplay,
  applyStatus,
  isNewSeq,
  mergeByKey,
  resetChunk,
} from './reviewStream';
import type { ReviewComment, ReviewStreamToken, StreamingComment } from '@/types/domain';

const token = (over: Partial<ReviewStreamToken> = {}): ReviewStreamToken => ({
  type: 'token',
  sessionId: 1,
  seq: 1,
  chunk: 0,
  key: 'k-a10',
  file: 'src/a.ts',
  line: 10,
  severity: 'WARNING',
  text: 'x',
  confidence: null,
  ruleKey: null,
  ...over,
});

const persistedComment = (over: Partial<ReviewComment> = {}): ReviewComment => ({
  id: 1,
  key: 'k-a10',
  file: 'src/a.ts',
  line: 10,
  severity: 'CRITICAL',
  text: 'persisted',
  codeSuggestion: null,
  ruleKey: 'local.secret',
  feedback: { up: 0, down: 1, myVote: -1 },
  apply: null,
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

describe('appendToken (grouped by fingerprint key)', () => {
  it('creates a new streaming comment for a new key', () => {
    const next = appendToken([], token({ text: 'Hello ' }));
    expect(next).toHaveLength(1);
    expect(next[0]).toMatchObject({ key: 'k-a10', file: 'src/a.ts', line: 10, text: 'Hello ', isStreaming: true });
  });

  it('accumulates text on the same key immutably', () => {
    const first = appendToken([], token({ text: 'Hello ' }));
    const second = appendToken(first, token({ seq: 2, text: 'world' }));
    expect(second[0].text).toBe('Hello world');
    expect(first[0].text).toBe('Hello ');
    expect(second).not.toBe(first);
  });

  it('keeps two findings on the same line apart when their keys differ', () => {
    const a = appendToken([], token({ key: 'k1', text: 'a' }));
    const b = appendToken(a, token({ seq: 2, key: 'k2', text: 'b' }));
    expect(b).toHaveLength(2);
  });
});

describe('resetChunk', () => {
  it('drops only the restarted chunk', () => {
    const s = appendToken(appendToken([], token({ key: 'a', chunk: 0 })), token({ seq: 2, key: 'b', chunk: 1 }));
    expect(resetChunk(s, 0).map((c) => c.key)).toEqual(['b']);
  });
});

describe('applyMessage (session-aware)', () => {
  it('applies tokens and marks the stream in progress', () => {
    const s = applyMessage(EMPTY_STREAM, token());
    expect(s).toMatchObject({ sessionId: 1, lastSeq: 1, status: 'IN_PROGRESS' });
    expect(s.comments).toHaveLength(1);
  });

  it('drops duplicates within a session', () => {
    const once = applyMessage(EMPTY_STREAM, token({ text: 'a' }));
    const twice = applyMessage(once, token({ text: 'a' }));
    expect(twice.comments[0].text).toBe('a');
  });

  it('a re-review (newer session) resets seq so its seq=1 is NOT dropped', () => {
    let s = applyMessage(EMPTY_STREAM, token({ seq: 1 }));
    s = applyMessage(s, token({ seq: 2, key: 'k2' }));
    s = applyMessage(s, token({ sessionId: 2, seq: 1, key: 'fresh', text: 'new run' }));
    expect(s.sessionId).toBe(2);
    expect(s.lastSeq).toBe(1);
    expect(s.comments.map((c) => c.key)).toEqual(['fresh']);
  });

  it('ignores messages from an older session', () => {
    const s = applyMessage(EMPTY_STREAM, token({ sessionId: 3 }));
    expect(applyMessage(s, token({ sessionId: 2, seq: 9, key: 'stale' }))).toBe(s);
  });

  it('applies a reset message by clearing that chunk', () => {
    let s = applyMessage(EMPTY_STREAM, token({ chunk: 0 }));
    s = applyMessage(s, { type: 'reset', sessionId: 1, seq: 2, chunk: 0 });
    expect(s.comments).toEqual([]);
    expect(s.lastSeq).toBe(2);
  });
});

describe('applyStatus', () => {
  it('marks comments finished on a terminal status', () => {
    const s = applyStatus(applyMessage(EMPTY_STREAM, token()), { sessionId: 1, status: 'COMPLETED', reason: null });
    expect(s.status).toBe('COMPLETED');
    expect(s.comments.every((c) => !c.isStreaming)).toBe(true);
  });
});

describe('applyReplay (reconnect catch-up)', () => {
  it('applies the batch, then buffered live messages, without duplicates', () => {
    const batch = {
      sessionId: 1,
      status: null,
      reason: null,
      items: [token({ seq: 1, text: 'a' }), token({ seq: 2, text: 'b' })],
    };
    // seq 2 arrived live while the replay was in flight, seq 3 after it.
    const pending = [token({ seq: 3, text: 'c' }), token({ seq: 2, text: 'b' })];
    const s = applyReplay(EMPTY_STREAM, batch, pending);
    expect(s.lastSeq).toBe(3);
    expect(s.comments[0].text).toBe('abc');
  });

  it('adopts the status carried by the batch', () => {
    const s = applyReplay(EMPTY_STREAM, { sessionId: 4, status: 'COMPLETED', reason: null, items: [] }, []);
    expect(s).toMatchObject({ sessionId: 4, status: 'COMPLETED' });
  });
});

describe('mergeByKey (REST persisted + live stream)', () => {
  it('when not streaming, persisted wins and live-only findings are appended', () => {
    const live: StreamingComment[] = [
      { key: 'k-a10', file: 'src/a.ts', line: 10, severity: 'CRITICAL', text: 'live', isStreaming: false },
      { key: 'other', file: 'src/b.ts', line: 3, severity: 'WARNING', text: 'live-only', isStreaming: false },
    ];
    const merged = mergeByKey([persistedComment()], live, false);
    expect(merged.map((c) => c.text)).toEqual(['persisted', 'live-only']);
    expect(merged[0].id).toBe(1);
  });

  it('while streaming, live is authoritative but keeps the persisted id and my vote', () => {
    const live: StreamingComment[] = [
      { key: 'k-a10', file: 'src/a.ts', line: 10, severity: 'CRITICAL', text: 'fresh', isStreaming: true },
    ];
    const merged = mergeByKey([persistedComment(), persistedComment({ id: 2, key: 'gone' })], live, true);
    expect(merged).toHaveLength(1);
    expect(merged[0]).toMatchObject({ text: 'fresh', id: 1, isStreaming: true });
    expect(merged[0].feedback?.myVote).toBe(-1);
  });
});
