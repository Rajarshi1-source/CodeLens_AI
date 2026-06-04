import { AlertTriangle, Clock, GitPullRequest } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatsCard } from '@/components/dashboard/StatsCard';
import { SeverityChart } from '@/components/dashboard/SeverityChart';
import { ReviewCoverage } from '@/components/dashboard/ReviewCoverage';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/ui/skeleton';
import { useDashboard } from '@/hooks/useDashboard';

export function Dashboard() {
  const { data, isLoading, isError, refetch } = useDashboard();

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-28" />
        ))}
      </div>
    );
  }
  if (isError || !data) return <ErrorState message="Could not load dashboard stats." onRetry={() => void refetch()} />;

  const avg = data.avgReviewTimeMs == null ? '—' : `${(data.avgReviewTimeMs / 1000).toFixed(1)}s`;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatsCard label="Total PRs" value={data.totalPullRequests} icon={<GitPullRequest className="h-4 w-4" />} />
        <StatsCard label="Reviewed" value={data.reviewedPullRequests} />
        <StatsCard label="Avg review time" value={avg} icon={<Clock className="h-4 w-4" />} />
        <StatsCard
          label="Critical findings"
          value={data.severityCounts.CRITICAL ?? 0}
          accentClassName="text-[color:var(--severity-critical)]"
          icon={<AlertTriangle className="h-4 w-4" />}
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Findings by severity</CardTitle>
          </CardHeader>
          <CardContent>
            <SeverityChart counts={data.severityCounts} />
          </CardContent>
        </Card>
        <ReviewCoverage reviewed={data.reviewedPullRequests} pending={data.pendingPullRequests} />
      </div>
    </div>
  );
}
