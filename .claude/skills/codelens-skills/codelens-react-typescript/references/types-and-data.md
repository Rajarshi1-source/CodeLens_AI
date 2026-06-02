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

```ts
export type Severity = 'CRITICAL' | 'WARNING' | 'SUGGESTION';

export type ReviewStatus =
  | 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'REVIEW_FAILED' | 'REVIEW_UNAVAILABLE';

export interface ReviewStreamToken {
  seq: number;
  file: string;
  line: number;
  severity: Severity;
  text: string;
}

export interface StreamingComment {
  file: string;
  line: number;
  severity: Severity;
  text: string;
  confidence?: number;
  isStreaming: boolean;
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
}

export interface Repository {
  id: RepoId;
  fullName: string;
  isActive: boolean;
}

export interface DashboardStats {
  totalReviews: number;
  avgReviewTimeMs: number;
  severityCounts: Record<Severity, number>;
}

// Discriminated union for async UI state — render exhaustively.
export type Async<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; error: string }
  | { status: 'success'; data: T };
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

Validate API responses at the boundary so malformed payloads fail loudly and typed. Infer the TS type from the schema — don't hand-write both.

```ts
import { z } from 'zod';

export const SeveritySchema = z.enum(['CRITICAL', 'WARNING', 'SUGGESTION']);
export const ReviewStatusSchema = z.enum([
  'PENDING', 'IN_PROGRESS', 'COMPLETED', 'REVIEW_FAILED', 'REVIEW_UNAVAILABLE',
]);

export const PullRequestSchema = z.object({
  id: z.string(),
  number: z.number().int(),
  title: z.string(),
  author: z.string(),
  status: ReviewStatusSchema,
  filesChanged: z.number().int(),
  additions: z.number().int(),
  deletions: z.number().int(),
});

export const CommentSchema = z.object({
  file: z.string(),
  line: z.number().int(),
  severity: SeveritySchema,
  text: z.string(),
  confidence: z.number().min(0).max(1).optional(),
});

export type PullRequestWire = z.infer<typeof PullRequestSchema>;
```

## 4. Typed API services

```ts
const BASE = import.meta.env.VITE_API_URL;

async function getJson<T>(path: string, schema: z.ZodSchema<T>): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { credentials: 'include' });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return schema.parse(await res.json());          // throws ZodError on bad shape
}

export const prService = {
  list: (repoId: RepoId) =>
    getJson(`/api/prs?repo=${repoId}`, z.array(PullRequestSchema))
      .then((rows) => rows.map((r) => ({ ...r, id: PrId(r.id) }) as PullRequest)),

  comments: (prId: PrId) =>
    getJson(`/api/prs/${prId}/comments`, z.array(CommentSchema)),

  reReview: (prId: PrId) =>
    fetch(`${BASE}/api/prs/${prId}/re-review`, { method: 'POST', credentials: 'include' }),
};
```

## 5. React Query patterns

REST goes through React Query; the WebSocket handles live streaming. Keep them separate.

```ts
export function usePullRequests(repoId: RepoId) {
  return useQuery({
    queryKey: ['prs', repoId],
    queryFn: () => prService.list(repoId),
    staleTime: 30_000,
  });
}

export function useReReview(repoId: RepoId) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (prId: PrId) => prService.reReview(prId),
    onSuccess: (_d, prId) => {
      qc.invalidateQueries({ queryKey: ['prs', repoId] });
      qc.invalidateQueries({ queryKey: ['comments', prId] });   // see read-your-writes
    },
  });
}
```

## 6. Read-your-writes on the client

After triggering a re-review, the user must see their own change immediately even though dashboard reads are eventually consistent server-side. Invalidating the PR's queries (above) forces a refetch routed to fresh data, and the WebSocket stream begins delivering the new review's comments. Don't optimistically fake the result — show `IN_PROGRESS` and let the stream populate it, so the UI never diverges from the server.
