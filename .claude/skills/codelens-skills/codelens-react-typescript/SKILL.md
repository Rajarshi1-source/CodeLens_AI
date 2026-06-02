---
name: codelens-react-typescript
description: React 18 + TypeScript specialist for the CodeLens AI frontend. Use this skill whenever building or editing any frontend code in the CodeLens AI code-review platform — .tsx/.ts files, components, hooks, the STOMP/WebSocket streaming layer, the diff viewer, streaming-comment rendering, PR list/dashboard, Zustand stores, React Query data fetching, or TypeScript types. Trigger this even when the user only says "the frontend," "the diff view," "the streaming hook," "the PR list," "the dashboard," or names a component/hook — all client-side logic in CodeLens AI follows these conventions and should use this skill. Visual styling (Tailwind classes, shadcn/ui components, theming) lives in codelens-tailwind-shadcn; use both for UI work.
---

# CodeLens AI — React 18 + TypeScript

You are building the **CodeLens AI** frontend: a React 18 + TypeScript SPA (Vite) that renders GitHub PR diffs and shows AI review comments **streaming in word-by-word** over WebSocket, exactly like Copilot. This skill covers component architecture, hooks, the real-time streaming layer, and strict typing. Styling conventions are in `codelens-tailwind-shadcn`.

## Reference files (load on demand)

This body is the working summary. Read the matching reference when you need full code:

| Reference | Load when |
|---|---|
| `references/streaming.md` | Building/debugging the STOMP client, the streaming hook, reconnect+replay, or merging REST with live WebSocket |
| `references/types-and-data.md` | Defining types, Zod boundary schemas, typed API services, React Query queries/mutations, read-your-writes |

## What the frontend does

- GitHub OAuth login → dashboard of repos and PRs.
- Open a PR → side-by-side diff (`react-diff-viewer-continued`) with inline AI comments.
- Comments **stream** over STOMP/WebSocket on `/topic/pr/{id}/review`, rendered token-by-token with a blinking cursor.
- Each comment carries a severity (CRITICAL / WARNING / SUGGESTION) and confidence.
- Status updates arrive on `/topic/pr/{id}/status`.

## Project structure (follow it)

```
src/
├── routes/      Login, Dashboard, RepoSettings, PRList, PRReview
├── components/
│   ├── diff/    DiffViewer, InlineComment, SeverityBadge, StreamingText
│   ├── pr/      PRCard, PRStatusBadge, PRFilters
│   ├── dashboard/ StatsCard, SeverityChart, ReviewTimeline
│   └── common/  LoadingSpinner, ErrorBoundary, EmptyState
├── hooks/       useWebSocket, useReviewStream, useAuth, usePullRequests
├── services/    api, authService, prService, websocketService
├── store/       Zustand slices
└── types/       shared domain types
```

## Strict typing — the domain model

Model the wire protocol exactly. Use **discriminated unions** for anything with variants and a **branded** type for IDs so a PR id can't be passed where a repo id is expected. Full catalog (all domain types, the `Async<T>` union, Zod schemas, typed services) is in `references/types-and-data.md`.

```ts
export type Severity = 'CRITICAL' | 'WARNING' | 'SUGGESTION';
export type ReviewStatus =
  | 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'REVIEW_FAILED' | 'REVIEW_UNAVAILABLE';

export type PrId = string & { readonly __brand: 'PrId' };

export interface ReviewStreamToken {       // pushed over WebSocket as the LLM streams
  seq: number;                             // per-session sequence → resumable stream
  file: string; line: number; severity: Severity; text: string;
}

export interface StreamingComment {        // accumulates in the UI
  file: string; line: number; severity: Severity;
  text: string; confidence?: number; isStreaming: boolean;
}
```

Render discriminated unions exhaustively with a `never` guard so adding a status that isn't handled fails the build (pattern in the reference).

## Data fetching — React Query + typed services

REST goes through React Query (`@tanstack/react-query`); the WebSocket handles live streaming — keep them separate, don't fetch in raw `useEffect`. Validate every response at the boundary with Zod (schemas in the reference) so malformed payloads fail loudly and typed. After a re-review mutation, invalidate `['prs', repoId]` and the PR's comments query so the user sees their own write immediately (read-your-writes).

## React 18 conventions

- **Functional components + hooks only.** No class components except a single `ErrorBoundary`.
- Mark non-urgent updates (e.g. high-frequency streaming token appends) with `useTransition` / `startTransition` so typing and scrolling stay responsive.
- Use `useId` for accessible label/input associations.
- Keep `StrictMode` on; write effects that are safe to run twice in dev.
- Components stay **under ~150 lines** and single-responsibility — split a monolith into composed units rather than growing it.
- Props-first design: derive from props/state, lift state only as far as needed.

## The streaming comment hook (core of the product)

Subscribe to the PR topic, accumulate tokens into comments keyed by `(file, line)`, track status, and guard against replays with the `seq` number. Batch rapid appends with a transition so the diff doesn't thrash. The full hook plus the shared STOMP provider, reconnect/replay, and the REST+WebSocket merge are in `references/streaming.md`.

```ts
export function useReviewStream(prId: PrId) {
  const client = useStompClient();
  const [comments, setComments] = useState<StreamingComment[]>([]);
  const [status, setStatus] = useState<ReviewStatus>('PENDING');
  const lastSeq = useRef(0);
  const [, startTransition] = useTransition();

  useEffect(() => {
    const sub = client.subscribe(`/topic/pr/${prId}/review`, (message) => {
      const token: ReviewStreamToken = JSON.parse(message.body);
      if (token.seq <= lastSeq.current) return;       // drop replays / out-of-order dupes
      lastSeq.current = token.seq;
      startTransition(() => setComments((prev) => appendByAnchor(prev, token)));
    });
    return () => sub.unsubscribe();
  }, [client, prId]);

  return { comments, status };
}
```

`appendByAnchor` finds the comment with the same `(file, line)` and appends the fragment immutably, or pushes a new streaming comment.

## State management — Zustand

Use Zustand for cross-route client state (auth/user, UI filters), React Query for server cache, local `useState` for component state. Don't put server data in Zustand.

## MUST DO
- Use discriminated unions for status/variant state and render them exhaustively.
- Key streaming comments on `(file, line)`; append fragments immutably.
- Guard against replayed/out-of-order tokens with the `seq` number.
- Hold the STOMP client in a ref; clean up subscriptions in the effect return.
- Use React Query for REST, WebSocket for streaming; keep them separate.
- Keep components under ~150 lines, single-responsibility, props-first.

## MUST NOT DO
- Use `any` or non-null assertions to silence the type checker — model the type instead.
- Recreate the WebSocket/STOMP client on every render.
- Mutate state arrays/objects in place.
- Use HTML `<form>` submit semantics for the streaming actions; use `onClick`/`onChange` handlers.
- Store server-fetched lists in Zustand (that's React Query's job).
- Render every token as an urgent update without a transition (causes jank during fast streams).

## Troubleshooting
- **Comments duplicate as they stream**: the `(file, line)` key or `seq` dedup is missing — fragments are creating new comments instead of appending.
- **UI freezes during fast streaming**: wrap the `setComments` append in `startTransition`.
- **Stale comments after reconnect**: send `lastSeq` on reconnect and merge replayed comments; don't blow away local state.
- **`PrId`/`repoId` mixed up**: the branded type is doing its job — pass the correct id, don't cast.
- **Diff line numbers don't line up with comments**: backend validates `(file, line)` against hunks; surface only validated comments.
