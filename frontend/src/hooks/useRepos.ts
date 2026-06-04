import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { repoService } from '@/services/repoService';
import { RepoId, type Repository } from '@/types/domain';

export function useRepos() {
  return useQuery<Repository[]>({
    queryKey: ['repos'],
    queryFn: repoService.list,
  });
}

export function useConnectRepo() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fullName: string) => repoService.connect(fullName),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['repos'] });
      toast.success('Repository connected');
    },
    onError: () => toast.error('Could not connect repository'),
  });
}

export function useDisconnectRepo() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: RepoId) => repoService.disconnect(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['repos'] });
      toast.success('Repository disconnected');
    },
    onError: () => toast.error('Could not disconnect repository'),
  });
}
