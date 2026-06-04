import type { Severity } from '@/types/domain';

/** Single source of truth for severity color/label. Consumed by badges, gutters, and the chart. */
export const SEVERITY_STYLES: Record<Severity, { label: string; className: string; cssVar: string }> = {
  CRITICAL: {
    label: 'Critical',
    className:
      'bg-[var(--severity-critical)]/15 text-[var(--severity-critical)] border-[var(--severity-critical)]/30',
    cssVar: 'var(--severity-critical)',
  },
  WARNING: {
    label: 'Warning',
    className:
      'bg-[var(--severity-warning)]/15 text-[var(--severity-warning)] border-[var(--severity-warning)]/30',
    cssVar: 'var(--severity-warning)',
  },
  SUGGESTION: {
    label: 'Suggestion',
    className:
      'bg-[var(--severity-suggestion)]/15 text-[var(--severity-suggestion)] border-[var(--severity-suggestion)]/30',
    cssVar: 'var(--severity-suggestion)',
  },
};

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'WARNING', 'SUGGESTION'];
