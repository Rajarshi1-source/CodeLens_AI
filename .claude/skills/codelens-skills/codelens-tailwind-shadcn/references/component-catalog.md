# CodeLens AI — shadcn/ui Component Catalog

Composition patterns for the specific CodeLens components, built from shadcn/ui primitives. Load when building a UI component. SKILL.md has the severity badge and inline comment; this covers the PR list, dashboard, diff layout, forms, and accessibility checklist.

## Contents
1. Which primitives to install
2. PR list (Table + status badge + filters)
3. Dashboard (Card grid + severity chart)
4. PR detail layout (Tabs + diff + comment rail)
5. Forms (repo connect/settings)
6. Empty / loading / error states
7. Accessibility checklist

## 1. Which primitives to install

```
npx shadcn@latest add badge card table tabs dialog form input select \
  dropdown-menu tooltip skeleton sonner avatar separator scroll-area
```

They land in `components/ui/` and are project-owned — customize directly. Build features by composing these; don't pull in a second component library.

## 2. PR list (Table + status badge + filters)

```tsx
function PRList({ prs }: { prs: PullRequest[] }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>PR</TableHead><TableHead>Title</TableHead>
          <TableHead>Author</TableHead><TableHead>Status</TableHead>
          <TableHead className="text-right">Changes</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {prs.map((pr) => (
          <TableRow key={pr.id} className="cursor-pointer hover:bg-muted/50">
            <TableCell className="font-mono text-muted-foreground">#{pr.number}</TableCell>
            <TableCell className="font-medium">{pr.title}</TableCell>
            <TableCell>{pr.author}</TableCell>
            <TableCell><PRStatusBadge status={pr.status} /></TableCell>
            <TableCell className="text-right font-mono text-xs">
              <span className="text-[var(--diff-added-text)]">+{pr.additions}</span>{' '}
              <span className="text-[var(--diff-removed-text)]">−{pr.deletions}</span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
```

`PRStatusBadge` maps `ReviewStatus` → label + variant the same way `SeverityBadge` maps severity (single source of truth). `REVIEW_FAILED` and `REVIEW_UNAVAILABLE` use the destructive/muted tokens respectively, with a tooltip explaining the state and (for failed) a retry affordance.

Filters use `Select` / `DropdownMenu` for status and a search `Input`; keep them in a sticky toolbar above the table.

## 3. Dashboard (Card grid + severity chart)

```tsx
function Dashboard({ stats }: { stats: DashboardStats }) {
  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      <StatsCard label="Reviews" value={stats.totalReviews} />
      <StatsCard label="Avg review time" value={`${(stats.avgReviewTimeMs / 1000).toFixed(1)}s`} />
      <StatsCard label="Critical findings" value={stats.severityCounts.CRITICAL}
                 accent="var(--severity-critical)" />
      <Card className="md:col-span-2 xl:col-span-3">
        <CardHeader><CardTitle>Findings by severity</CardTitle></CardHeader>
        <CardContent><SeverityChart counts={stats.severityCounts} /></CardContent>
      </Card>
    </div>
  );
}
```

`SeverityChart` (Recharts) colors each bar/segment from the severity tokens and labels them — never a legend that relies on color alone.

## 4. PR detail layout (Tabs + diff + comment rail)

```tsx
function PRReview({ prId }: { prId: PrId }) {
  const { comments, status } = usePrReview(prId);   // REST + WS merge (see streaming ref)
  return (
    <Tabs defaultValue="diff" className="h-full">
      <div className="flex items-center justify-between border-b px-4 py-2">
        <TabsList>
          <TabsTrigger value="diff">Diff</TabsTrigger>
          <TabsTrigger value="summary">Summary</TabsTrigger>
          <TabsTrigger value="comments">Comments ({comments.length})</TabsTrigger>
        </TabsList>
        <PRStatusBadge status={status} />
      </div>
      <TabsContent value="diff" className="grid grid-cols-[1fr_360px] gap-0">
        <ScrollArea className="h-[calc(100vh-8rem)]"><DiffViewer prId={prId} comments={comments} /></ScrollArea>
        <ScrollArea className="h-[calc(100vh-8rem)] border-l">
          <CommentRail comments={comments} />
        </ScrollArea>
      </TabsContent>
      {/* summary, comments tabs… */}
    </Tabs>
  );
}
```

Give the diff the dominant column; comments dock in a fixed-width rail. Each `InlineComment` (from SKILL.md) fades in; multiple arrivals stagger.

## 5. Forms (repo connect/settings)

Use shadcn `Form` (React Hook Form + Zod resolver). Labels associate via the generated `id`; errors render in the message slot. Keep Radix primitives — don't drop to bare inputs.

```tsx
const schema = z.object({ fullName: z.string().min(1, 'Repository is required') });

function ConnectRepoForm({ onConnect }: { onConnect: (v: { fullName: string }) => void }) {
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema) });
  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField control={form.control} name="fullName" render={({ field }) => (
          <FormItem>
            <FormLabel>Repository</FormLabel>
            <FormControl><Input placeholder="owner/repo" {...field} /></FormControl>
            <FormMessage />
          </FormItem>
        )} />
        <Button onClick={form.handleSubmit(onConnect)}>Connect</Button>
      </div>
    </Form>
  );
}
```

(In a Claude artifact, avoid native `<form>` submit — wire the button's `onClick` to `handleSubmit`, as above.)

## 6. Empty / loading / error states

Every data view needs all three. Use `Skeleton` for loading (match the final layout's shape), a friendly `EmptyState` with a clear next action, and an `ErrorBoundary` + inline error card for failures. Use `sonner` toasts for transient feedback (e.g. "Re-review queued").

## 7. Accessibility checklist

- Severity/status conveyed by color **and** text/shape.
- All interactive elements reachable and operable by keyboard (Radix handles this — don't break it).
- Visible focus rings (`focus-visible:ring-2 ring-ring`) — never remove them.
- Form inputs have associated labels; errors are announced (shadcn `FormMessage` is `aria-live`).
- Dialogs/dropdowns trap focus and restore it on close (Radix default — keep it).
- Color contrast meets WCAG AA in both light and dark (the OKLCH lightness choices target this).
- Icons that carry meaning have `aria-label`; purely decorative ones get `aria-hidden`.
