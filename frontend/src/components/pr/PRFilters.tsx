import { Search } from 'lucide-react';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useUiStore } from '@/store/uiStore';
import type { ReviewStatus } from '@/types/domain';

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: 'ALL', label: 'All statuses' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'IN_PROGRESS', label: 'Reviewing' },
  { value: 'COMPLETED', label: 'Reviewed' },
  { value: 'REVIEW_FAILED', label: 'Failed' },
  { value: 'REVIEW_UNAVAILABLE', label: 'Unavailable' },
];

export function PRFilters() {
  const { statusFilter, search, setStatusFilter, setSearch } = useUiStore();
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
      <div className="relative flex-1">
        <Search
          className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden
        />
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by title or author…"
          className="pl-8"
          aria-label="Search pull requests"
        />
      </div>
      <Select value={statusFilter} onValueChange={setStatusFilter}>
        <SelectTrigger className="sm:w-48" aria-label="Filter by status">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {STATUS_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

/** Pure filter so it can be unit tested and reused. */
export function filterPullRequests<T extends { title: string; author: string; status: ReviewStatus }>(
  prs: T[],
  statusFilter: string,
  search: string,
): T[] {
  const q = search.trim().toLowerCase();
  return prs.filter((pr) => {
    if (statusFilter !== 'ALL' && pr.status !== statusFilter) return false;
    if (q && !pr.title.toLowerCase().includes(q) && !pr.author.toLowerCase().includes(q)) return false;
    return true;
  });
}
