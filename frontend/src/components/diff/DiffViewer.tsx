import { useMemo } from 'react';
import { FileCode } from 'lucide-react';
import { InlineComment } from './InlineComment';
import { EmptyState } from '@/components/common/EmptyState';
import type { StreamingComment } from '@/types/domain';

/**
 * Renders review findings grouped by file and anchored by line.
 *
 * NOTE: the MVP backend does not expose raw diff content (no /api/prs/{id}/diff), so a side-by-side
 * `react-diff-viewer-continued` render has nothing to show. This file-grouped, line-anchored view is
 * the honest representation of the data we have; swap in the diff viewer once a diff endpoint lands.
 */
export function DiffViewer({ comments }: { comments: StreamingComment[] }) {
  const byFile = useMemo(() => {
    const map = new Map<string, StreamingComment[]>();
    for (const c of comments) {
      const list = map.get(c.file) ?? [];
      list.push(c);
      map.set(c.file, list);
    }
    for (const list of map.values()) list.sort((a, b) => a.line - b.line);
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [comments]);

  if (byFile.length === 0) {
    return (
      <div className="p-6">
        <EmptyState
          icon={<FileCode className="h-6 w-6" />}
          title="No findings to show"
          description="When the review runs, findings appear here grouped by file."
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6 p-4">
      {byFile.map(([file, fileComments]) => (
        <section key={file}>
          <header className="sticky top-0 z-10 mb-2 flex items-center gap-2 rounded-md bg-[var(--diff-gutter)] px-3 py-2">
            <FileCode className="h-4 w-4 text-muted-foreground" aria-hidden />
            <h3 className="font-mono text-sm font-medium">{file}</h3>
            <span className="ml-auto text-xs text-muted-foreground">
              {fileComments.length} finding{fileComments.length === 1 ? '' : 's'}
            </span>
          </header>
          <div className="flex flex-col gap-3">
            {fileComments.map((c) => (
              <InlineComment key={`${c.file}:${c.line}`} comment={c} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
