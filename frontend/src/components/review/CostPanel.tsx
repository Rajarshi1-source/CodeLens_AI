import type { ReactNode } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { ReviewSummary } from '@/types/domain';

export function formatUsd(v: number | null | undefined): string {
  if (v == null) return '—';
  if (v === 0) return '$0.00';
  return v < 0.01 ? `$${v.toFixed(4)}` : `$${v.toFixed(2)}`;
}

/** What the review cost: model(s) that served it, tokens in/out, USD, and how much repo context was used. */
export function CostPanel({ summary }: { summary: ReviewSummary }) {
  const servedBy = Object.entries(summary.servedBy ?? {});
  return (
    <Card data-testid="cost-panel">
      <CardHeader>
        <CardTitle>Model &amp; cost</CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-4 text-sm">
        <Field label="Model">{summary.modelUsed ?? '—'}</Field>
        <Field label="Cost">{formatUsd(summary.costUsd)}</Field>
        <Field label="Input tokens">{summary.inputTokens?.toLocaleString() ?? '—'}</Field>
        <Field label="Output tokens">{summary.outputTokens?.toLocaleString() ?? '—'}</Field>
        <Field label="Review time">{summary.reviewTimeMs != null ? `${(summary.reviewTimeMs / 1000).toFixed(1)}s` : '—'}</Field>
        <Field label="Prompt">{summary.promptVersion ?? '—'}</Field>
        <Field label="Repo context">
          {summary.retrieval?.enabled ? `${summary.retrieval.snippets ?? 0} snippets` : 'not indexed'}
        </Field>
        <Field label="Served by">
          {servedBy.length === 0 ? '—' : servedBy.map(([m, n]) => `${m}${n > 1 ? ` ×${n}` : ''}`).join(', ')}
        </Field>
      </CardContent>
    </Card>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="truncate font-medium">{children}</div>
    </div>
  );
}
