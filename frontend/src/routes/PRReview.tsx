import { useMemo, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ExternalLink, RefreshCw } from 'lucide-react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { DiffViewer } from '@/components/diff/DiffViewer';
import { CommentRail } from '@/components/diff/CommentRail';
import { SeverityBadge } from '@/components/diff/SeverityBadge';
import { PRStatusBadge } from '@/components/pr/PRStatusBadge';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { ErrorState } from '@/components/common/ErrorState';
import { usePrReview } from '@/hooks/usePrReview';
import { usePullRequest, useReReview } from '@/hooks/usePullRequests';
import { SEVERITY_ORDER } from '@/lib/severity';
import { PrId, type ReviewStatus, type Severity } from '@/types/domain';

export function PRReview() {
  const { id = '' } = useParams();
  const prId = PrId(id);

  const pr = usePullRequest(prId);
  const { comments, status, isLoading } = usePrReview(prId);
  const reReview = useReReview();

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
        <div className="ml-auto flex items-center gap-2">
          <PRStatusBadge status={effectiveStatus} />
          {pr.data.htmlUrl && (
            <Button variant="outline" size="sm" asChild>
              <a href={pr.data.htmlUrl} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                GitHub
              </a>
            </Button>
          )}
          <Button
            size="sm"
            onClick={() => reReview.mutate(prId)}
            disabled={reReview.isPending || effectiveStatus === 'IN_PROGRESS'}
          >
            <RefreshCw className="h-4 w-4" aria-hidden />
            Re-review
          </Button>
        </div>
      </div>

      <Tabs defaultValue="diff" className="flex min-h-0 flex-1 flex-col">
        <TabsList className="self-start">
          <TabsTrigger value="diff">Findings</TabsTrigger>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="comments">Comments ({comments.length})</TabsTrigger>
        </TabsList>

        <TabsContent value="diff" className="mt-3 min-h-0 flex-1">
          <div className="grid h-full grid-cols-1 gap-0 overflow-hidden rounded-lg border lg:grid-cols-[1fr_360px]">
            <ScrollArea className="h-full">
              <DiffViewer comments={comments} />
            </ScrollArea>
            <ScrollArea className="hidden h-full border-l lg:block">
              <CommentRail comments={comments} />
            </ScrollArea>
          </div>
        </TabsContent>

        <TabsContent value="summary" className="mt-3 min-h-0 flex-1">
          <Card>
            <CardHeader>
              <CardTitle>Review summary</CardTitle>
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
        </TabsContent>

        <TabsContent value="comments" className="mt-3 min-h-0 flex-1">
          <ScrollArea className="h-full rounded-lg border">
            <CommentRail comments={comments} />
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
