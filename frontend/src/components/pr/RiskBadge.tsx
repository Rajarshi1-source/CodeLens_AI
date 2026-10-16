import { Badge } from '@/components/ui/badge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { RISK_STYLES } from '@/lib/risk';
import type { RiskLevel } from '@/types/domain';

const BAR_HEIGHT = ['h-1.5', 'h-2', 'h-2.5'];

/** PR risk band (G3): color + shape (bar glyph) + label, so it never relies on color alone. */
export function RiskBadge({ level, score }: { level: RiskLevel | null; score?: number | null }) {
  if (!level) {
    return (
      <Badge variant="outline" className="font-normal text-muted-foreground" data-testid="risk-badge">
        Not scored
      </Badge>
    );
  }
  const s = RISK_STYLES[level];
  const bars = level === 'HIGH' ? 3 : level === 'MEDIUM' ? 2 : 1;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant="outline" className={cn('gap-1.5 font-medium', s.className)} data-testid="risk-badge" data-risk={level}>
          <span aria-hidden className="flex items-end gap-px">
            {[1, 2, 3].map((i) => (
              <span key={i} className={cn('w-0.5 rounded-sm bg-current', BAR_HEIGHT[i - 1], i <= bars ? 'opacity-100' : 'opacity-25')} />
            ))}
          </span>
          {s.label}
          {typeof score === 'number' && <span className="font-mono text-[11px] opacity-80">{score}</span>}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>Risk score {score ?? '—'}/100 — heuristic, see the review summary for reasons.</TooltipContent>
    </Tooltip>
  );
}
