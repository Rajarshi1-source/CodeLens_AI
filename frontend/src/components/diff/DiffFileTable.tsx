import { Fragment, useMemo, useState, type ReactNode } from 'react';
import { InlineComment } from './InlineComment';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { toSplitRows, type DiffRow, type FileDiff } from '@/lib/parseDiff';
import type { DiffMode } from '@/store/uiStore';
import type { PresenceUser } from '@/hooks/usePresence';
import type { PrId, StreamingComment } from '@/types/domain';

const COLLAPSE_ROWS = 1500;

const ROW_BG: Record<DiffRow['kind'], string> = {
  add: 'bg-[var(--diff-added)]',
  del: 'bg-[var(--diff-removed)]',
  context: '',
};
const MARK: Record<DiffRow['kind'], string> = { add: '+', del: '-', context: ' ' };

/**
 * One file of the diff as a table, in split or unified mode, with each AI comment rendered in a full-width
 * row directly under the new-file line it is anchored to (line numbers are absolute, from the hunk headers).
 * Hovering a line reports focus for collaborative presence; other viewers' focus shows as an avatar in the
 * gutter.
 */
export function DiffFileTable({
  file,
  comments,
  mode,
  prId,
  focusByLine,
  onFocusLine,
}: {
  file: FileDiff;
  comments: StreamingComment[];
  mode: DiffMode;
  prId?: PrId;
  focusByLine?: Map<number, PresenceUser[]>;
  onFocusLine?: (line: number | null) => void;
}) {
  const totalRows = file.hunks.reduce((n, h) => n + h.rows.length, 0);
  const [expanded, setExpanded] = useState(totalRows <= COLLAPSE_ROWS);
  const byLine = useMemo(() => {
    const m = new Map<number, StreamingComment[]>();
    for (const c of comments) m.set(c.line, [...(m.get(c.line) ?? []), c]);
    return m;
  }, [comments]);

  if (!expanded) {
    return (
      <div className="flex items-center justify-between rounded-md border px-3 py-3 text-sm text-muted-foreground">
        Large diff ({totalRows} lines) collapsed.
        <Button size="sm" variant="outline" onClick={() => setExpanded(true)}>
          Expand
        </Button>
      </div>
    );
  }

  const cols = mode === 'split' ? 4 : 3;
  const commentRow = (newNo: number | undefined, key: string): ReactNode => {
    if (newNo === undefined) return null;
    const list = byLine.get(newNo);
    if (!list?.length) return null;
    return (
      <tr key={key} data-testid="comment-row" data-after-line={newNo}>
        <td colSpan={cols} className="border-y bg-muted/40 px-3 py-2 font-sans">
          <div className="flex max-w-3xl flex-col gap-2">
            {list.map((c) => (
              <InlineComment key={c.key} comment={c} prId={prId} showAnchor={false} />
            ))}
          </div>
        </td>
      </tr>
    );
  };

  const gutter = (no: number | undefined, focusLine?: number) => {
    const viewers = focusLine !== undefined ? focusByLine?.get(focusLine) : undefined;
    return (
      <td className="relative w-12 select-none border-r bg-[var(--diff-gutter)] px-2 text-right text-muted-foreground">
        {viewers?.length ? (
          <span
            className="absolute left-0.5 top-1/2 flex -translate-y-1/2"
            data-testid="focus-marker"
            title={`${viewers.map((v) => v.login).join(', ')} looking here`}
          >
            {viewers.slice(0, 2).map((v) => (
              <img key={v.login} src={v.avatarUrl ?? ''} alt="" className="size-3.5 rounded-full ring-1 ring-background" />
            ))}
          </span>
        ) : null}
        {no ?? ''}
      </td>
    );
  };

  const cell = (row: DiffRow | undefined) => (
    <td className={cn('whitespace-pre px-2', row ? ROW_BG[row.kind] : 'bg-muted/30')}>
      {row ? (
        <>
          <span aria-hidden className="mr-2 select-none text-muted-foreground">
            {MARK[row.kind]}
          </span>
          {row.text}
        </>
      ) : null}
    </td>
  );

  return (
    <div className="overflow-x-auto rounded-md border" onMouseLeave={() => onFocusLine?.(null)}>
      <table className="w-full border-collapse font-mono text-xs leading-5" data-testid="diff-table" data-mode={mode}>
        <tbody>
          {file.hunks.map((hunk, hi) => (
            <Fragment key={hi}>
              <tr>
                <td colSpan={cols} className="bg-[var(--diff-gutter)] px-3 py-1 text-muted-foreground">
                  {hunk.header}
                </td>
              </tr>
              {mode === 'split'
                ? toSplitRows(hunk.rows).map((r, ri) => {
                    const anchor = r.right?.newNo;
                    return (
                      <Fragment key={`${hi}-${ri}`}>
                        <tr
                          data-new-line={anchor}
                          onMouseEnter={() => anchor !== undefined && onFocusLine?.(anchor)}
                        >
                          {gutter(r.left?.oldNo)}
                          {cell(r.left)}
                          {gutter(r.right?.newNo, anchor)}
                          {cell(r.right)}
                        </tr>
                        {commentRow(anchor, `c-${hi}-${ri}`)}
                      </Fragment>
                    );
                  })
                : hunk.rows.map((row, ri) => (
                    <Fragment key={`${hi}-${ri}`}>
                      <tr
                        data-new-line={row.newNo}
                        onMouseEnter={() => row.newNo !== undefined && onFocusLine?.(row.newNo)}
                      >
                        {gutter(row.oldNo)}
                        {gutter(row.newNo, row.newNo)}
                        {cell(row)}
                      </tr>
                      {commentRow(row.kind === 'del' ? undefined : row.newNo, `c-${hi}-${ri}`)}
                    </Fragment>
                  ))}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}
