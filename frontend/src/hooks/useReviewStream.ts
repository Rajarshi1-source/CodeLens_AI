import { useEffect, useRef, useState, useTransition } from 'react';
import { toast } from 'sonner';
import { useWebSocket } from './useWebSocket';
import { appendToken, isNewSeq } from '@/lib/reviewStream';
import { ReviewErrorSchema, ReviewStreamTokenSchema } from '@/types/schemas';
import type { PrId, ReviewStatus, StreamingComment } from '@/types/domain';

interface ReviewStreamState {
  comments: StreamingComment[];
  status: ReviewStatus | null;
}

/**
 * Subscribes to a PR's review + status topics. Dedupes by seq (replay-safe), appends tokens by
 * (file,line) anchor, and reconnects via stompjs. Resumes with a `resume-from-seq` header so the
 * backend replays only what was missed.
 */
export function useReviewStream(prId: PrId): ReviewStreamState {
  const { client, connected } = useWebSocket();
  const [comments, setComments] = useState<StreamingComment[]>([]);
  const [status, setStatus] = useState<ReviewStatus | null>(null);
  const lastSeq = useRef(0);
  const [, startTransition] = useTransition();

  // Reset accumulated state when switching PRs.
  useEffect(() => {
    lastSeq.current = 0;
    setComments([]);
    setStatus(null);
  }, [prId]);

  useEffect(() => {
    if (!connected) return;

    const reviewSub = client.subscribe(
      `/topic/pr/${prId}/review`,
      (message) => {
        const payload: unknown = JSON.parse(message.body);

        const err = ReviewErrorSchema.safeParse(payload);
        if (err.success) {
          toast.error(`Review error in ${err.data.file}: ${err.data.error}`);
          return;
        }

        const parsed = ReviewStreamTokenSchema.safeParse(payload);
        if (!parsed.success) return;
        const token = parsed.data;
        if (!isNewSeq(lastSeq.current, token.seq)) return; // drop replays / dupes
        lastSeq.current = token.seq;

        startTransition(() => {
          setComments((prev) => appendToken(prev, token));
        });
      },
      { 'resume-from-seq': String(lastSeq.current) },
    );

    const statusSub = client.subscribe(`/topic/pr/${prId}/status`, (m) => {
      const next = m.body as ReviewStatus;
      setStatus(next);
      if (next === 'COMPLETED') {
        setComments((prev) => prev.map((c) => ({ ...c, isStreaming: false })));
      }
    });

    return () => {
      reviewSub.unsubscribe();
      statusSub.unsubscribe();
    };
  }, [client, connected, prId]);

  return { comments, status };
}
