import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { EmptyState } from '@/components/common/EmptyState';
import { RISK_ORDER, RISK_STYLES } from '@/lib/risk';

/** PRs by risk band (G3), each bar colored + labeled from the `--risk-*` tokens. */
export function RiskDistributionChart({ distribution }: { distribution: Record<string, number> }) {
  const data = RISK_ORDER.map((level) => ({
    level,
    label: RISK_STYLES[level].label,
    count: distribution[level] ?? 0,
    fill: RISK_STYLES[level].cssVar,
  }));
  if (data.every((d) => d.count === 0)) {
    return <EmptyState title="No scored PRs yet" description="Risk appears once a review completes." />;
  }
  return (
    <div data-testid="risk-chart">
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 4, left: 8 }}>
          <XAxis type="number" allowDecimals={false} tickLine={false} axisLine={false} fontSize={12} />
          <YAxis type="category" dataKey="label" tickLine={false} axisLine={false} width={92} fontSize={12} />
          <Tooltip
            cursor={{ fill: 'var(--muted)', opacity: 0.4 }}
            itemStyle={{ color: 'var(--popover-foreground)' }}
            labelStyle={{ color: 'var(--muted-foreground)' }}
            contentStyle={{
              background: 'var(--popover)',
              border: '1px solid var(--border)',
              borderRadius: 8,
              color: 'var(--popover-foreground)',
              fontSize: 12,
            }}
          />
          <Bar dataKey="count" radius={[0, 6, 6, 0]}>
            {data.map((d) => (
              <Cell key={d.level} fill={d.fill} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
