import type { ReactNode } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';

export function StatsCard({
  label,
  value,
  accentClassName,
  icon,
}: {
  label: string;
  value: ReactNode;
  /** Optional Tailwind text-color class (e.g. a severity token) applied to the value. */
  accentClassName?: string;
  icon?: ReactNode;
}) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
        {icon && <span className="text-muted-foreground" aria-hidden>{icon}</span>}
      </CardHeader>
      <CardContent>
        <div className={cn('text-2xl font-semibold', accentClassName)}>{value}</div>
      </CardContent>
    </Card>
  );
}
