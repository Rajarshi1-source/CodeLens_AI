import type {
  ReplayBatch,
  ReviewComment,
  ReviewStatus,
  ReviewStatusMessage,
  ReviewStreamToken,
  ReviewTopicMessage,
  StreamingComment,
} from '@/types/domain';

/**
 * Pure state machine for the live review stream. Everything is keyed by the review session:
 *  - a message from a NEWER session (re-review) resets the comments and the sequence;
 *  - a message from an OLDER session is ignored;
 *  - within a session, only strictly increasing `seq` is applied (replays / duplicates are dropped).
 */
export interface StreamState {
  sessionId: number | null;
  lastSeq: number;
  status: ReviewStatus | null;
  reason: string | null;
  comments: StreamingComment[];
}

export const EMPTY_STREAM: StreamState = { sessionId: null, lastSeq: 0, status: null, reason: null, comments: [] };

/** A message is new (should be applied) only if its seq is strictly greater than the last seen seq. */
export function isNewSeq(lastSeq: number, seq: number): boolean {
  return seq > lastSeq;
}

/** Align the state with `sessionId`: reset for a newer session; returns null for a stale one. */
function forSession(state: StreamState, sessionId: number): StreamState | null {
  if (state.sessionId === null || sessionId > state.sessionId) {
    return { ...EMPTY_STREAM, sessionId };
  }
  if (sessionId < state.sessionId) return null;
  return state;
}

/** Append a streamed fragment to its finding (grouped by fingerprint `key`), immutably. */
export function appendToken(prev: StreamingComment[], token: ReviewStreamToken): StreamingComment[] {
  const i = prev.findIndex((c) => c.key === token.key);
  if (i === -1) {
    return [
      ...prev,
      {
        key: token.key,
        chunk: token.chunk,
        file: token.file,
        line: token.line,
        severity: token.severity,
        text: token.text,
        confidence: token.confidence ?? undefined,
        ruleKey: token.ruleKey,
        isStreaming: true,
      },
    ];
  }
  const next = [...prev];
  next[i] = { ...next[i], text: next[i].text + token.text };
  return next;
}

/** A retry / fallback restarted one chunk: drop that chunk's streamed text. */
export function resetChunk(prev: StreamingComment[], chunk: number): StreamingComment[] {
  return prev.filter((c) => c.chunk !== chunk);
}

/** Apply one review-topic message. Errors are surfaced by the caller; here they only advance seq. */
export function applyMessage(state: StreamState, msg: ReviewTopicMessage): StreamState {
  const s = forSession(state, msg.sessionId);
  if (!s || !isNewSeq(s.lastSeq, msg.seq)) return s ?? state;
  switch (msg.type) {
    case 'token':
      return { ...s, lastSeq: msg.seq, status: s.status ?? 'IN_PROGRESS', comments: appendToken(s.comments, msg) };
    case 'reset':
      return { ...s, lastSeq: msg.seq, comments: resetChunk(s.comments, msg.chunk) };
    case 'error':
      return { ...s, lastSeq: msg.seq };
  }
}

export function applyStatus(state: StreamState, msg: ReviewStatusMessage): StreamState {
  const s = forSession(state, msg.sessionId);
  if (!s) return state;
  const done = msg.status !== 'IN_PROGRESS' && msg.status !== 'PENDING';
  return {
    ...s,
    status: msg.status,
    reason: msg.reason,
    comments: done ? s.comments.map((c) => ({ ...c, isStreaming: false })) : s.comments,
  };
}

/**
 * Apply a replay batch, then the live messages that arrived while it was in flight (buffered by the
 * caller), in seq order. Duplicates between the two are dropped by the seq guard.
 */
export function applyReplay(state: StreamState, batch: ReplayBatch, pending: ReviewTopicMessage[]): StreamState {
  let s = forSession(state, batch.sessionId) ?? state;
  if (s.sessionId !== batch.sessionId) return state;
  for (const item of batch.items) s = applyMessage(s, item);
  for (const item of [...pending].sort((a, b) => a.seq - b.seq)) s = applyMessage(s, item);
  if (batch.status) {
    s = applyStatus(s, { sessionId: batch.sessionId, status: batch.status, reason: batch.reason });
  }
  return s;
}

/** Persisted (REST) comment → render model. */
export function fromPersisted(c: ReviewComment): StreamingComment {
  return {
    key: c.key,
    id: c.id,
    file: c.file,
    line: c.line,
    severity: c.severity,
    text: c.text,
    confidence: c.confidence,
    ruleKey: c.ruleKey,
    codeSuggestion: c.codeSuggestion,
    feedback: c.feedback,
    apply: c.apply,
    isStreaming: false,
  };
}

/**
 * Merge persisted comments with live-streamed ones on the finding fingerprint (`key`). While a review is
 * streaming the live set is authoritative (old persisted comments belong to the previous run) but keeps
 * the persisted id / feedback / apply state of the same finding; otherwise persisted comments win and
 * live-only findings are appended.
 */
export function mergeByKey(
  persisted: ReviewComment[],
  live: StreamingComment[],
  liveIsAuthoritative: boolean,
): StreamingComment[] {
  const byKey = new Map(persisted.map((c) => [c.key, fromPersisted(c)] as const));
  if (liveIsAuthoritative) {
    return live.map((c) => {
      const p = byKey.get(c.key);
      return p ? { ...c, id: p.id, feedback: p.feedback, apply: p.apply, codeSuggestion: p.codeSuggestion } : c;
    });
  }
  const out = [...byKey.values()];
  for (const c of live) {
    if (!byKey.has(c.key)) out.push(c);
  }
  return out;
}
