/**
 * Minimal unified-diff parser. Splits a `git diff` (GitHub's `application/vnd.github.v3.diff`) into
 * per-file blocks and hunks. Every row carries its ABSOLUTE old/new line numbers (from the `@@` headers),
 * so review comments — which are anchored on new-file line numbers — can be rendered directly under the
 * exact line they talk about.
 */

export type DiffRowKind = 'context' | 'add' | 'del';

export interface DiffRow {
  kind: DiffRowKind;
  oldNo?: number;
  newNo?: number;
  text: string;
}

export interface DiffHunk {
  header: string;
  oldStart: number;
  newStart: number;
  rows: DiffRow[];
}

export interface FileDiff {
  oldPath: string;
  newPath: string;
  /** Path to display (the new path, or the old path for deletions). */
  path: string;
  /** Reconstructed old/new sides (hunks joined), kept for simple consumers. */
  oldValue: string;
  newValue: string;
  isBinary: boolean;
  hunks: DiffHunk[];
}

/** One rendered row of the split view: left = old side, right = new side. */
export interface SplitRow {
  left?: DiffRow;
  right?: DiffRow;
}

const DEV_NULL = '/dev/null';
const HUNK_HEADER = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

function stripPrefix(raw: string): string {
  const p = raw.trim().split('\t')[0];
  if (p === DEV_NULL) return DEV_NULL;
  if (p.startsWith('a/') || p.startsWith('b/')) return p.slice(2);
  return p;
}

function emptyFile(oldPath = '', newPath = ''): FileDiff {
  return { oldPath, newPath, path: newPath, oldValue: '', newValue: '', isBinary: false, hunks: [] };
}

export function parseDiff(unified: string): FileDiff[] {
  if (!unified || !unified.trim()) return [];

  const files: FileDiff[] = [];
  let current: FileDiff | null = null;
  let hunk: DiffHunk | null = null;
  let oldNo = 0;
  let newNo = 0;

  const flush = () => {
    if (!current) return;
    current.path = current.newPath && current.newPath !== DEV_NULL ? current.newPath : current.oldPath;
    const rows = current.hunks.flatMap((h, i) => (i > 0 ? [null, ...h.rows] : h.rows));
    current.oldValue = rows.filter((r) => r === null || r.kind !== 'add').map((r) => (r ? r.text : '')).join('\n');
    current.newValue = rows.filter((r) => r === null || r.kind !== 'del').map((r) => (r ? r.text : '')).join('\n');
    files.push(current);
    current = null;
    hunk = null;
  };

  for (const rawLine of unified.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
    if (line.startsWith('diff --git ')) {
      flush();
      const m = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
      current = emptyFile(m ? m[1] : '', m ? m[2] : '');
      continue;
    }
    if (line.startsWith('--- ') && (!current || current.hunks.length > 0)) {
      // A file block without a leading "diff --git" line (plain unified diff).
      flush();
      current = emptyFile();
    }
    if (!current) continue;

    if (line.startsWith('--- ') && current.hunks.length === 0) {
      current.oldPath = stripPrefix(line.slice(4));
      continue;
    }
    if (line.startsWith('+++ ') && current.hunks.length === 0) {
      current.newPath = stripPrefix(line.slice(4));
      continue;
    }
    if (line.startsWith('Binary files') || line.startsWith('GIT binary patch')) {
      current.isBinary = true;
      continue;
    }
    const header = HUNK_HEADER.exec(line);
    if (header) {
      oldNo = Number(header[1]);
      newNo = Number(header[2]);
      hunk = { header: line, oldStart: oldNo, newStart: newNo, rows: [] };
      current.hunks.push(hunk);
      continue;
    }
    if (!hunk) continue; // file-meta lines (index, mode, rename...) before the first hunk
    if (line.startsWith('\\')) continue; // "\ No newline at end of file"
    // A genuinely empty split element is a trailing-newline artifact; real blank lines in a unified
    // diff carry a leading-space prefix (so they arrive here as " ", not "").
    if (line === '') continue;

    const marker = line[0];
    const text = line.slice(1);
    if (marker === '+') {
      hunk.rows.push({ kind: 'add', newNo: newNo++, text });
    } else if (marker === '-') {
      hunk.rows.push({ kind: 'del', oldNo: oldNo++, text });
    } else {
      hunk.rows.push({ kind: 'context', oldNo: oldNo++, newNo: newNo++, text });
    }
  }
  flush();

  return files.filter((f) => f.hunks.length > 0 || f.isBinary);
}

/**
 * Pair a hunk's rows for side-by-side display: context rows sit on both sides; a run of deletions
 * followed by a run of additions is zipped line by line (the classic split diff).
 */
export function toSplitRows(rows: DiffRow[]): SplitRow[] {
  const out: SplitRow[] = [];
  let i = 0;
  while (i < rows.length) {
    const row = rows[i];
    if (row.kind === 'context') {
      out.push({ left: row, right: row });
      i++;
      continue;
    }
    const dels: DiffRow[] = [];
    const adds: DiffRow[] = [];
    while (i < rows.length && rows[i].kind === 'del') dels.push(rows[i++]);
    while (i < rows.length && rows[i].kind === 'add') adds.push(rows[i++]);
    const n = Math.max(dels.length, adds.length);
    for (let k = 0; k < n; k++) out.push({ left: dels[k], right: adds[k] });
  }
  return out;
}

/** All new-file line numbers present in a file diff (valid comment anchors). */
export function newLineNumbers(file: FileDiff): Set<number> {
  const set = new Set<number>();
  for (const h of file.hunks) for (const r of h.rows) if (r.newNo !== undefined) set.add(r.newNo);
  return set;
}
