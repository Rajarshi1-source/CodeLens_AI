import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { SEVERITY_STYLES } from '@/lib/severity';
import type { Severity } from '@/types/domain';

/** Severity is conveyed by color AND a dot + label (color-blind safe). */
export function SeverityBadge({ severity }: { severity: Severity }) {
  const s = SEVERITY_STYLES[severity];
  return (
    <Badge variant="outline" className={cn('gap-1 font-medium', s.className)}>
      <span aria-hidden className="size-1.5 rounded-full bg-current" />
      {s.label}
    </Badge>
  );
}
