/** Shared domain model. Wire shapes (Zod) live in types/schemas.ts and are validated at the boundary. */

export type Severity = 'CRITICAL' | 'WARNING' | 'SUGGESTION';

export type ReviewStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'REVIEW_FAILED'
  | 'REVIEW_UNAVAILABLE';

// Branded IDs so a PR id can't be passed where a repo id is expected.
type Brand<T, B> = T & { readonly __brand: B };
export type PrId = Brand<string, 'PrId'>;
export type RepoId = Brand<string, 'RepoId'>;

export const PrId = (s: string | number) => String(s) as PrId;
export const RepoId = (s: string | number) => String(s) as RepoId;

export interface CurrentUser {
  login: string;
  name: string;
  avatarUrl: string;
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
}

export interface Repository {
  id: RepoId;
  fullName: string;
  active: boolean;
  webhookId: number | null;
  createdAt: string | null;
}

/** Normalized comment used across the UI (REST persisted + live stream converge on this shape). */
export interface ReviewComment {
  file: string;
  line: number;
  severity: Severity;
  text: string;
  codeSuggestion?: string | null;
  confidence?: number;
}

export interface ReviewStreamToken {
  seq: number;
  file: string;
  line: number;
  severity: Severity;
  text: string;
}

/** A comment that accumulates token-by-token in the UI. */
export interface StreamingComment {
  file: string;
  line: number;
  severity: Severity;
  text: string;
  confidence?: number;
  isStreaming: boolean;
}

export interface DashboardStats {
  totalPullRequests: number;
  reviewedPullRequests: number;
  pendingPullRequests: number;
  severityCounts: Record<string, number>;
  avgReviewTimeMs: number | null;
}

export interface ReviewSummary {
  sessionId: number;
  prId: number;
  headSha: string;
  status: ReviewStatus;
  modelUsed: string | null;
  promptVersion: string | null;
  totalTokens: number | null;
  reviewTimeMs: number | null;
  summary: string | null;
  severityCounts: Record<string, number>;
  startedAt: string | null;
  completedAt: string | null;
}

/** Exhaustiveness guard: adding a ReviewStatus without handling it fails the build. */
export function assertNever(x: never): never {
  throw new Error('Unhandled case: ' + JSON.stringify(x));
}
