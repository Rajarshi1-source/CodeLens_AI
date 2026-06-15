import { z } from 'zod';

/**
 * Zod boundary schemas. These mirror the BACKEND DTOs exactly (e.g. comments use
 * filePath/lineNumber/commentText, dashboard uses totalPullRequests). Responses are parsed
 * through these so malformed payloads fail loudly and typed.
 */

export const SeveritySchema = z.enum(['CRITICAL', 'WARNING', 'SUGGESTION']);

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
});

// RepoDto
export const RepoSchema = z.object({
  id: z.number(),
  fullName: z.string(),
  active: z.boolean(),
  webhookId: z.number().nullable(),
  createdAt: z.string().nullable(),
});

// DashboardStats
export const DashboardStatsSchema = z.object({
  totalPullRequests: z.number(),
  reviewedPullRequests: z.number(),
  pendingPullRequests: z.number(),
  severityCounts: z.record(z.string(), z.number()),
  avgReviewTimeMs: z.number().nullable(),
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
  reviewTimeMs: z.number().nullable(),
  summary: z.string().nullable(),
  severityCounts: z.record(z.string(), z.number()),
  startedAt: z.string().nullable(),
  completedAt: z.string().nullable(),
});

// PrDiffResponse (GET /api/prs/{id}/diff) — `diff` is "" when unavailable.
export const PrDiffResponseSchema = z.object({
  prId: z.number(),
  headSha: z.string().nullable(),
  diff: z.string(),
});

// ReviewStreamToken (WebSocket /topic/pr/{id}/review)
export const ReviewStreamTokenSchema = z.object({
  seq: z.number(),
  file: z.string(),
  line: z.number().int(),
  severity: SeveritySchema,
  text: z.string(),
});

// Error envelope also delivered on the review topic
export const ReviewErrorSchema = z.object({
  error: z.string(),
  file: z.string(),
});
