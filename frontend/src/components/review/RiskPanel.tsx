import { AlertTriangle, ShieldCheck } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { RiskBadge } from '@/components/pr/RiskBadge';
import { RISK_STYLES } from '@/lib/risk';
import type { ReviewSummary } from '@/types/domain';

const FAILURE_COPY: Record<string, string> = {
  BUDGET_EXCEEDED: 'The daily LLM token budget is exhausted, so this review could not run. It will work again tomorrow (UTC) or after the budget is raised.',
  LLM_UNAVAILABLE: 'The AI provider (and its fallback) were unavailable. Try re-reviewing in a moment.',
  RETRIES_EXHAUSTED: 'The review failed repeatedly and was moved to the dead-letter queue. Re-review to try again.',
};

/** G3: the explainable risk score — level, reasons with their points, the recommendation and the summary. */
export function RiskPanel({ summary }: { summary: ReviewSummary }) {
  const level = summary.riskLevel;
  return (
    <Card data-testid="risk-panel">
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="flex items-center gap-2">
          {level === 'HIGH' ? <AlertTriangle className="h-4 w-4" aria-hidden /> : <ShieldCheck className="h-4 w-4" aria-hidden />}
          Risk assessment
        </CardTitle>
        <RiskBadge level={level} score={summary.riskScore} />
      </CardHeader>
      <CardContent className="space-y-4">
        {summary.failureReason && (
          <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm" data-testid="failure-banner">
            {FAILURE_COPY[summary.failureReason] ?? `Review unavailable: ${summary.failureReason}`}
          </div>
        )}
        {level && summary.riskScore != null && (
          <div>
            <div className="mb-1 flex justify-between text-xs text-muted-foreground">
              <span>Score</span>
              <span className="font-mono">{summary.riskScore}/100</span>
            </div>
            <progress
              className="risk-bar h-2 w-full"
              data-risk={level}
              value={summary.riskScore}
              max={100}
              aria-label={`${RISK_STYLES[level].label}: ${summary.riskScore} out of 100`}
            />
          </div>
        )}
        {summary.riskReasons && summary.riskReasons.length > 0 && (
          <ul className="space-y-1.5 text-sm" data-testid="risk-reasons">
            {summary.riskReasons.map((r) => (
              <li key={r.code} className="flex items-start justify-between gap-3">
                <span>{r.detail}</span>
                <span className="shrink-0 font-mono text-xs text-muted-foreground">
                  {r.points > 0 ? '+' : ''}
                  {r.points}
                </span>
              </li>
            ))}
          </ul>
        )}
        {summary.recommendedAction && (
          <p className="rounded-md bg-muted px-3 py-2 text-sm">
            <span className="font-medium">Recommended: </span>
            {summary.recommendedAction}
          </p>
        )}
        {summary.summary && (
          <p className="text-sm leading-relaxed text-muted-foreground" data-testid="review-summary-text">
            {summary.summary}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
