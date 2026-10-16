import { z } from 'zod';

/**
 * Zod boundary schemas. These mirror the BACKEND DTOs exactly (e.g. comments use
 * filePath/lineNumber/commentText, dashboard uses totalPullRequests). Responses and WebSocket frames
 * are parsed through these so malformed payloads fail loudly and typed.
 */

export const SeveritySchema = z.enum(['CRITICAL', 'WARNING', 'SUGGESTION']);
export const RiskLevelSchema = z.enum(['HIGH', 'MEDIUM', 'LOW']);

export const ReviewStatusSchema = z.enum([
  'PENDING',
  'IN_PROGRESS',
  'COMPLETED',
  'REVIEW_FAILED',
  'REVIEW_UNAVAILABLE',
]);

export const CurrentUserSchema = z.object({
  login: z.string(),
  name: z.string(),
  avatarUrl: z.string(),
});

export const AuthConfigSchema = z.object({
  demoEnabled: z.boolean(),
  githubLoginEnabled: z.boolean(),
});

// PullRequestDto
export const PullRequestSchema = z.object({
  id: z.number(),
  number: z.number().int(),
  title: z.string(),
  author: z.string(),
  status: ReviewStatusSchema,
  filesChanged: z.number().int(),
  additions: z.number().int(),
  deletions: z.number().int(),
  htmlUrl: z.string().nullable(),
  headSha: z.string().nullable(),
  riskLevel: RiskLevelSchema.nullable(),
  riskScore: z.number().nullable(),
  latestSessionId: z.number().nullable(),
});

export const FeedbackSummarySchema = z.object({
  up: z.number(),
  down: z.number(),
  myVote: z.union([z.literal(-1), z.literal(1)]).nullable(),
});

export const ApplyStateSchema = z.object({
  state: z.enum(['PENDING', 'APPLIED', 'FAILED']),
  htmlUrl: z.string().nullable(),
});

// ReviewCommentDto
export const ReviewCommentDtoSchema = z.object({
  id: z.number(),
  filePath: z.string(),
  lineNumber: z.number().int(),
  severity: SeveritySchema,
  commentText: z.string(),
  codeSuggestion: z.string().nullable(),
  confidence: z.number().nullable(),
  ruleKey: z.string().nullable(),
  fingerprint: z.string().nullable(),
  feedback: FeedbackSummarySchema,
  apply: ApplyStateSchema.nullable(),
});

// SuggestionApplyService.ApplyResult
export const ApplyResultSchema = z.object({
  state: z.enum(['PENDING', 'APPLIED', 'FAILED']),
  githubCommentId: z.number().nullable(),
  htmlUrl: z.string().nullable(),
});

// RepoDto
export const RepoSchema = z.object({
  id: z.number(),
  fullName: z.string(),
  active: z.boolean(),
  webhookId: z.number().nullable(),
  createdAt: z.string().nullable(),
  indexStatus: z.string().nullable(),
  indexedChunks: z.number().nullable(),
  indexedAt: z.string().nullable(),
});

// DashboardStats
export const DashboardStatsSchema = z.object({
  totalPullRequests: z.number(),
  reviewedPullRequests: z.number(),
  pendingPullRequests: z.number(),
  severityCounts: z.record(z.string(), z.number()),
  avgReviewTimeMs: z.number().nullable(),
  riskDistribution: z.record(z.string(), z.number()),
  costTodayUsd: z.number(),
  cost30dUsd: z.number(),
  tokensToday: z.number(),
  budget: z.object({ dailyLimit: z.number(), usedToday: z.number(), remaining: z.number() }),
});

export const RiskReasonSchema = z.object({
  code: z.string(),
  detail: z.string(),
  points: z.number(),
});

// ReviewSummaryResponse
export const ReviewSummarySchema = z.object({
  sessionId: z.number(),
  prId: z.number(),
  headSha: z.string(),
  status: ReviewStatusSchema,
  modelUsed: z.string().nullable(),
  promptVersion: z.string().nullable(),
  totalTokens: z.number().nullable(),
  inputTokens: z.number().nullable(),
  outputTokens: z.number().nullable(),
  costUsd: z.number().nullable(),
  reviewTimeMs: z.number().nullable(),
  summary: z.string().nullable(),
  severityCounts: z.record(z.string(), z.number()),
  riskScore: z.number().nullable(),
  riskLevel: RiskLevelSchema.nullable(),
  riskReasons: z.array(RiskReasonSchema).nullable(),
  recommendedAction: z.string().nullable(),
  servedBy: z.record(z.string(), z.number()).nullable(),
  failureReason: z.string().nullable(),
  retrieval: z
    .object({
      enabled: z.boolean().optional(),
      snippets: z.number().optional(),
      estimatedTokens: z.number().optional(),
      latencyMs: z.number().optional(),
    })
    .nullable(),
  startedAt: z.string().nullable(),
  completedAt: z.string().nullable(),
});

// PrDiffResponse (GET /api/prs/{id}/diff) — `diff` is "" when unavailable.
export const PrDiffResponseSchema = z.object({
  prId: z.number(),
  headSha: z.string().nullable(),
  diff: z.string(),
});

// --- WebSocket ------------------------------------------------------------------------------

export const ReviewStreamTokenSchema = z.object({
  type: z.literal('token'),
  sessionId: z.number(),
  seq: z.number(),
  chunk: z.number(),
  key: z.string(),
  file: z.string(),
  line: z.number().int(),
  severity: SeveritySchema,
  text: z.string(),
  confidence: z.number().nullable(),
  ruleKey: z.string().nullable(),
});

export const ReviewResetSchema = z.object({
  type: z.literal('reset'),
  sessionId: z.number(),
  seq: z.number(),
  chunk: z.number(),
});

export const ReviewErrorSchema = z.object({
  type: z.literal('error'),
  sessionId: z.number(),
  seq: z.number(),
  chunk: z.number(),
  file: z.string().nullable().transform((f) => f ?? ''),
});

/** /topic/pr/{id}/review carries a union discriminated on `type`. */
export const ReviewTopicMessageSchema = z.discriminatedUnion('type', [
  ReviewStreamTokenSchema,
  ReviewResetSchema,
  ReviewErrorSchema,
]);

export const ReviewStatusMessageSchema = z.object({
  sessionId: z.number(),
  status: ReviewStatusSchema,
  reason: z.string().nullable(),
});

export const ReplayBatchSchema = z.object({
  sessionId: z.number(),
  status: ReviewStatusSchema.nullable(),
  reason: z.string().nullable(),
  items: z.array(z.unknown()),
});

export const PresenceViewerSchema = z.object({
  sessionId: z.string(),
  userId: z.number(),
  login: z.string(),
  avatarUrl: z.string().nullable(),
  file: z.string().nullable(),
  line: z.number().nullable(),
  typing: z.boolean(),
  lastSeen: z.number(),
});

export const PresenceSnapshotSchema = z.object({
  prId: z.number(),
  viewers: z.array(PresenceViewerSchema),
});
