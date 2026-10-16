import { SeverityBadge } from './SeverityBadge';
import { StreamingText } from './StreamingText';
import { SuggestionBlock } from './SuggestionBlock';
import { ApplySuggestionButton, CommentFeedback } from './CommentActions';
import { Card } from '@/components/ui/card';
import type { PrId, StreamingComment } from '@/types/domain';

/**
 * A single review comment anchored to (file, line); left-bordered by severity color. Once the comment is
 * persisted (has an id) it gains feedback votes and, when it carries a code suggestion, "Apply suggestion".
 */
export function InlineComment({
  comment,
  prId,
  showAnchor = true,
}: {
  comment: StreamingComment;
  prId?: PrId;
  showAnchor?: boolean;
}) {
  return (
    <Card
      className="border-l-4 p-3 shadow-none"
      style={{ borderLeftColor: `var(--severity-${comment.severity.toLowerCase()})` }}
      data-testid="inline-comment"
      data-file={comment.file}
      data-line={comment.line}
      data-streaming={comment.isStreaming}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <SeverityBadge severity={comment.severity} />
          {comment.ruleKey && (
            <span className="truncate font-mono text-[11px] text-muted-foreground" title="Rule">
              {comment.ruleKey}
            </span>
          )}
        </div>
        {showAnchor && (
          <span className="shrink-0 font-mono text-xs text-muted-foreground" title={`${comment.file}:${comment.line}`}>
            {comment.file.split('/').pop()}:{comment.line}
          </span>
        )}
      </div>
      <StreamingText text={comment.text} isStreaming={comment.isStreaming} />
      {comment.codeSuggestion && !comment.isStreaming && <SuggestionBlock code={comment.codeSuggestion} />}
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        {typeof comment.confidence === 'number' ? (
          <span className="text-xs text-muted-foreground">Confidence {(comment.confidence * 100).toFixed(0)}%</span>
        ) : (
          <span />
        )}
        {prId && (
          <div className="flex items-center gap-2">
            <ApplySuggestionButton prId={prId} comment={comment} />
            <CommentFeedback prId={prId} comment={comment} />
          </div>
        )}
      </div>
    </Card>
  );
}
