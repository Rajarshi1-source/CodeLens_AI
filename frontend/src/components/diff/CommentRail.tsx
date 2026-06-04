import { InlineComment } from './InlineComment';
import { EmptyState } from '@/components/common/EmptyState';
import { MessageSquare } from 'lucide-react';
import type { StreamingComment } from '@/types/domain';

export function CommentRail({ comments }: { comments: StreamingComment[] }) {
  if (comments.length === 0) {
    return (
      <div className="p-4">
        <EmptyState
          icon={<MessageSquare className="h-6 w-6" />}
          title="No comments yet"
          description="Findings appear here as the review streams in."
        />
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3 p-4">
      {comments.map((c) => (
        <InlineComment key={`${c.file}:${c.line}`} comment={c} />
      ))}
    </div>
  );
}
