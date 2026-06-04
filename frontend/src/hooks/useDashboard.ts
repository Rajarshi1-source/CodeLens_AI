import { useQuery } from '@tanstack/react-query';
import { dashboardService } from '@/services/dashboardService';
import type { DashboardStats } from '@/types/domain';

export function useDashboard() {
  return useQuery<DashboardStats>({
    queryKey: ['dashboard'],
    queryFn: dashboardService.stats,
    staleTime: 30_000,
  });
}
