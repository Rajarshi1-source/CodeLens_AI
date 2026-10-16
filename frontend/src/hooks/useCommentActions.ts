import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { commentService } from '@/services/commentService';
import { ApiError } from '@/services/api';
import type { ApplyState, FeedbackSummary, PrId, ReviewComment } from '@/types/domain';

function patchComment(
  qc: ReturnType<typeof useQueryClient>,
  prId: PrId,
  commentId: number,
  patch: Partial<Pick<ReviewComment, 'feedback' | 'apply'>>,
) {
  qc.setQueryData<ReviewComment[]>(['comments', prId], (prev) =>
    prev?.map((c) => (c.id === commentId ? { ...c, ...patch } : c)),
  );
}

/**
 * Thumbs up/down on an AI comment. Clicking the active vote again clears it. The comments cache is
 * updated optimistically and rolled back on failure.
 */
export function useCommentFeedback(prId: PrId) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ commentId, vote, current }: { commentId: number; vote: 1 | -1; current: FeedbackSummary }) =>
      current.myVote === vote ? commentService.clearVote(commentId) : commentService.vote(commentId, vote),
    onMutate: ({ commentId, vote, current }) => {
      const previous = qc.getQueryData<ReviewComment[]>(['comments', prId]);
      const clearing = current.myVote === vote;
      const optimistic: FeedbackSummary = {
        up: current.up - (current.myVote === 1 ? 1 : 0) + (!clearing && vote === 1 ? 1 : 0),
        down: current.down - (current.myVote === -1 ? 1 : 0) + (!clearing && vote === -1 ? 1 : 0),
        myVote: clearing ? null : vote,
      };
      patchComment(qc, prId, commentId, { feedback: optimistic });
      return { previous };
    },
    onSuccess: (feedback, { commentId }) => patchComment(qc, prId, commentId, { feedback }),
    onError: (_e, _v, ctx) => {
      if (ctx?.previous) qc.setQueryData(['comments', prId], ctx.previous);
      toast.error('Could not save your feedback');
    },
  });
}

/** "Apply suggestion": posts the suggestion to the PR as a GitHub suggested change. */
export function useApplySuggestion(prId: PrId) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (commentId: number) => commentService.apply(commentId),
    onSuccess: (result, commentId) => {
      const apply: ApplyState = { state: result.state, htmlUrl: result.htmlUrl };
      patchComment(qc, prId, commentId, { apply });
      if (result.state === 'APPLIED') toast.success('Suggestion posted to the pull request');
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        toast.error('The PR changed since this review — re-run the review before applying.');
      } else {
        toast.error(e instanceof Error ? e.message : 'Could not apply the suggestion');
      }
      void qc.invalidateQueries({ queryKey: ['comments', prId] });
    },
  });
}
