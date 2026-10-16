import { z } from 'zod';
import { getJson, getJsonOrNull, postVoid } from './api';
import {
  PrDiffResponseSchema,
  PullRequestSchema,
  ReviewCommentDtoSchema,
  ReviewSummarySchema,
} from '@/types/schemas';
import { PrId, type PullRequest, type ReviewComment, type ReviewSummary } from '@/types/domain';

function toPullRequest(p: z.infer<typeof PullRequestSchema>): PullRequest {
  return { ...p, id: PrId(p.id) };
}

/** Map the backend ReviewCommentDto (filePath/lineNumber/commentText) to the normalized ReviewComment. */
export function toComment(c: z.infer<typeof ReviewCommentDtoSchema>): ReviewComment {
  return {
    id: c.id,
    key: c.fingerprint ?? `${c.filePath}:${c.lineNumber}:${c.ruleKey ?? ''}`,
    file: c.filePath,
    line: c.lineNumber,
    severity: c.severity,
    text: c.commentText,
    codeSuggestion: c.codeSuggestion,
    confidence: c.confidence ?? undefined,
    ruleKey: c.ruleKey,
    feedback: c.feedback,
    apply: c.apply,
  };
}

export const prService = {
  list: (risk?: string): Promise<PullRequest[]> =>
    getJson(`/api/prs${risk && risk !== 'ALL' ? `?risk=${encodeURIComponent(risk)}` : ''}`, z.array(PullRequestSchema)).then(
      (rows) => rows.map(toPullRequest),
    ),

  get: (prId: PrId): Promise<PullRequest> =>
    getJson(`/api/prs/${prId}`, PullRequestSchema).then(toPullRequest),

  /** Comments of the PR's latest review (with feedback tallies and apply state). */
  comments: (prId: PrId): Promise<ReviewComment[]> =>
    getJson(`/api/prs/${prId}/comments`, z.array(ReviewCommentDtoSchema)).then((rows) => rows.map(toComment)),

  /** Latest review summary (risk, tokens, cost); null when the PR has never been reviewed. */
  summary: (prId: PrId): Promise<ReviewSummary | null> => getJsonOrNull(`/api/prs/${prId}/summary`, ReviewSummarySchema),

  /** Unified diff the review ran against (persisted snapshot); "" when unavailable. */
  diff: (prId: PrId): Promise<string> => getJson(`/api/prs/${prId}/diff`, PrDiffResponseSchema).then((r) => r.diff),

  reReview: (prId: PrId): Promise<void> => postVoid(`/api/prs/${prId}/re-review`),
};
