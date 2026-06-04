import { getJson } from './api';
import { DashboardStatsSchema } from '@/types/schemas';
import type { DashboardStats } from '@/types/domain';

export const dashboardService = {
  stats: (): Promise<DashboardStats> => getJson('/api/dashboard/stats', DashboardStatsSchema),
};
