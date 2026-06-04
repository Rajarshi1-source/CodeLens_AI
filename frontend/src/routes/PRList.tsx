import { useNavigate } from 'react-router-dom';
import { GitPullRequest } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { PRStatusBadge } from '@/components/pr/PRStatusBadge';
import { PRFilters, filterPullRequests } from '@/components/pr/PRFilters';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/ui/skeleton';
import { usePullRequests } from '@/hooks/usePullRequests';
import { useUiStore } from '@/store/uiStore';

export function PRList() {
  const { data, isLoading, isError, refetch } = usePullRequests();
  const navigate = useNavigate();
  const { statusFilter, search } = useUiStore();

  const filtered = filterPullRequests(data ?? [], statusFilter, search);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Pull Requests</h1>
      <PRFilters />

      {isLoading ? (
        <Skeleton className="h-64" />
      ) : isError ? (
        <ErrorState message="Could not load pull requests." onRetry={() => void refetch()} />
      ) : (data?.length ?? 0) === 0 ? (
        <EmptyState
          icon={<GitPullRequest className="h-6 w-6" />}
          title="No pull requests yet"
          description="Open a PR on a connected repository to see AI reviews here."
        />
      ) : filtered.length === 0 ? (
        <EmptyState title="No matching pull requests" description="Try adjusting your search or status filter." />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-16">PR</TableHead>
                <TableHead>Title</TableHead>
                <TableHead>Author</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Changes</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((pr) => (
                <TableRow
                  key={pr.id}
                  className="cursor-pointer"
                  onClick={() => navigate(`/prs/${pr.id}`)}
                >
                  <TableCell className="font-mono text-muted-foreground">#{pr.number}</TableCell>
                  <TableCell className="font-medium">{pr.title}</TableCell>
                  <TableCell>{pr.author}</TableCell>
                  <TableCell>
                    <PRStatusBadge status={pr.status} />
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs">
                    <span className="text-[var(--diff-added-text)]">+{pr.additions}</span>{' '}
                    <span className="text-[var(--diff-removed-text)]">−{pr.deletions}</span>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
