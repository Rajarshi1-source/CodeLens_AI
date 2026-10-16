import { useMemo } from 'react';
import { Columns2, FileCode, Rows3 } from 'lucide-react';
import { DiffFileTable } from './DiffFileTable';
import { InlineComment } from './InlineComment';
import { EmptyState } from '@/components/common/EmptyState';
import { Button } from '@/components/ui/button';
import { newLineNumbers, parseDiff } from '@/lib/parseDiff';
import { useUiStore } from '@/store/uiStore';
import type { PresenceUser } from '@/hooks/usePresence';
import type { PrId, StreamingComment } from '@/types/domain';

/**
 * The PR's diff (split or unified) with every AI finding rendered inline, directly under the line it is
 * anchored to. Findings whose anchor is not in the diff are listed under "Other findings". When no diff is
 * available at all (e.g. a GitHub fetch that degraded) it falls back to a findings list grouped by file.
 */
export function DiffViewer({
  diff,
  comments,
  prId,
  presence,
  onFocus,
}: {
  diff?: string;
  comments: StreamingComment[];
  prId?: PrId;
  presence?: PresenceUser[];
  onFocus?: (file: string | null, line: number | null) => void;
}) {
  const { diffMode, setDiffMode } = useUiStore();
  const files = useMemo(() => parseDiff(diff ?? ''), [diff]);
  const commentsByFile = useMemo(() => groupByFile(comments), [comments]);

  const anchored = useMemo(() => {
    const valid = new Map(files.map((f) => [f.path, newLineNumbers(f)] as const));
    const inline = new Map<string, StreamingComment[]>();
    const orphans: StreamingComment[] = [];
    for (const [file, list] of commentsByFile) {
      const lines = valid.get(file);
      for (const c of list) {
        if (lines?.has(c.line)) inline.set(file, [...(inline.get(file) ?? []), c]);
        else orphans.push(c);
      }
    }
    return { inline, orphans };
  }, [files, commentsByFile]);

  if (files.length === 0) {
    return <FindingsList comments={comments} prId={prId} />;
  }

  return (
    <div className="flex flex-col gap-6 p-4" data-testid="diff-viewer">
      <div className="flex items-center justify-end gap-1">
        <Button
          size="sm"
          variant={diffMode === 'split' ? 'secondary' : 'ghost'}
          aria-pressed={diffMode === 'split'}
          onClick={() => setDiffMode('split')}
        >
          <Columns2 className="h-4 w-4" aria-hidden />
          Split
        </Button>
        <Button
          size="sm"
          variant={diffMode === 'unified' ? 'secondary' : 'ghost'}
          aria-pressed={diffMode === 'unified'}
          onClick={() => setDiffMode('unified')}
        >
          <Rows3 className="h-4 w-4" aria-hidden />
          Unified
        </Button>
      </div>
      {files.map((file) => {
        const fileComments = anchored.inline.get(file.path) ?? [];
        const focusByLine = new Map<number, PresenceUser[]>();
        for (const v of presence ?? []) {
          if (v.file === file.path && v.line != null) focusByLine.set(v.line, [...(focusByLine.get(v.line) ?? []), v]);
        }
        return (
          <section key={file.path} data-testid="diff-file" data-path={file.path}>
            <header className="mb-2 flex items-center gap-2 rounded-md bg-[var(--diff-gutter)] px-3 py-2">
              <FileCode className="h-4 w-4 text-muted-foreground" aria-hidden />
              <h3 className="truncate font-mono text-sm font-medium">{file.path}</h3>
              {(commentsByFile.get(file.path)?.length ?? 0) > 0 && (
                <span className="ml-auto text-xs text-muted-foreground">
                  {commentsByFile.get(file.path)!.length} finding{commentsByFile.get(file.path)!.length === 1 ? '' : 's'}
                </span>
              )}
            </header>
            {file.isBinary ? (
              <p className="px-3 py-4 text-sm text-muted-foreground">Binary file not shown.</p>
            ) : (
              <DiffFileTable
                file={file}
                comments={fileComments}
                mode={diffMode}
                prId={prId}
                focusByLine={focusByLine}
                onFocusLine={(line) => onFocus?.(line === null ? null : file.path, line)}
              />
            )}
          </section>
        );
      })}

      {anchored.orphans.length > 0 && (
        <section data-testid="other-findings">
          <header className="mb-2 flex items-center gap-2 rounded-md bg-[var(--diff-gutter)] px-3 py-2">
            <FileCode className="h-4 w-4 text-muted-foreground" aria-hidden />
            <h3 className="font-mono text-sm font-medium">Other findings</h3>
          </header>
          <div className="flex flex-col gap-3">
            {anchored.orphans.map((c) => (
              <InlineComment key={c.key} comment={c} prId={prId} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function groupByFile(comments: StreamingComment[]): Map<string, StreamingComment[]> {
  const map = new Map<string, StreamingComment[]>();
  for (const c of comments) map.set(c.file, [...(map.get(c.file) ?? []), c]);
  for (const list of map.values()) list.sort((a, b) => a.line - b.line);
  return map;
}

/** Fallback: findings grouped by file when no raw diff is available. */
function FindingsList({ comments, prId }: { comments: StreamingComment[]; prId?: PrId }) {
  const byFile = useMemo(() => [...groupByFile(comments).entries()].sort(([a], [b]) => a.localeCompare(b)), [comments]);

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
    <div className="flex flex-col gap-6 p-4" data-testid="findings-list">
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
              <InlineComment key={c.key} comment={c} prId={prId} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
