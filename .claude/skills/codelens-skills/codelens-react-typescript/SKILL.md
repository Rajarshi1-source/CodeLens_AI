---
name: codelens-react-typescript
description: React 18 + TypeScript specialist for the CodeLens AI frontend. Use this skill whenever building or editing any frontend code in the CodeLens AI code-review platform — .tsx/.ts files, components, hooks, the STOMP/WebSocket streaming layer (session-aware, resumable), the inline diff renderer, risk/cost panels, feedback and apply-suggestion actions, presence, the PR list/dashboard, Zustand stores, React Query data fetching, Zod schemas, Vitest or Playwright tests. Trigger this even when the user only says "the frontend," "the diff view," "the streaming hook," "the PR list," "the dashboard," "presence," "the E2E tests," or names a component/hook — all client-side logic in CodeLens AI follows these conventions and should use this skill. Visual styling (Tailwind classes, shadcn/ui components, theming) lives in codelens-tailwind-shadcn; use both for UI work.
---

# CodeLens AI — React 18 + TypeScript

You are building the **CodeLens AI** frontend: a React 18 + TypeScript SPA (Vite 7) that renders a PR's diff with every AI finding **inline under its line**, streams findings live over STOMP/WebSocket, shows the PR's risk and the review's cost, lets reviewers vote on and apply suggestions, and shows who else is looking at the PR. This skill covers component architecture, hooks, the real-time layer, strict typing and testing. Styling conventions are in `codelens-tailwind-shadcn`.

## Reference files (load on demand)

| Reference | Load when |
|---|---|
| `references/streaming.md` | The STOMP provider, `useReviewStream` (session/seq/replay), merging REST with live, presence |
| `references/types-and-data.md` | Domain types, Zod boundary schemas, typed services, React Query queries/mutations, optimistic updates |

## What the frontend does

- GitHub OAuth login, or "Try the demo" (Alice/Bob) when `GET /api/auth/config` reports the demo profile.
- Dashboard: totals, findings by severity, **PRs by risk**, review coverage, **LLM budget and spend**.
- PR list with a **risk badge** column and risk/status/search filters.
- PR review: our own `DiffFileTable` (split or unified) with **absolute line numbers** from the hunk headers and each
  finding rendered in a row **directly after its line**; findings outside the diff under "Other findings"; a
  findings list when there is no diff at all. Comment rail, Summary tab (`RiskPanel`, `CostPanel`), Comments tab.
- Live: findings stream on `/topic/pr/{id}/review`, status on `/topic/pr/{id}/status`; reconnecting clients replay
  what they missed; presence avatars and focus markers on `/topic/pr/{id}/presence`.
- Actions on persisted findings: thumbs up/down (optimistic, `aria-pressed`) and **Apply suggestion**.
- Repositories page: connect/disconnect, retrieval-index status, **Re-index** (polls while indexing).

## Project structure (follow it)

```
src/
├── routes/      Login, Dashboard, RepoSettings, PRList, PRReview
├── components/
│   ├── diff/      DiffViewer, DiffFileTable, InlineComment, SuggestionBlock, CommentActions, CommentRail,
│   │              SeverityBadge, StreamingText
│   ├── pr/        PRStatusBadge, RiskBadge, PRFilters
│   ├── review/    RiskPanel, CostPanel
│   ├── presence/  PresenceAvatars
│   ├── dashboard/ StatsCard, SeverityChart, RiskDistributionChart, ReviewCoverage, BudgetCard
│   ├── layout/    AppShell, RequireAuth, ThemeToggle
│   └── common/    LoadingSpinner, ErrorBoundary, ErrorState, EmptyState
├── hooks/       useWebSocket, useReviewStream, usePrReview(+usePrSummary), usePresence, useCommentActions,
│                useAuth, usePullRequests, useRepos, useDashboard
├── lib/         reviewStream (pure stream state machine), parseDiff, risk, severity, format
├── services/    api, authService, prService, commentService, repoService, dashboardService
├── store/       uiStore (Zustand: theme, filters, diff mode)
├── types/       domain.ts, schemas.ts (Zod)
└── test/        setup.ts, render.tsx (providers for component tests)
e2e/             Playwright specs against the demo-profile backend
```

## Strict typing — the domain model

Model the wire protocol exactly; discriminated unions for variants, branded ids. Full catalog in
`references/types-and-data.md`.

```ts
export type Severity = 'CRITICAL' | 'WARNING' | 'SUGGESTION';
export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW';
export type PrId = string & { readonly __brand: 'PrId' };   // PrId(n) at the boundary

/** /topic/pr/{id}/review — discriminated on `type`. */
export type ReviewTopicMessage = ReviewStreamToken | ReviewResetMessage | ReviewErrorMessage;
export interface ReviewStreamToken {
  type: 'token'; sessionId: number; seq: number; chunk: number;
  key: string;                       // finding fingerprint — the merge/dedup key, NOT (file, line)
  file: string; line: number; severity: Severity; text: string;
  confidence: number | null; ruleKey: string | null;
}
export interface ReviewResetMessage { type: 'reset'; sessionId: number; seq: number; chunk: number }

export interface StreamingComment {  // what the UI renders (live or persisted)
  key: string; id?: number; chunk?: number; file: string; line: number; severity: Severity; text: string;
  confidence?: number; ruleKey?: string | null; codeSuggestion?: string | null;
  feedback?: FeedbackSummary; apply?: ApplyState | null; isStreaming: boolean;
}
```

Render unions exhaustively with a `never` guard so an unhandled variant fails the build.

## Data fetching — React Query + typed services + Zod

REST goes through React Query; the WebSocket handles live streaming — keep them separate, never fetch in raw
`useEffect`. Validate every response **and every STOMP message** at the boundary with Zod (`safeParse`, drop
invalid). Query keys: `['prs']`, `['pr', id]`, `['comments', id]`, `['summary', id]`, `['diff', id]`, `['repos']`,
`['dashboard']`, `['auth']`, `['auth-config']`. When a live review leaves `IN_PROGRESS`, invalidate comments, PR,
summary, diff and the PR list so persisted ids (needed for feedback/apply) replace live comments.

## The streaming layer (core of the product)

`lib/reviewStream.ts` is a **pure state machine** (`StreamState = {sessionId, lastSeq, status, reason, comments}`),
unit-tested in isolation; `useReviewStream` only wires it to STOMP:

1. subscribe to review + status and **buffer** live messages;
2. subscribe to `/app/pr/{id}/replay` with headers `after-seq` / `session-id` (from the current state);
3. `applyReplay(state, batch, pending)` — the batch, then buffered messages in `seq` order; go live.

Rules in `applyMessage`: a **newer `sessionId` resets** comments and `lastSeq` (a re-review restarts `seq` at 1 — the
old "re-review drops every token" bug); an older session is ignored; within a session only `seq > lastSeq` is
applied; `reset` drops one chunk's comments; tokens are appended by **`key`**. Appends go through `startTransition`.
`mergeByKey(persisted, live, liveIsAuthoritative)` combines REST and live: while streaming, live wins but keeps the
persisted id/feedback/apply of the same key. Full code in `references/streaming.md`.

## React 18 conventions

- Functional components + hooks only (one class `ErrorBoundary`).
- `useTransition` for high-frequency stream updates; `useId` for label/input pairs; `StrictMode` on.
- Components stay **under ~150 lines** and single-responsibility (e.g. `DiffViewer` → `DiffFileTable` → `InlineComment` → `CommentActions`).
- Toggle buttons expose state with `aria-pressed`; status changes are announced, not color-only.
- Move pure helpers out of component files (`lib/format.ts`) so fast refresh stays clean.

## State management — Zustand

Zustand for cross-route client state (theme, status/risk filters, search, diff mode — persisted to
`localStorage`), React Query for server cache, local `useState` for component state. Never put server data in Zustand.

## Testing

- **Vitest + Testing Library** (`npm run test`): pure logic in `lib/` (stream state machine, `parseDiff` absolute
  numbering and blank-context tolerance, filters) and components via `renderWithProviders` from `src/test/render.tsx`
  (QueryClient without retries + TooltipProvider); stub `fetch` with `vi.stubGlobal`. Vitest excludes `e2e/**`.
- **Playwright** (`npm run e2e`) against the real backend in the `demo` profile: `vite preview` on :4173 with the same
  proxy; `e2e/global-setup.ts` resets the demo and stores Alice's and Bob's sessions as `storageState`; the
  `consoleErrors` auto-fixture fails any test whose page logged an error. Find seeded PRs **by number**
  (`[data-testid="pr-row"][data-number="5"]`) — ids change on every reset. Two-user features use a second
  `browser.newContext({ storageState: storageStateFor('bob') })`. `npm run e2e:screenshots` regenerates the README
  images.
- Stable hooks for tests are `data-testid`s (`diff-table`, `comment-row` + `data-after-line`, `inline-comment` +
  `data-streaming`, `risk-badge` + `data-risk`, `presence-avatar` + `data-login`, `focus-marker`, ...).

## MUST DO
- Use discriminated unions for status/variant state and render them exhaustively.
- Key comments by the finding **fingerprint (`key`)**; append fragments immutably.
- Make stream state session-aware: reset on a newer `sessionId`, dedup by `seq`, buffer live messages until the replay arrives.
- Validate REST responses and STOMP payloads with Zod at the boundary.
- Hold the STOMP client in one provider; clean up subscriptions in the effect return.
- Use React Query for REST, WebSocket for streaming; invalidate on review completion.
- Keep the findings-list fallback when no diff is available.

## MUST NOT DO
- Use `any` or non-null assertions to silence the type checker — model the type instead.
- Key comments on `(file, line)` (two findings can share a line; a retry changes text but not the key).
- Recreate the WebSocket/STOMP client on every render, or subscribe without unsubscribing.
- Mutate state arrays/objects in place.
- Store server-fetched lists in Zustand.
- Render every token as an urgent update without a transition.
- Re-introduce a third-party diff viewer — inline anchoring needs rows between lines (ADR 0007).

## Troubleshooting
- **Re-review shows nothing live**: `lastSeq` wasn't reset for the new `sessionId`.
- **Duplicates after reconnect**: live messages weren't buffered during the replay, or `seq` dedup is missing.
- **Finding rendered one line off**: the diff has a blank context line without its leading space — `parseDiff` must count it using the hunk header's line counts.
- **Feedback/Apply buttons missing**: the comment has no persisted `id` yet (still streaming) — they appear after completion.
- **Diff overflows under the rail**: grid track must be `minmax(0,1fr)` and the ScrollArea content must not be `display: table`.
- **`PrId`/`RepoId` mixed up**: the branded type is doing its job — pass the correct id, don't cast.
