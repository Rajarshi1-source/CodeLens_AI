import { useMemo } from 'react';
import ReactDiffViewer from 'react-diff-viewer-continued';
import { FileCode } from 'lucide-react';
import { InlineComment } from './InlineComment';
import { EmptyState } from '@/components/common/EmptyState';
import { parseDiff } from '@/lib/parseDiff';
import { useUiStore } from '@/store/uiStore';
import type { StreamingComment } from '@/types/domain';

/**
 * Renders the PR's side-by-side diff (via `react-diff-viewer-continued`) with the review findings
 * for each file shown alongside it. When no diff is available (local mock provider / missing GitHub
 * token / fetch degraded) it falls back to a findings-grouped list — the honest view of the data
 * we actually have.
 */
export function DiffViewer({ diff, comments }: { diff?: string; comments: StreamingComment[] }) {
  const theme = useUiStore((s) => s.theme);
  const files = useMemo(() => parseDiff(diff ?? ''), [diff]);

  const commentsByFile = useMemo(() => groupByFile(comments), [comments]);

  if (files.length === 0) {
    return <FindingsList comments={comments} />;
  }

  const filePaths = new Set(files.map((f) => f.path));
  const orphanComments = comments.filter((c) => !filePaths.has(c.file));

  return (
    <div className="flex flex-col gap-6 p-4">
      {files.map((file) => {
        const fileComments = commentsByFile.get(file.path) ?? [];
        return (
          <section key={file.path}>
            <header className="mb-2 flex items-center gap-2 rounded-md bg-[var(--diff-gutter)] px-3 py-2">
              <FileCode className="h-4 w-4 text-muted-foreground" aria-hidden />
              <h3 className="truncate font-mono text-sm font-medium">{file.path}</h3>
              {fileComments.length > 0 && (
                <span className="ml-auto text-xs text-muted-foreground">
                  {fileComments.length} finding{fileComments.length === 1 ? '' : 's'}
                </span>
              )}
            </header>
            {file.isBinary ? (
              <p className="px-3 py-4 text-sm text-muted-foreground">Binary file not shown.</p>
            ) : (
              <div className="overflow-x-auto rounded-md border text-sm">
                <ReactDiffViewer
                  oldValue={file.oldValue}
                  newValue={file.newValue}
                  splitView
                  useDarkTheme={theme === 'dark'}
                  leftTitle="Before"
                  rightTitle="After"
                />
              </div>
            )}
            {fileComments.length > 0 && (
              <div className="mt-3 flex flex-col gap-3">
                {fileComments.map((c) => (
                  <InlineComment key={`${c.file}:${c.line}`} comment={c} />
                ))}
              </div>
            )}
          </section>
        );
      })}

      {orphanComments.length > 0 && (
        <section>
          <header className="mb-2 flex items-center gap-2 rounded-md bg-[var(--diff-gutter)] px-3 py-2">
            <FileCode className="h-4 w-4 text-muted-foreground" aria-hidden />
            <h3 className="font-mono text-sm font-medium">Other findings</h3>
          </header>
          <div className="flex flex-col gap-3">
            {orphanComments.map((c) => (
              <InlineComment key={`${c.file}:${c.line}`} comment={c} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function groupByFile(comments: StreamingComment[]): Map<string, StreamingComment[]> {
  const map = new Map<string, StreamingComment[]>();
  for (const c of comments) {
    const list = map.get(c.file) ?? [];
    list.push(c);
    map.set(c.file, list);
  }
  for (const list of map.values()) list.sort((a, b) => a.line - b.line);
  return map;
}

/** Fallback: findings grouped by file when no raw diff is available. */
function FindingsList({ comments }: { comments: StreamingComment[] }) {
  const byFile = useMemo(
    () => [...groupByFile(comments).entries()].sort(([a], [b]) => a.localeCompare(b)),
    [comments],
  );

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
