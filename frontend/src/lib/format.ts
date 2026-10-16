import type { Repository } from '@/types/domain';

/** USD for LLM spend: sub-cent amounts keep 4 decimals so a $0.0031 review does not read as $0.00. */
export function formatUsd(v: number | null | undefined): string {
  if (v == null) return '—';
  if (v === 0) return '$0.00';
  return v < 0.01 ? `$${v.toFixed(4)}` : `$${v.toFixed(2)}`;
}

/** Human-readable state of the repo's retrieval index (G1). */
export function describeIndex(repo: Pick<Repository, 'indexStatus' | 'indexedChunks' | 'indexedAt'>): string {
  switch (repo.indexStatus) {
    case 'READY':
      return `Indexed · ${(repo.indexedChunks ?? 0).toLocaleString()} chunks${
        repo.indexedAt ? ` · ${new Date(repo.indexedAt).toLocaleDateString()}` : ''
      }`;
    case 'QUEUED':
      return 'Index queued';
    case 'INDEXING':
      return 'Indexing…';
    case 'FAILED':
      return 'Index failed';
    default:
      return 'Not indexed';
  }
}
