# CodeLens AI — Types, Zod & Data-Fetching Reference

The full TypeScript domain model, Zod boundary schemas, and React Query patterns. Load when defining types, validating API responses, or wiring data fetching/mutations. SKILL.md has the key types; this is the complete catalog and the validation strategy.

## Contents
1. Branded IDs
2. Domain types
3. Zod schemas (single source of truth)
4. Typed API services
5. React Query patterns
6. Read-your-writes on the client

## 1. Branded IDs

Prevent mixing up ID kinds at compile time:

```ts
type Brand<T, B> = T & { readonly __brand: B };
export type PrId   = Brand<string, 'PrId'>;
export type RepoId = Brand<string, 'RepoId'>;
export type UserId = Brand<string, 'UserId'>;

export const PrId   = (s: string) => s as PrId;     // smart constructors at boundaries
export const RepoId = (s: string) => s as RepoId;
```

A function taking `PrId` rejects a raw `string` or a `RepoId` — passing the wrong id becomes a type error, not a runtime mystery.

## 2. Domain types

The real `src/types/domain.ts` (after the branded ids). Persisted comments are normalized from the wire DTO
(`filePath`/`lineNumber`/`commentText` → `file`/`line`/`text`, `fingerprint` → `key`) in `prService`, so the UI has one
comment shape for live and persisted findings (`StreamingComment`).

```ts
export interface CurrentUser {
  login: string;
  name: string;
  avatarUrl: string;
}

export interface AuthConfig {
  demoEnabled: boolean;
  githubLoginEnabled: boolean;
}

export interface PullRequest {
  id: PrId;
  number: number;
  title: string;
  author: string;
  status: ReviewStatus;
  filesChanged: number;
  additions: number;
  deletions: number;
  htmlUrl: string | null;
  headSha: string | null;
  riskLevel: RiskLevel | null;
  riskScore: number | null;
  latestSessionId: number | null;
}

export interface Repository {
  id: RepoId;
  fullName: string;
  active: boolean;
  webhookId: number | null;
  createdAt: string | null;
  indexStatus: string | null;
  indexedChunks: number | null;
  indexedAt: string | null;
}

export interface FeedbackSummary {
  up: number;
  down: number;
  myVote: -1 | 1 | null;
}

export type ApplyStateName = 'PENDING' | 'APPLIED' | 'FAILED';

export interface ApplyState {
  state: ApplyStateName;
  htmlUrl: string | null;
}

/** Normalized persisted comment (REST). */
export interface ReviewComment {
  id: number;
  key: string;
  file: string;
  line: number;
  severity: Severity;
  text: string;
  codeSuggestion: string | null;
  confidence?: number;
  ruleKey: string | null;
  feedback: FeedbackSummary;
  apply: ApplyState | null;
}

/** One streamed text fragment of a finding (WebSocket). `key` is the finding's fingerprint. */
export interface ReviewStreamToken {
  type: 'token';
  sessionId: number;
  seq: number;
  chunk: number;
  key: string;
  file: string;
  line: number;
  severity: Severity;
  text: string;
  confidence: number | null;
  ruleKey: string | null;
}

export interface ReviewResetMessage {
  type: 'reset';
  sessionId: number;
  seq: number;
  chunk: number;
}

export interface ReviewErrorMessage {
  type: 'error';
  sessionId: number;
  seq: number;
  chunk: number;
  file: string;
}

/** Discriminated union pushed to /topic/pr/{id}/review. */
export type ReviewTopicMessage = ReviewStreamToken | ReviewResetMessage | ReviewErrorMessage;

export interface ReviewStatusMessage {
  sessionId: number;
  status: ReviewStatus;
  reason: string | null;
}

/** The catch-up batch a (re)connecting client receives from /app/pr/{id}/replay. */
export interface ReplayBatch {
  sessionId: number;
  status: ReviewStatus | null;
  reason: string | null;
  items: ReviewTopicMessage[];
}

/** A comment as the UI renders it — persisted, live, or both merged on `key`. */
export interface StreamingComment {
  key: string;
  id?: number;
  chunk?: number;
  file: string;
  line: number;
  severity: Severity;
  text: string;
  confidence?: number;
  ruleKey?: string | null;
  codeSuggestion?: string | null;
  feedback?: FeedbackSummary;
  apply?: ApplyState | null;
  isStreaming: boolean;
}

export interface DashboardStats {
  totalPullRequests: number;
  reviewedPullRequests: number;
  pendingPullRequests: number;
  severityCounts: Record<string, number>;
  avgReviewTimeMs: number | null;
  riskDistribution: Record<string, number>;
  costTodayUsd: number;
  cost30dUsd: number;
  tokensToday: number;
  budget: { dailyLimit: number; usedToday: number; remaining: number };
}

export interface RiskReason {
  code: string;
  detail: string;
  points: number;
}

export interface ReviewSummary {
  sessionId: number;
  prId: number;
  headSha: string;
  status: ReviewStatus;
  modelUsed: string | null;
  promptVersion: string | null;
  totalTokens: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  costUsd: number | null;
  reviewTimeMs: number | null;
  summary: string | null;
  severityCounts: Record<string, number>;
  riskScore: number | null;
  riskLevel: RiskLevel | null;
  riskReasons: RiskReason[] | null;
  recommendedAction: string | null;
  servedBy: Record<string, number> | null;
  failureReason: string | null;
  retrieval: { enabled?: boolean; snippets?: number; estimatedTokens?: number; latencyMs?: number } | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PresenceViewer {
  sessionId: string;
  userId: number;
  login: string;
  avatarUrl: string | null;
  file: string | null;
  line: number | null;
  typing: boolean;
  lastSeen: number;
}

/** Exhaustiveness guard: adding a union member without handling it fails the build. */
export function assertNever(x: never): never {
  throw new Error('Unhandled case: ' + JSON.stringify(x));
}
```

Exhaustive rendering with a `never` guard catches missed cases at compile time:

```ts
function assertNever(x: never): never { throw new Error('unhandled: ' + JSON.stringify(x)); }

function renderStatus(s: ReviewStatus) {
  switch (s) {
    case 'PENDING': case 'IN_PROGRESS': return 'Reviewing…';
    case 'COMPLETED': return 'Done';
    case 'REVIEW_FAILED': return 'Failed';
    case 'REVIEW_UNAVAILABLE': return 'AI unavailable';
    default: return assertNever(s);   // adding a status without handling it fails the build
  }
}
```

## 3. Zod schemas (single source of truth)

Validate every REST response **and every STOMP message** at the boundary (`types/schemas.ts`); infer wire types from
the schemas. Unions use `z.discriminatedUnion` on the same `type` field the backend writes with `@JsonTypeInfo`:

```ts
export const ReviewStreamTokenSchema = z.object({
  type: z.literal('token'), sessionId: z.number(), seq: z.number(), chunk: z.number(), key: z.string(),
  file: z.string(), line: z.number().int(), severity: SeveritySchema, text: z.string(),
  confidence: z.number().nullable(), ruleKey: z.string().nullable(),
});
export const ReviewResetSchema = z.object({ type: z.literal('reset'), sessionId: z.number(), seq: z.number(), chunk: z.number() });
export const ReviewErrorSchema = z.object({
  type: z.literal('error'), sessionId: z.number(), seq: z.number(), chunk: z.number(),
  file: z.string().nullable().transform((f) => f ?? ''),
});
export const ReviewTopicMessageSchema = z.discriminatedUnion('type', [ReviewStreamTokenSchema, ReviewResetSchema, ReviewErrorSchema]);

export const ReviewCommentDtoSchema = z.object({
  id: z.number(), filePath: z.string(), lineNumber: z.number().int(), severity: SeveritySchema,
  commentText: z.string(), codeSuggestion: z.string().nullable(), confidence: z.number().nullable(),
  ruleKey: z.string().nullable(), fingerprint: z.string().nullable(),
  feedback: FeedbackSummarySchema, apply: ApplyStateSchema.nullable(),
});
```

Other schemas: `PullRequestSchema` (with `riskLevel`, `riskScore`, `latestSessionId` nullable), `ReviewSummarySchema`
(risk reasons, tokens, cost, `servedBy`, `failureReason`, retrieval stats), `DashboardStatsSchema` (risk distribution,
cost, budget), `ReviewStatusMessageSchema`, `ReplayBatchSchema` (items validated one by one), `PresenceSnapshotSchema`,
`ApplyResultSchema`, `AuthConfigSchema`, `RepoSchema` (index status). `schemas.test.ts` parses real backend payloads.

## 4. Typed API services

`services/api.ts` wraps `fetch` (same-origin, `credentials: 'include'`), maps 401 to `UnauthorizedError` (so
`RequireAuth` redirects and React Query stops retrying) and other failures to `ApiError(status, message)` with the
backend's error message, and parses every body with a Zod schema.

```ts
export const prService = {
  list: (risk?: RiskLevel) =>
    getJson(`/api/prs${risk ? `?risk=${risk}` : ''}`, z.array(PullRequestSchema)).then((rows) => rows.map(toPullRequest)),
  comments: (prId: PrId) =>
    getJson(`/api/prs/${prId}/comments`, z.array(ReviewCommentDtoSchema)).then((rows) => rows.map(toComment)),
  summary: (prId: PrId) => getJsonOrNull(`/api/prs/${prId}/summary`, ReviewSummarySchema),   // 404 → null (never reviewed)
  diff: (prId: PrId) => getJson(`/api/prs/${prId}/diff`, PrDiffResponseSchema).then((r) => r.diff),
  reReview: (prId: PrId) => postVoid(`/api/prs/${prId}/re-review`),
};

export const commentService = {
  vote: (id: number, vote: 1 | -1) => putJson(`/api/comments/${id}/feedback`, { vote }, FeedbackSummarySchema),
  clearVote: (id: number) => deleteJson(`/api/comments/${id}/feedback`, FeedbackSummarySchema),
  apply: (id: number) => postJson(`/api/comments/${id}/apply`, undefined, ApplyResultSchema),
};
```

## 5. React Query patterns

```ts
export function usePullRequests() {
  return useQuery<PullRequest[]>({ queryKey: ['prs'], queryFn: () => prService.list(), staleTime: 15_000 });
}

export function useRepos() {
  return useQuery<Repository[]>({
    queryKey: ['repos'],
    queryFn: repoService.list,
    // indexing runs in the background: poll only while a repo is still indexing
    refetchInterval: (q) => (q.state.data?.some((r) => r.indexStatus === 'INDEXING') ? 2000 : false),
  });
}
```

**Optimistic feedback** (`useCommentFeedback`): `onMutate` snapshots `['comments', prId]`, patches the comment's
`feedback` (clicking the active vote clears it), `onSuccess` writes the server's counts, `onError` restores the
snapshot and toasts. **Apply** (`useApplySuggestion`) is *not* optimistic: it patches `apply` from the server result;
a 409 means the PR moved since the review ("re-run the review before applying").

## 6. Read-your-writes on the client

After a re-review, invalidate `['prs']`, `['pr', id]` and `['comments', id]` and show `IN_PROGRESS`; the stream
populates findings. When the stream reports a terminal status, invalidate comments, PR, summary and diff so persisted
ids replace live comments. Don't fake review results optimistically — only user-owned state (votes) is optimistic.
