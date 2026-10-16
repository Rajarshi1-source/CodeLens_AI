import { Check, ExternalLink, GitPullRequestArrow, Loader2, ThumbsDown, ThumbsUp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { useApplySuggestion, useCommentFeedback } from '@/hooks/useCommentActions';
import type { PrId, StreamingComment } from '@/types/domain';

/** Thumbs up/down (feedback flywheel). Toggle buttons expose their state with aria-pressed. */
export function CommentFeedback({ prId, comment }: { prId: PrId; comment: StreamingComment }) {
  const feedback = useCommentFeedback(prId);
  if (comment.id === undefined || !comment.feedback) return null;
  const current = comment.feedback;
  const vote = (v: 1 | -1) => feedback.mutate({ commentId: comment.id!, vote: v, current });
  return (
    <div className="flex items-center gap-1" data-testid="comment-feedback">
      <Button
        variant="ghost"
        size="sm"
        className={cn('h-7 gap-1 px-2 text-xs', current.myVote === 1 && 'bg-accent text-foreground')}
        aria-pressed={current.myVote === 1}
        aria-label="Helpful"
        onClick={() => vote(1)}
      >
        <ThumbsUp className="h-3.5 w-3.5" aria-hidden />
        {current.up}
      </Button>
      <Button
        variant="ghost"
        size="sm"
        className={cn('h-7 gap-1 px-2 text-xs', current.myVote === -1 && 'bg-accent text-foreground')}
        aria-pressed={current.myVote === -1}
        aria-label="Not helpful"
        onClick={() => vote(-1)}
      >
        <ThumbsDown className="h-3.5 w-3.5" aria-hidden />
        {current.down}
      </Button>
    </div>
  );
}

/** Posts the suggestion to GitHub as a suggested change; shows the link once applied. */
export function ApplySuggestionButton({ prId, comment }: { prId: PrId; comment: StreamingComment }) {
  const apply = useApplySuggestion(prId);
  if (comment.id === undefined || !comment.codeSuggestion || comment.isStreaming) return null;
  if (comment.apply?.state === 'APPLIED') {
    return comment.apply.htmlUrl ? (
      <Button variant="outline" size="sm" className="h-7 gap-1 px-2 text-xs" asChild>
        <a href={comment.apply.htmlUrl} target="_blank" rel="noreferrer" data-testid="applied-link">
          <Check className="h-3.5 w-3.5" aria-hidden />
          Applied
          <ExternalLink className="h-3 w-3" aria-hidden />
        </a>
      </Button>
    ) : (
      <span className="text-xs text-muted-foreground">Applied</span>
    );
  }
  const pending = apply.isPending || comment.apply?.state === 'PENDING';
  return (
    <Button
      variant="outline"
      size="sm"
      className="h-7 gap-1 px-2 text-xs"
      disabled={pending}
      onClick={() => apply.mutate(comment.id!)}
      data-testid="apply-suggestion"
    >
      {pending ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> : <GitPullRequestArrow className="h-3.5 w-3.5" aria-hidden />}
      {comment.apply?.state === 'FAILED' ? 'Retry apply' : 'Apply suggestion'}
    </Button>
  );
}
