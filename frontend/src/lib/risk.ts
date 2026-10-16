import type { RiskLevel } from '@/types/domain';

/**
 * Single source of truth for risk color/label. Risk uses its own `--risk-*` token family — never the
 * severity tokens (a risk band grades a whole PR; a severity grades one comment).
 */
export const RISK_STYLES: Record<RiskLevel, { label: string; className: string; cssVar: string }> = {
  HIGH: {
    label: 'High risk',
    className: 'bg-[var(--risk-high)]/15 text-[var(--risk-high)] border-[var(--risk-high)]/35',
    cssVar: 'var(--risk-high)',
  },
  MEDIUM: {
    label: 'Medium risk',
    className: 'bg-[var(--risk-medium)]/15 text-[var(--risk-medium)] border-[var(--risk-medium)]/35',
    cssVar: 'var(--risk-medium)',
  },
  LOW: {
    label: 'Low risk',
    className: 'bg-[var(--risk-low)]/15 text-[var(--risk-low)] border-[var(--risk-low)]/35',
    cssVar: 'var(--risk-low)',
  },
};

export const RISK_ORDER: RiskLevel[] = ['HIGH', 'MEDIUM', 'LOW'];
