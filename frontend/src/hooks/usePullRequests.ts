import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { prService } from '@/services/prService';
import { PrId, type PullRequest } from '@/types/domain';

export function usePullRequests() {
  return useQuery<PullRequest[]>({
    queryKey: ['prs'],
    queryFn: prService.list,
    staleTime: 15_000,
  });
}

export function usePullRequest(prId: PrId) {
  return useQuery<PullRequest>({
    queryKey: ['pr', prId],
    queryFn: () => prService.get(prId),
  });
}

/** Re-review mutation; invalidates the PR lists + this PR's comments (read-your-writes). */
export function useReReview() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (prId: PrId) => prService.reReview(prId),
    onSuccess: (_data, prId) => {
      qc.invalidateQueries({ queryKey: ['prs'] });
      qc.invalidateQueries({ queryKey: ['pr', prId] });
      qc.invalidateQueries({ queryKey: ['comments', prId] });
      toast.success('Re-review queued');
    },
    onError: () => toast.error('Could not queue re-review'),
  });
}
