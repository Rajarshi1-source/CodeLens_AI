import { z } from 'zod';
import { getJson, postVoid } from './api';
import { PullRequestSchema, ReviewCommentDtoSchema, ReviewSummarySchema } from '@/types/schemas';
import { PrId, type PullRequest, type ReviewComment, type ReviewSummary } from '@/types/domain';

function toPullRequest(p: z.infer<typeof PullRequestSchema>): PullRequest {
  return { ...p, id: PrId(p.id) };
}

/** Map the backend ReviewCommentDto (filePath/lineNumber/commentText) to the normalized ReviewComment. */
function toComment(c: z.infer<typeof ReviewCommentDtoSchema>): ReviewComment {
  return {
    file: c.filePath,
    line: c.lineNumber,
    severity: c.severity,
    text: c.commentText,
    codeSuggestion: c.codeSuggestion,
    confidence: c.confidence ?? undefined,
  };
}

export const prService = {
  list: (): Promise<PullRequest[]> =>
    getJson('/api/prs', z.array(PullRequestSchema)).then((rows) => rows.map(toPullRequest)),

  get: (prId: PrId): Promise<PullRequest> =>
    getJson(`/api/prs/${prId}`, PullRequestSchema).then(toPullRequest),

  comments: (prId: PrId): Promise<ReviewComment[]> =>
    getJson(`/api/prs/${prId}/comments`, z.array(ReviewCommentDtoSchema)).then((rows) =>
      rows.map(toComment),
    ),

  summary: (reviewId: number): Promise<ReviewSummary> =>
    getJson(`/api/reviews/${reviewId}/summary`, ReviewSummarySchema),

  reReview: (prId: PrId): Promise<void> => postVoid(`/api/prs/${prId}/re-review`),
};
