# CodeLens AI — React Streaming Reference

The full real-time layer: the STOMP client lifecycle, the streaming-comment hook, reconnect + replay, and combining REST (already-persisted comments) with live WebSocket. Load when building or debugging the streaming UI. SKILL.md has the core hook; this adds the provider, reconnect, and merge logic.

## Contents
1. STOMP client provider (single instance)
2. The streaming hook (with replay guard)
3. Reconnect + resumable replay
4. Combining REST + WebSocket on PR open
5. Performance: transitions and batching
6. Common failure modes

## 1. STOMP client provider (single instance)

Create exactly one client for the app and share it via context. Never instantiate per component or per render.

```tsx
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { createContext, useContext, useEffect, useRef } from 'react';

const StompContext = createContext<Client | null>(null);

export function StompProvider({ children }: { children: React.ReactNode }) {
  const clientRef = useRef<Client | null>(null);

  if (!clientRef.current) {
    clientRef.current = new Client({
      webSocketFactory: () => new SockJS(import.meta.env.VITE_WS_URL),
      reconnectDelay: 2000,            // stompjs handles backoff/retry
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
    });
  }

  useEffect(() => {
    const client = clientRef.current!;
    client.activate();
    return () => { void client.deactivate(); };
  }, []);

  return <StompContext.Provider value={clientRef.current}>{children}</StompContext.Provider>;
}

export function useStompClient(): Client {
  const client = useContext(StompContext);
  if (!client) throw new Error('useStompClient must be used within StompProvider');
  return client;
}
```

## 2. The streaming hook (with replay guard)

```ts
export function useReviewStream(prId: PrId) {
  const client = useStompClient();
  const [comments, setComments] = useState<StreamingComment[]>([]);
  const [status, setStatus] = useState<ReviewStatus>('PENDING');
  const lastSeq = useRef(0);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!client.connected) return;   // re-run when connection flips (add client.connected to deps via a small subscription)

    const reviewSub = client.subscribe(`/topic/pr/${prId}/review`, (message) => {
      const token: ReviewStreamToken = JSON.parse(message.body);
      if (token.seq <= lastSeq.current) return;     // drop replays / dupes
      lastSeq.current = token.seq;

      startTransition(() => {
        setComments((prev) => appendToken(prev, token));
      });
    });

    const statusSub = client.subscribe(`/topic/pr/${prId}/status`, (m) => {
      const next = m.body as ReviewStatus;
      setStatus(next);
      if (next === 'COMPLETED') {
        setComments((prev) => prev.map((c) => ({ ...c, isStreaming: false })));
      }
    });

    return () => { reviewSub.unsubscribe(); statusSub.unsubscribe(); };
  }, [client, prId]);

  return { comments, status };
}

function appendToken(prev: StreamingComment[], token: ReviewStreamToken): StreamingComment[] {
  const i = prev.findIndex((c) => c.file === token.file && c.line === token.line);
  if (i === -1) {
    return [...prev, {
      file: token.file, line: token.line, severity: token.severity,
      text: token.text, isStreaming: true,
    }];
  }
  const next = [...prev];
  next[i] = { ...next[i], text: next[i].text + token.text };   // immutable append
  return next;
}
```

## 3. Reconnect + resumable replay

The backend numbers comments per session with `seq`. On reconnect, tell the server the last `seq` you saw so it replays only what you missed, then resumes live push.

```ts
// On (re)subscribe, send the resume point. The backend reads this header and replays seq > N.
const reviewSub = client.subscribe(
  `/topic/pr/${prId}/review`,
  handler,
  { 'resume-from-seq': String(lastSeq.current) },   // STOMP subscription headers
);
```

Because the hook keeps `lastSeq` in a ref (survives re-renders) and the append handler dedupes on `seq`, replayed comments merge cleanly without duplication and without wiping local state.

## 4. Combining REST + WebSocket on PR open

When you open a PR whose review is already in progress (or finished), seed from REST first, then let WebSocket carry the rest:

```ts
export function usePrReview(prId: PrId) {
  // 1. Persisted comments (React Query) — instant render of what's already done.
  const persisted = useQuery({
    queryKey: ['comments', prId],
    queryFn: () => prService.comments(prId),
  });

  // 2. Live stream — remaining tokens.
  const { comments: live, status } = useReviewStream(prId);

  // 3. Merge by (file, line), preferring live (it carries the freshest text).
  const merged = useMemo(
    () => mergeByAnchor(persisted.data ?? [], live),
    [persisted.data, live],
  );
  return { comments: merged, status };
}
```

`mergeByAnchor` keys on `(file, line)`: a live comment supersedes a persisted one with the same anchor (it's the same comment still streaming), otherwise both are kept.

## 5. Performance: transitions and batching

- Wrap token appends in `startTransition` so a burst of tokens doesn't block typing/scrolling.
- For very high token rates, debounce flushes (accumulate fragments for ~30–50ms, then apply) to cut render churn.
- Virtualize long comment lists / large diffs if a PR has hundreds of comments.
- Keep the diff viewer and the comment rail as separate components so a comment update doesn't re-render the whole diff.

## 6. Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Comments duplicate as they stream | missing `(file,line)` key or `seq` dedup | append by anchor; drop `seq <= lastSeq` |
| UI freezes during fast streams | urgent state updates per token | wrap in `startTransition`, debounce |
| Client on another pod gets nothing | backend Redis Pub/Sub relay not wired | server-side fix (see Spring resilience ref) |
| State wiped on reconnect | local state reset instead of merged | keep `lastSeq` ref, merge replayed comments |
| Stream never marks "done" | provider didn't emit completion / no COMPLETED status | flip `isStreaming` on `COMPLETED` status msg |
