import { deleteJson, postJson, putJson } from './api';
import { ApplyResultSchema, FeedbackSummarySchema } from '@/types/schemas';
import type { FeedbackSummary } from '@/types/domain';
import type { z } from 'zod';

export type ApplyResult = z.infer<typeof ApplyResultSchema>;

/** Per-comment actions: thumbs up/down (feedback flywheel) and "apply suggestion". */
export const commentService = {
  vote: (commentId: number, vote: 1 | -1): Promise<FeedbackSummary> =>
    putJson(`/api/comments/${commentId}/feedback`, { vote }, FeedbackSummarySchema),

  clearVote: (commentId: number): Promise<FeedbackSummary> =>
    deleteJson(`/api/comments/${commentId}/feedback`, FeedbackSummarySchema),

  apply: (commentId: number): Promise<ApplyResult> =>
    postJson(`/api/comments/${commentId}/apply`, undefined, ApplyResultSchema),
};
