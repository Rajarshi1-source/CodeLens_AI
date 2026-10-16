/** Shared domain model. Wire shapes (Zod) live in types/schemas.ts and are validated at the boundary. */

export type Severity = 'CRITICAL' | 'WARNING' | 'SUGGESTION';

export type ReviewStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'REVIEW_FAILED'
  | 'REVIEW_UNAVAILABLE';

/** G3 PR risk band — deliberately separate from Severity (which grades one comment). */
export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW';

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
