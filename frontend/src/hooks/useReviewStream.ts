import { useEffect, useRef, useState, useTransition } from 'react';
import { toast } from 'sonner';
import { useWebSocket } from './useWebSocket';
import { EMPTY_STREAM, applyMessage, applyReplay, applyStatus, type StreamState } from '@/lib/reviewStream';
import { ReplayBatchSchema, ReviewStatusMessageSchema, ReviewTopicMessageSchema } from '@/types/schemas';
import type { PrId, ReplayBatch, ReviewTopicMessage } from '@/types/domain';

/**
 * Live review stream for one PR, resumable across reconnects:
 *
 *  1. subscribe to the review + status topics, BUFFERING live messages;
 *  2. ask `/app/pr/{id}/replay` for everything after our last (session, seq);
 *  3. apply the replay batch, then the buffered live messages in seq order, then go live.
 *
 * A message from a newer review session (re-review) resets the stream; duplicates are dropped by seq.
 */
export function useReviewStream(prId: PrId): StreamState {
  const { client, connected } = useWebSocket();
  const [state, setState] = useState<StreamState>(EMPTY_STREAM);
  const stateRef = useRef<StreamState>(EMPTY_STREAM);
  const [, startTransition] = useTransition();

  const update = (fn: (s: StreamState) => StreamState) => {
    stateRef.current = fn(stateRef.current);
    const next = stateRef.current;
    startTransition(() => setState(next));
  };

  // Reset accumulated state when switching PRs.
  useEffect(() => {
    stateRef.current = EMPTY_STREAM;
    setState(EMPTY_STREAM);
  }, [prId]);

  useEffect(() => {
    if (!connected) return;
    let replaying = true;
    const pending: ReviewTopicMessage[] = [];

    const onTopic = (body: string) => {
      const parsed = ReviewTopicMessageSchema.safeParse(JSON.parse(body));
      if (!parsed.success) return;
      const msg = parsed.data;
      if (msg.type === 'error') {
        toast.error(`Review of ${msg.file || 'a file'} failed for one chunk; other findings are unaffected.`);
      }
      if (replaying) {
        pending.push(msg);
        return;
      }
      update((s) => applyMessage(s, msg));
    };

    const reviewSub = client.subscribe(`/topic/pr/${prId}/review`, (m) => onTopic(m.body));
    const statusSub = client.subscribe(`/topic/pr/${prId}/status`, (m) => {
      const parsed = ReviewStatusMessageSchema.safeParse(JSON.parse(m.body));
      if (parsed.success) update((s) => applyStatus(s, parsed.data));
    });

    const { sessionId, lastSeq } = stateRef.current;
    const replaySub = client.subscribe(
      `/app/pr/${prId}/replay`,
      (m) => {
        const parsed = ReplayBatchSchema.safeParse(JSON.parse(m.body));
        replaying = false;
        if (parsed.success) {
          const items = parsed.data.items
            .map((i) => ReviewTopicMessageSchema.safeParse(i))
            .filter((r) => r.success)
            .map((r) => r.data as ReviewTopicMessage);
          const batch: ReplayBatch = { ...parsed.data, items };
          if (batch.sessionId > 0) {
            update((s) => applyReplay(s, batch, pending));
          } else {
            update((s) => pending.reduce(applyMessage, s));
          }
        } else {
          update((s) => pending.reduce(applyMessage, s));
        }
        pending.length = 0;
        replaySub.unsubscribe();
      },
      { 'after-seq': String(lastSeq), 'session-id': String(sessionId ?? 0) },
    );

    return () => {
      reviewSub.unsubscribe();
      statusSub.unsubscribe();
      try {
        replaySub.unsubscribe();
      } catch {
        // already unsubscribed after the reply
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `update` only touches refs/setters
  }, [client, connected, prId]);

  return state;
}
