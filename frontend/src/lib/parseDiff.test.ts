import { describe, expect, it } from 'vitest';
import { newLineNumbers, parseDiff, toSplitRows } from './parseDiff';

const SAMPLE = `diff --git a/src/app.ts b/src/app.ts
index 1234567..89abcde 100644
--- a/src/app.ts
+++ b/src/app.ts
@@ -1,3 +1,4 @@
 const a = 1;
-const b = 2;
+const b = 3;
+const c = 4;
 export { a };
diff --git a/README.md b/README.md
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/README.md
@@ -0,0 +1,2 @@
+# Title
+body
`;

describe('parseDiff', () => {
  it('returns [] for empty input', () => {
    expect(parseDiff('')).toEqual([]);
    expect(parseDiff('   ')).toEqual([]);
  });

  it('splits into one block per file', () => {
    const files = parseDiff(SAMPLE);
    expect(files.map((f) => f.path)).toEqual(['src/app.ts', 'README.md']);
  });

  it('reconstructs old and new sides of a hunk', () => {
    const [app] = parseDiff(SAMPLE);
    expect(app.oldValue).toBe('const a = 1;\nconst b = 2;\nexport { a };');
    expect(app.newValue).toBe('const a = 1;\nconst b = 3;\nconst c = 4;\nexport { a };');
  });

  it('treats an added file as empty old side', () => {
    const readme = parseDiff(SAMPLE)[1];
    expect(readme.oldPath).toBe('/dev/null');
    expect(readme.oldValue).toBe('');
    expect(readme.newValue).toBe('# Title\nbody');
  });

  it('marks binary files', () => {
    const bin = parseDiff(
      'diff --git a/logo.png b/logo.png\nindex 1..2 100644\nBinary files a/logo.png and b/logo.png differ\n',
    );
    expect(bin).toHaveLength(1);
    expect(bin[0].isBinary).toBe(true);
  });
});

describe('parseDiff — absolute line numbers', () => {
  const HUNK = `diff --git a/src/x.ts b/src/x.ts
--- a/src/x.ts
+++ b/src/x.ts
@@ -40,4 +40,5 @@ function f() {
 const a = 1;
-const b = 2;
+const b = 3;
+const c = 4;
 return a;
 }
`;

  it('numbers rows from the @@ header, not from 1', () => {
    const [f] = parseDiff(HUNK);
    const rows = f.hunks[0].rows;
    expect(rows[0]).toMatchObject({ kind: 'context', oldNo: 40, newNo: 40 });
    expect(rows[1]).toMatchObject({ kind: 'del', oldNo: 41 });
    expect(rows[2]).toMatchObject({ kind: 'add', newNo: 41 });
    expect(rows[3]).toMatchObject({ kind: 'add', newNo: 42 });
    expect(rows[4]).toMatchObject({ kind: 'context', oldNo: 42, newNo: 43 });
  });

  it('exposes the new-side line numbers a comment can anchor to', () => {
    const [f] = parseDiff(HUNK);
    expect([...newLineNumbers(f)].sort((a, b) => a - b)).toEqual([40, 41, 42, 43, 44]);
  });

  it('pairs deletions with additions in split view', () => {
    const [f] = parseDiff(HUNK);
    const split = toSplitRows(f.hunks[0].rows);
    expect(split[1]).toMatchObject({ left: { kind: 'del', oldNo: 41 }, right: { kind: 'add', newNo: 41 } });
    expect(split[2].left).toBeUndefined();
    expect(split[2].right).toMatchObject({ kind: 'add', newNo: 42 });
  });

  it('counts a blank context line whose leading space was stripped', () => {
    // Line 9 is blank context written as "" instead of " "; the secret must stay on new line 10.
    const [f] = parseDiff('--- a/A.java\n+++ b/A.java\n@@ -8,2 +8,3 @@\n class A {\n\n+  String k = "x";\n');
    expect(f.hunks[0].rows.map((r) => [r.kind, r.newNo])).toEqual([
      ['context', 8],
      ['context', 9],
      ['add', 10],
    ]);
  });

  it('accepts a plain ---/+++ diff without the git header', () => {
    const files = parseDiff('--- a/a.py\n+++ b/a.py\n@@ -1 +1 @@\n-x = 1\n+x = 2\n');
    expect(files.map((f) => f.path)).toEqual(['a.py']);
  });
});
