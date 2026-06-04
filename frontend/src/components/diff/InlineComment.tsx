import { SeverityBadge } from './SeverityBadge';
import { StreamingText } from './StreamingText';
import { Card } from '@/components/ui/card';
import type { StreamingComment } from '@/types/domain';

/** A single review comment anchored to (file, line); left-bordered by severity color. */
export function InlineComment({ comment }: { comment: StreamingComment }) {
  return (
    <Card
      className="border-l-4 p-3 shadow-none"
      style={{ borderLeftColor: `var(--severity-${comment.severity.toLowerCase()})` }}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <SeverityBadge severity={comment.severity} />
        <span className="font-mono text-xs text-muted-foreground">
          {comment.file}:{comment.line}
        </span>
      </div>
      <StreamingText text={comment.text} isStreaming={comment.isStreaming} />
      {typeof comment.confidence === 'number' && (
        <p className="mt-1.5 text-xs text-muted-foreground">
          Confidence: {(comment.confidence * 100).toFixed(0)}%
        </p>
      )}
    </Card>
  );
}
