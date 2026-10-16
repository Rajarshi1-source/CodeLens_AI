import { describe, expect, it } from 'vitest';
import { filterPullRequests } from './PRFilters';
import type { ReviewStatus, RiskLevel } from '@/types/domain';

interface Row {
  title: string;
  author: string;
  status: ReviewStatus;
  riskLevel: RiskLevel | null;
}

const rows: Row[] = [
  { title: 'Fix login bug', author: 'alice', status: 'COMPLETED', riskLevel: 'HIGH' },
  { title: 'Add caching', author: 'bob', status: 'IN_PROGRESS', riskLevel: null },
  { title: 'Refactor parser', author: 'carol', status: 'PENDING', riskLevel: 'LOW' },
];

describe('filterPullRequests', () => {
  it('returns all rows when filter is ALL and search empty', () => {
    expect(filterPullRequests(rows, 'ALL', '')).toHaveLength(3);
  });

  it('filters by status', () => {
    const out = filterPullRequests(rows, 'IN_PROGRESS', '');
    expect(out).toEqual([rows[1]]);
  });

  it('searches title and author case-insensitively', () => {
    expect(filterPullRequests(rows, 'ALL', 'LOGIN')).toEqual([rows[0]]);
    expect(filterPullRequests(rows, 'ALL', 'bob')).toEqual([rows[1]]);
  });

  it('combines status and search', () => {
    expect(filterPullRequests(rows, 'COMPLETED', 'parser')).toHaveLength(0);
    expect(filterPullRequests(rows, 'COMPLETED', 'login')).toEqual([rows[0]]);
  });

  it('filters by risk band, excluding unscored PRs', () => {
    expect(filterPullRequests(rows, 'ALL', '', 'HIGH')).toEqual([rows[0]]);
    expect(filterPullRequests(rows, 'ALL', '', 'MEDIUM')).toEqual([]);
    expect(filterPullRequests(rows, 'ALL', '', 'ALL')).toHaveLength(3);
  });
});
