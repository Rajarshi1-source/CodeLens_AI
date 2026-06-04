import { Badge } from '@/components/ui/badge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { assertNever, type ReviewStatus } from '@/types/domain';

interface StatusMeta {
  label: string;
  className: string;
  hint: string;
}

function meta(status: ReviewStatus): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: 'Pending', className: 'bg-muted text-muted-foreground', hint: 'Waiting to be reviewed.' };
    case 'IN_PROGRESS':
      return {
        label: 'Reviewing',
        className: 'bg-primary/15 text-primary border-primary/30',
        hint: 'AI review is in progress.',
      };
    case 'COMPLETED':
      return {
        label: 'Reviewed',
        className:
          'bg-[var(--severity-suggestion)]/15 text-[var(--severity-suggestion)] border-[var(--severity-suggestion)]/30',
        hint: 'Review completed.',
      };
    case 'REVIEW_FAILED':
      return {
        label: 'Failed',
        className: 'bg-destructive/15 text-destructive border-destructive/30',
        hint: 'The review failed. Try re-reviewing.',
      };
    case 'REVIEW_UNAVAILABLE':
      return {
        label: 'Unavailable',
        className: 'bg-muted text-muted-foreground',
        hint: 'AI review was unavailable for this PR.',
      };
    default:
      return assertNever(status);
  }
}

export function PRStatusBadge({ status }: { status: ReviewStatus }) {
  const m = meta(status);
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant="outline" className={cn('font-medium', m.className)}>
          {m.label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{m.hint}</TooltipContent>
    </Tooltip>
  );
}
