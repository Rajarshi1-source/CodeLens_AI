/**
 * Minimal unified-diff parser. Splits a `git diff` (GitHub's `application/vnd.github.v3.diff`)
 * into per-file blocks, reconstructing the old/new side of each hunk so a diff widget can render
 * them. Inter-hunk gaps are collapsed (we only have hunk content, not the whole file), so the
 * reconstructed line numbers are hunk-relative, not absolute — review comments are shown adjacent
 * to each file rather than overlaid on exact lines.
 */

export interface FileDiff {
  oldPath: string;
  newPath: string;
  /** Path to display (the new path, or the old path for deletions). */
  path: string;
  oldValue: string;
  newValue: string;
  isBinary: boolean;
}

const DEV_NULL = '/dev/null';

function stripPrefix(raw: string): string {
  const p = raw.trim();
  if (p === DEV_NULL) return DEV_NULL;
  // unified-diff headers prefix paths with a/ and b/
  if (p.startsWith('a/') || p.startsWith('b/')) return p.slice(2);
  return p;
}

export function parseDiff(unified: string): FileDiff[] {
  if (!unified || !unified.trim()) return [];

  const files: FileDiff[] = [];
  let current: FileDiff | null = null;
  let oldLines: string[] = [];
  let newLines: string[] = [];
  let hunkCount = 0;

  const flush = () => {
    if (!current) return;
    current.oldValue = oldLines.join('\n');
    current.newValue = newLines.join('\n');
    current.path = current.newPath !== DEV_NULL ? current.newPath : current.oldPath;
    files.push(current);
    current = null;
    oldLines = [];
    newLines = [];
    hunkCount = 0;
  };

  for (const line of unified.split('\n')) {
    if (line.startsWith('diff --git ')) {
      flush();
      const m = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
      current = {
        oldPath: m ? m[1] : '',
        newPath: m ? m[2] : '',
        path: m ? m[2] : '',
        oldValue: '',
        newValue: '',
        isBinary: false,
      };
      continue;
    }
    if (!current) continue;

    if (line.startsWith('--- ')) {
      current.oldPath = stripPrefix(line.slice(4));
      continue;
    }
    if (line.startsWith('+++ ')) {
      current.newPath = stripPrefix(line.slice(4));
      continue;
    }
    if (line.startsWith('Binary files') || line.startsWith('GIT binary patch')) {
      current.isBinary = true;
      continue;
    }
    if (line.startsWith('@@')) {
      if (hunkCount > 0) {
        oldLines.push('');
        newLines.push('');
      }
      hunkCount += 1;
      continue;
    }
    // Ignore file-meta lines that aren't hunk content.
    if (line.startsWith('index ') || line.startsWith('old mode ') || line.startsWith('new mode ') ||
        line.startsWith('similarity index') || line.startsWith('rename ') ||
        line.startsWith('new file mode') || line.startsWith('deleted file mode') ||
        line.startsWith('\\')) {
      continue;
    }
    if (hunkCount === 0) continue; // not inside a hunk yet
    // A genuinely empty split element is a trailing-newline artifact; real blank lines in a
    // unified diff carry a leading-space prefix (so they arrive here as " ", not "").
    if (line === '') continue;

    const marker = line[0];
    const content = line.slice(1);
    if (marker === '+') {
      newLines.push(content);
    } else if (marker === '-') {
      oldLines.push(content);
    } else {
      // context line (leading space) or empty line within a hunk
      oldLines.push(content);
      newLines.push(content);
    }
  }
  flush();

  return files.filter((f) => f.oldValue !== '' || f.newValue !== '' || f.isBinary);
}
