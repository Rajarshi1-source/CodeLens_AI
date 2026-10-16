import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { formatUsd } from '@/components/review/CostPanel';
import type { DashboardStats } from '@/types/domain';

/** Today's LLM token budget (enforced atomically in Redis) and spend. */
export function BudgetCard({ stats }: { stats: DashboardStats }) {
  const { dailyLimit, usedToday, remaining } = stats.budget;
  const pct = dailyLimit === 0 ? 0 : Math.min(100, Math.round((usedToday / dailyLimit) * 100));
  return (
    <Card data-testid="budget-card">
      <CardHeader>
        <CardTitle>LLM budget today</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-baseline justify-between">
          <span className="text-3xl font-semibold">{pct}%</span>
          <span className="text-sm text-muted-foreground">
            {remaining.toLocaleString()} of {dailyLimit.toLocaleString()} tokens left
          </span>
        </div>
        <progress
          className="coverage-bar h-2.5 w-full"
          value={pct}
          max={100}
          aria-label={`Budget used: ${pct}%`}
        />
        <div className="grid grid-cols-2 gap-2 text-sm">
          <div>
            <div className="text-xs text-muted-foreground">Spend today</div>
            <div className="font-medium">{formatUsd(stats.costTodayUsd)}</div>
          </div>
          <div>
            <div className="text-xs text-muted-foreground">Last 30 days</div>
            <div className="font-medium">{formatUsd(stats.cost30dUsd)}</div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
