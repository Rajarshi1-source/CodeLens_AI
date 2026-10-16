import { useEffect, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useReviewStream } from './useReviewStream';
import { prService } from '@/services/prService';
import { mergeByKey } from '@/lib/reviewStream';
import type { PrId, ReviewStatus, ReviewSummary, StreamingComment } from '@/types/domain';

/**
 * Combines persisted comments (React Query) with the live WebSocket stream so a PR view works whether
 * its review is finished or still in-flight. When a streamed review completes, the PR, its comments and
 * its summary are refetched, so persisted ids (needed for feedback / apply) appear.
 */
export function usePrReview(prId: PrId): {
  comments: StreamingComment[];
  status: ReviewStatus | null;
  reason: string | null;
  sessionId: number | null;
  isLoading: boolean;
} {
  const qc = useQueryClient();
  const persisted = useQuery({
    queryKey: ['comments', prId],
    queryFn: () => prService.comments(prId),
  });

  const stream = useReviewStream(prId);

  const lastStatus = useRef(stream.status);
  useEffect(() => {
    const was = lastStatus.current;
    lastStatus.current = stream.status;
    if (was === 'IN_PROGRESS' && stream.status && stream.status !== 'IN_PROGRESS') {
      void qc.invalidateQueries({ queryKey: ['comments', prId] });
      void qc.invalidateQueries({ queryKey: ['pr', prId] });
      void qc.invalidateQueries({ queryKey: ['summary', prId] });
      void qc.invalidateQueries({ queryKey: ['diff', prId] });
      void qc.invalidateQueries({ queryKey: ['prs'] });
    }
  }, [stream.status, prId, qc]);

  const comments = useMemo(
    () => mergeByKey(persisted.data ?? [], stream.comments, stream.status === 'IN_PROGRESS'),
    [persisted.data, stream.comments, stream.status],
  );

  return {
    comments,
    status: stream.status,
    reason: stream.reason,
    sessionId: stream.sessionId,
    isLoading: persisted.isLoading,
  };
}

/** Latest review summary (risk, tokens, cost); null when the PR has never been reviewed. */
export function usePrSummary(prId: PrId) {
  return useQuery<ReviewSummary | null>({
    queryKey: ['summary', prId],
    queryFn: () => prService.summary(prId),
  });
}
