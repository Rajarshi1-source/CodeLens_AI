import { useQuery, useQueryClient } from '@tanstack/react-query';
import { authService } from '@/services/authService';
import { UnauthorizedError } from '@/services/api';
import type { CurrentUser } from '@/types/domain';

export function useAuth() {
  const query = useQuery<CurrentUser>({
    queryKey: ['auth'],
    queryFn: authService.me,
    retry: (failureCount, error) => !(error instanceof UnauthorizedError) && failureCount < 2,
    staleTime: 60_000,
  });

  const isUnauthorized = query.error instanceof UnauthorizedError;

  return {
    user: query.data ?? null,
    isLoading: query.isLoading,
    isAuthenticated: !!query.data,
    isUnauthorized,
    error: query.error,
  };
}

export function useLogout() {
  const qc = useQueryClient();
  return async () => {
    await authService.logout();
    qc.clear();
    window.location.href = '/';
  };
}
