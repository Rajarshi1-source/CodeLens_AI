import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { SEVERITY_ORDER, SEVERITY_STYLES } from '@/lib/severity';
import { EmptyState } from '@/components/common/EmptyState';
import type { Severity } from '@/types/domain';

/** Bar chart of findings by severity; each bar colored + labeled from the severity tokens. */
export function SeverityChart({ counts }: { counts: Record<string, number> }) {
  const data = SEVERITY_ORDER.map((sev) => ({
    severity: sev,
    label: SEVERITY_STYLES[sev].label,
    count: counts[sev] ?? 0,
    fill: SEVERITY_STYLES[sev].cssVar,
  }));

  const total = data.reduce((sum, d) => sum + d.count, 0);
  if (total === 0) {
    return <EmptyState title="No findings yet" description="Severity breakdown appears once reviews complete." />;
  }

  return (
    <ResponsiveContainer width="100%" height={240}>
      <BarChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
        <XAxis dataKey="label" tickLine={false} axisLine={false} fontSize={12} />
        <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={28} fontSize={12} />
        <Tooltip
          cursor={{ fill: 'var(--muted)', opacity: 0.4 }}
          contentStyle={{
            background: 'var(--popover)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            color: 'var(--popover-foreground)',
            fontSize: 12,
          }}
        />
        <Bar dataKey="count" radius={[6, 6, 0, 0]}>
          {data.map((d) => (
            <Cell key={d.severity as Severity} fill={d.fill} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
