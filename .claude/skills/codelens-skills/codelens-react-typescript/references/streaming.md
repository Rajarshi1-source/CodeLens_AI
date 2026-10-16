# CodeLens AI — React Streaming Reference

The full real-time layer: the STOMP client lifecycle, the session-aware stream state machine, reconnect + replay,
combining REST (persisted comments) with live WebSocket, and presence. Load when building or debugging the streaming
UI. Backend side: `codelens-spring-boot/references/presence-and-resume.md`.

## Contents
1. STOMP client provider (single instance, `connected` flag)
2. The pure stream state machine (`lib/reviewStream.ts`)
3. The hook: subscribe, buffer, replay, go live
4. Combining REST + WebSocket (`usePrReview`, `mergeByKey`)
5. Presence (`usePresence`)
6. Performance
7. Common failure modes

## 1. STOMP client provider

One client for the app, shared via context together with a live `connected` flag (subscriptions are made in
effects that depend on it, so they are re-created after every reconnect). The SockJS handshake carries the session
cookie, which is how the backend authenticates CONNECT.

```tsx
export function StompProvider({ children }: { children: ReactNode }) {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = useState(false);
  if (!clientRef.current) {
    clientRef.current = new Client({
      webSocketFactory: () => new SockJS(import.meta.env.VITE_WS_URL ?? '/ws'),
      reconnectDelay: 2000, heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
    });
  }
  useEffect(() => {
    const client = clientRef.current!;
    client.onConnect = () => setConnected(true);
    client.onWebSocketClose = () => setConnected(false);
    client.onDisconnect = () => setConnected(false);
    client.activate();
    return () => { void client.deactivate(); };
  }, []);
  return <StompContext.Provider value={{ client: clientRef.current, connected }}>{children}</StompContext.Provider>;
}
```

## 2. The pure stream state machine

```ts
export interface StreamState {
  sessionId: number | null; lastSeq: number; status: ReviewStatus | null; reason: string | null;
  comments: StreamingComment[];
}

/** Align with `sessionId`: a NEWER session resets everything (re-review restarts seq at 1); older → ignore. */
function forSession(state: StreamState, sessionId: number): StreamState | null {
  if (state.sessionId === null || sessionId > state.sessionId) return { ...EMPTY_STREAM, sessionId };
  if (sessionId < state.sessionId) return null;
  return state;
}

export function applyMessage(state: StreamState, msg: ReviewTopicMessage): StreamState {
  const s = forSession(state, msg.sessionId);
  if (!s || !isNewSeq(s.lastSeq, msg.seq)) return s ?? state;          // dedup by seq
  switch (msg.type) {
    case 'token': return { ...s, lastSeq: msg.seq, status: s.status ?? 'IN_PROGRESS', comments: appendToken(s.comments, msg) };
    case 'reset': return { ...s, lastSeq: msg.seq, comments: resetChunk(s.comments, msg.chunk) };
    case 'error': return { ...s, lastSeq: msg.seq };
  }
}

/** Append by finding key (fingerprint), immutably. */
export function appendToken(prev: StreamingComment[], t: ReviewStreamToken): StreamingComment[] {
  const i = prev.findIndex((c) => c.key === t.key);
  if (i === -1) return [...prev, { key: t.key, chunk: t.chunk, file: t.file, line: t.line, severity: t.severity,
                                   text: t.text, confidence: t.confidence ?? undefined, ruleKey: t.ruleKey, isStreaming: true }];
  const next = [...prev];
  next[i] = { ...next[i], text: next[i].text + t.text };
  return next;
}

/** Replay batch first, then the live messages buffered while it was in flight, in seq order. */
export function applyReplay(state: StreamState, batch: ReplayBatch, pending: ReviewTopicMessage[]): StreamState {
  let s = forSession(state, batch.sessionId) ?? state;
  if (s.sessionId !== batch.sessionId) return state;
  for (const item of batch.items) s = applyMessage(s, item);
  for (const item of [...pending].sort((a, b) => a.seq - b.seq)) s = applyMessage(s, item);
  if (batch.status) s = applyStatus(s, { sessionId: batch.sessionId, status: batch.status, reason: batch.reason });
  return s;
}
```

`applyStatus` sets status/reason and clears `isStreaming` on terminal statuses. Everything here is unit-tested in
`reviewStream.test.ts` (newer-session reset, older-session ignore, reset per chunk, replay + buffered dedup).

## 3. The hook

```ts
export function useReviewStream(prId: PrId): StreamState {
  const { client, connected } = useWebSocket();
  const [state, setState] = useState<StreamState>(EMPTY_STREAM);
  const stateRef = useRef<StreamState>(EMPTY_STREAM);          // latest state for headers/handlers
  const [, startTransition] = useTransition();
  const update = (fn: (s: StreamState) => StreamState) => {
    stateRef.current = fn(stateRef.current);
    const next = stateRef.current;
    startTransition(() => setState(next));
  };

  useEffect(() => {
    if (!connected) return;
    let replaying = true;
    const pending: ReviewTopicMessage[] = [];
    const reviewSub = client.subscribe(`/topic/pr/${prId}/review`, (m) => {
      const parsed = ReviewTopicMessageSchema.safeParse(JSON.parse(m.body));
      if (!parsed.success) return;
      if (replaying) pending.push(parsed.data); else update((s) => applyMessage(s, parsed.data));
    });
    const statusSub = client.subscribe(`/topic/pr/${prId}/status`, (m) => {
      const parsed = ReviewStatusMessageSchema.safeParse(JSON.parse(m.body));
      if (parsed.success) update((s) => applyStatus(s, parsed.data));
    });
    const { sessionId, lastSeq } = stateRef.current;
    const replaySub = client.subscribe(`/app/pr/${prId}/replay`, (m) => {
      replaying = false;
      // validate with ReplayBatchSchema + ReviewTopicMessageSchema per item, then:
      update((s) => applyReplay(s, batch, pending));
      pending.length = 0;
      replaySub.unsubscribe();                                   // the replay answers exactly once
    }, { 'after-seq': String(lastSeq), 'session-id': String(sessionId ?? 0) });
    return () => { reviewSub.unsubscribe(); statusSub.unsubscribe(); try { replaySub.unsubscribe(); } catch { /* done */ } };
  }, [client, connected, prId]);

  return state;
}
```

Subscribing to the topics **before** requesting the replay, and buffering until it arrives, closes the gap where an
event published between the two would otherwise be lost.

## 4. Combining REST + WebSocket

```ts
export function usePrReview(prId: PrId) {
  const qc = useQueryClient();
  const persisted = useQuery({ queryKey: ['comments', prId], queryFn: () => prService.comments(prId) });
  const stream = useReviewStream(prId);

  // When a live review finishes, refetch so persisted ids/feedback/apply state replace live comments.
  const last = useRef(stream.status);
  useEffect(() => {
    const was = last.current; last.current = stream.status;
    if (was === 'IN_PROGRESS' && stream.status && stream.status !== 'IN_PROGRESS') {
      for (const key of [['comments', prId], ['pr', prId], ['summary', prId], ['diff', prId], ['prs']])
        void qc.invalidateQueries({ queryKey: key });
    }
  }, [stream.status, prId, qc]);

  const comments = useMemo(
    () => mergeByKey(persisted.data ?? [], stream.comments, stream.status === 'IN_PROGRESS'),
    [persisted.data, stream.comments, stream.status],
  );
  return { comments, status: stream.status, reason: stream.reason, sessionId: stream.sessionId, isLoading: persisted.isLoading };
}
```

`mergeByKey`: while streaming, the live set is authoritative (old persisted comments belong to the previous run) but
each live comment keeps the persisted `id`, `feedback`, `apply` and `codeSuggestion` of the same key; otherwise
persisted comments win and live-only ones are appended.

## 5. Presence

`usePresence(prId, selfLogin)` subscribes to `/topic/pr/{id}/presence` (Zod `PresenceSnapshotSchema`), publishes
`JOIN` on connect, `HEARTBEAT` every 15 s, throttled `FOCUS` (250 ms) as the pointer moves over diff rows, and
`LEAVE` on unmount/`beforeunload`. `collapseViewers` merges one row per STOMP session into one entry per login
(`tabs` count); `others` excludes the current user. `DiffViewer` passes each file's focus map to `DiffFileTable`,
which shows a gutter marker (`data-testid="focus-marker"`) with the viewers' avatars.

## 6. Performance

- All stream updates go through `startTransition`.
- `DiffFileTable` is per file and memoizes its line → comments map; large files (> 1500 rows) start collapsed.
- The comment rail and the diff are siblings, so a comment update does not re-render unrelated files' tables.

## 7. Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Re-review shows nothing live | `lastSeq` kept from the previous session | reset on a newer `sessionId` (`forSession`) |
| Duplicates after reconnect | live events not buffered during replay | buffer until the replay reply, then `applyReplay` |
| Two findings on one line merge into one | keyed by `(file, line)` | key by the fingerprint `key` |
| Retry shows stale partial text | `reset` messages ignored | `resetChunk` on `type: 'reset'` |
| Feedback/apply buttons missing after completion | queries not invalidated | invalidate comments/pr/summary/diff when status leaves `IN_PROGRESS` |
| Client on another pod gets nothing | backend Redis Pub/Sub relay not wired | server-side fix |
