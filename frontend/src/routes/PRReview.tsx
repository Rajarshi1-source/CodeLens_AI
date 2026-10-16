import { useMemo, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ExternalLink, RefreshCw } from 'lucide-react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { DiffViewer } from '@/components/diff/DiffViewer';
import { CommentRail } from '@/components/diff/CommentRail';
import { SeverityBadge } from '@/components/diff/SeverityBadge';
import { PRStatusBadge } from '@/components/pr/PRStatusBadge';
import { RiskBadge } from '@/components/pr/RiskBadge';
import { PresenceAvatars } from '@/components/presence/PresenceAvatars';
import { RiskPanel } from '@/components/review/RiskPanel';
import { CostPanel } from '@/components/review/CostPanel';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { usePrReview, usePrSummary } from '@/hooks/usePrReview';
import { usePrDiff, usePullRequest, useReReview } from '@/hooks/usePullRequests';
import { usePresence } from '@/hooks/usePresence';
import { useAuth } from '@/hooks/useAuth';
import { SEVERITY_ORDER } from '@/lib/severity';
import { PrId, type ReviewStatus, type Severity } from '@/types/domain';

export function PRReview() {
  const { id = '' } = useParams();
  const prId = PrId(id);

  const { user } = useAuth();
  const pr = usePullRequest(prId);
  const { comments, status, reason, isLoading } = usePrReview(prId);
  const summary = usePrSummary(prId);
  const diff = usePrDiff(prId);
  const reReview = useReReview();
  const presence = usePresence(prId, user?.login);

  const severityCounts = useMemo(() => {
    const counts: Record<Severity, number> = { CRITICAL: 0, WARNING: 0, SUGGESTION: 0 };
    for (const c of comments) counts[c.severity] += 1;
    return counts;
  }, [comments]);

  if (pr.isLoading || isLoading) return <LoadingSpinner label="Loading review…" />;
  if (pr.isError || !pr.data) {
    return <ErrorState message="Could not load this pull request." onRetry={() => void pr.refetch()} />;
  }

  // Live status (from WebSocket) supersedes the persisted PR status once streaming begins.
  const effectiveStatus: ReviewStatus = status ?? pr.data.status;
  const reviewing = effectiveStatus === 'IN_PROGRESS';

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col">
      <div className="flex flex-wrap items-center gap-3 pb-3">
        <Button variant="ghost" size="sm" asChild>
          <Link to="/prs">
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Back
          </Link>
        </Button>
        <div className="min-w-0">
          <h1 className="truncate text-lg font-semibold">
            <span className="font-mono text-muted-foreground">#{pr.data.number}</span> {pr.data.title}
          </h1>
          <p className="text-sm text-muted-foreground">by {pr.data.author}</p>
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-3">
          <PresenceAvatars viewers={presence.others} />
          {!reviewing && <RiskBadge level={pr.data.riskLevel} score={pr.data.riskScore} />}
          <PRStatusBadge status={effectiveStatus} />
          {pr.data.htmlUrl && (
            <Button variant="outline" size="sm" asChild>
              <a href={pr.data.htmlUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                GitHub
              </a>
            </Button>
          )}
          <Button size="sm" onClick={() => reReview.mutate(prId)} disabled={reReview.isPending || reviewing}>
            <RefreshCw className={reviewing ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} aria-hidden />
            {reviewing ? 'Reviewing…' : 'Re-review'}
          </Button>
        </div>
      </div>

      {status && status !== 'IN_PROGRESS' && status !== 'COMPLETED' && reason && (
        <div role="alert" className="mb-3 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm">
          Review {status === 'REVIEW_UNAVAILABLE' ? 'unavailable' : 'failed'}: {reason}
        </div>
      )}

      <Tabs defaultValue="diff" className="flex min-h-0 flex-1 flex-col">
        <TabsList className="self-start">
          <TabsTrigger value="diff">Diff</TabsTrigger>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="comments">Comments ({comments.length})</TabsTrigger>
        </TabsList>

        <TabsContent value="diff" className="mt-3 min-h-0 flex-1">
          <div className="grid h-full grid-cols-1 gap-0 overflow-hidden rounded-lg border lg:grid-cols-[minmax(0,1fr)_340px]">
            <ScrollArea className="h-full min-w-0">
              {diff.isLoading ? (
                <div className="space-y-2 p-4">
                  <Skeleton className="h-6" />
                  <Skeleton className="h-48" />
                </div>
              ) : (
                <DiffViewer
                  diff={diff.data}
                  comments={comments}
                  prId={prId}
                  presence={presence.others}
                  onFocus={presence.focus}
                />
              )}
            </ScrollArea>
            <ScrollArea className="hidden h-full border-l lg:block">
              <CommentRail comments={comments} prId={prId} />
            </ScrollArea>
          </div>
        </TabsContent>

        <TabsContent value="summary" className="mt-3 min-h-0 flex-1 overflow-auto">
          <div className="grid gap-4 lg:grid-cols-2">
            {summary.isLoading ? (
              <Skeleton className="h-64" />
            ) : summary.data ? (
              <RiskPanel summary={summary.data} />
            ) : (
              <Card>
                <CardContent className="pt-6">
                  <EmptyState title="Not reviewed yet" description="Risk and cost appear once a review completes." />
                </CardContent>
              </Card>
            )}
            <div className="space-y-4">
              <Card>
                <CardHeader>
                  <CardTitle>Change overview</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
                    <Stat label="Files changed" value={pr.data.filesChanged} />
                    <Stat label="Additions" value={`+${pr.data.additions}`} />
                    <Stat label="Deletions" value={`−${pr.data.deletions}`} />
                    <Stat label="Findings" value={comments.length} />
                  </div>
                  <Separator />
                  <div className="flex flex-wrap items-center gap-4">
                    {SEVERITY_ORDER.map((sev) => (
                      <div key={sev} className="flex items-center gap-2">
                        <SeverityBadge severity={sev} />
                        <span className="text-sm font-medium">{severityCounts[sev]}</span>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>
              {summary.data && <CostPanel summary={summary.data} />}
            </div>
          </div>
        </TabsContent>

        <TabsContent value="comments" className="mt-3 min-h-0 flex-1">
          <ScrollArea className="h-full rounded-lg border">
            <CommentRail comments={comments} prId={prId} />
          </ScrollArea>
        </TabsContent>
      </Tabs>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <div className="text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold">{value}</div>
    </div>
  );
}
