import { describe, expect, it } from 'vitest';
import { SEVERITY_ORDER, SEVERITY_STYLES } from './severity';
import type { Severity } from '@/types/domain';

describe('SEVERITY_STYLES mapping', () => {
  it('covers every severity exactly once, in priority order', () => {
    expect(SEVERITY_ORDER).toEqual(['CRITICAL', 'WARNING', 'SUGGESTION']);
  });

  it('maps each severity to a distinct token and a human label', () => {
    const vars = new Set<string>();
    for (const sev of SEVERITY_ORDER) {
      const style = SEVERITY_STYLES[sev];
      expect(style.label).toBeTruthy();
      expect(style.cssVar).toBe(`var(--severity-${sev.toLowerCase()})`);
      vars.add(style.cssVar);
    }
    expect(vars.size).toBe(SEVERITY_ORDER.length);
  });

  it('has no severity outside the known set', () => {
    const keys = Object.keys(SEVERITY_STYLES) as Severity[];
    expect(keys.sort()).toEqual([...SEVERITY_ORDER].sort());
  });
});
