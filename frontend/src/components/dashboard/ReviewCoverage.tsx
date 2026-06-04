import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

/**
 * Reviewed-vs-pending coverage bar. (The MVP backend exposes aggregate counts, not a time series,
 * so this stands in for a per-day ReviewTimeline until the backend provides one.)
 */
export function ReviewCoverage({ reviewed, pending }: { reviewed: number; pending: number }) {
  const total = reviewed + pending;
  const pct = total === 0 ? 0 : Math.round((reviewed / total) * 100);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Review coverage</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-baseline justify-between">
          <span className="text-3xl font-semibold">{pct}%</span>
          <span className="text-sm text-muted-foreground">
            {reviewed} reviewed · {pending} pending
          </span>
        </div>
        <progress
          className="coverage-bar h-2.5 w-full"
          value={pct}
          max={100}
          aria-label={`Review coverage: ${pct}% (${reviewed} reviewed, ${pending} pending)`}
        />
      </CardContent>
    </Card>
  );
}
