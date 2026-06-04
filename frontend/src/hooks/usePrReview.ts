import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useReviewStream } from './useReviewStream';
import { prService } from '@/services/prService';
import { mergeByAnchor } from '@/lib/reviewStream';
import type { PrId, ReviewStatus, StreamingComment } from '@/types/domain';

/**
 * Combines persisted comments (React Query) with the live WebSocket stream so a PR view works
 * whether its review is finished or still in-flight. Live comments supersede persisted ones.
 */
export function usePrReview(prId: PrId): {
  comments: StreamingComment[];
  status: ReviewStatus | null;
  isLoading: boolean;
} {
  const persisted = useQuery({
    queryKey: ['comments', prId],
    queryFn: () => prService.comments(prId),
  });

  const { comments: live, status } = useReviewStream(prId);

  const comments = useMemo(
    () => mergeByAnchor(persisted.data ?? [], live),
    [persisted.data, live],
  );

  return { comments, status, isLoading: persisted.isLoading };
}
