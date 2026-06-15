import { describe, expect, it } from 'vitest';
import { parseDiff } from './parseDiff';

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
