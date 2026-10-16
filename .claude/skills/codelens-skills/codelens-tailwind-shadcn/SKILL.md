---
name: codelens-tailwind-shadcn
description: Tailwind CSS + shadcn/ui design specialist for the CodeLens AI frontend. Use this skill whenever styling, theming, or building UI for the CodeLens AI code-review platform — composing or customizing shadcn/ui primitives (Dialog, Table, Form, Badge, Card, Tabs), Tailwind utility styling, the severity color system, the separate PR risk-band tokens, the inline diff table chrome, feedback/apply/presence controls, dashboard cards/charts, dark mode, or accessibility. Trigger this even when the user only says "make it look better," "style the severity badge," "the dashboard cards," "dark mode," "the diff panel," or names a shadcn component — all visual/UI work in CodeLens AI follows these conventions and should use this skill. Pair it with codelens-react-typescript for component logic and types.
---

# CodeLens AI — Tailwind CSS + shadcn/ui

You style the **CodeLens AI** frontend. The look is a focused developer tool: dense but legible diffs, clear severity signaling, calm dashboard. Components are shadcn/ui primitives (Radix-based) styled with Tailwind. Component logic and types live in `codelens-react-typescript` — use both for UI work.

## Reference files (load on demand)

This body is the working summary. Read the matching reference when you need the full set:

| Reference | Load when |
|---|---|
| `references/theme-tokens.md` | Setting up theming, adding a token, or debugging dark mode; full OKLCH token set (light/dark/severity/diff) + dark-mode wiring |
| `references/component-catalog.md` | Building a specific component; PR list, dashboard, PR detail layout, forms, empty/loading/error states, a11y checklist |

## Design principles (avoid generic AI-slop UI)

1. **Distinctive but restrained** — a code-review tool earns trust through clarity, not flourish. One accent hue, a disciplined neutral ramp, generous whitespace around dense diff content.
2. **OKLCH color tokens, not raw HSL** — define theme colors in CSS variables using OKLCH for perceptually even lightness across light/dark.
3. **Severity is the primary semantic axis** — CRITICAL / WARNING / SUGGESTION each get a fixed, color-blind-considerate token used consistently in badges, gutters, and chart legends.
4. **Composition over configuration** — build features by composing primitives, not by adding props to a god-component.
5. **Accessibility is non-negotiable** — shadcn/ui sits on Radix; keep its keyboard nav, focus management, and screen-reader semantics intact.

## Theme tokens (define once, in `globals.css`)

Use shadcn's CSS-variable convention with **OKLCH** values, plus CodeLens severity and diff-chrome tokens. This project is locked to **Tailwind v4** (CSS-first, no `tailwind.config.js`): define tokens in `:root`/`.dark`, wire dark mode with `@custom-variant dark`, and expose tokens as utilities with `@theme inline`. Define the **full set in both `:root` and `.dark`** — overriding only some tokens is the #1 cause of washed-out dark mode. The complete copy-ready token set and the full v4 wiring (`@custom-variant` + `@theme inline`) are in `references/theme-tokens.md`. Shape:

```css
:root {
  --background: oklch(1 0 0);
  --foreground: oklch(0.21 0.01 265);
  --primary: oklch(0.55 0.18 265);              /* single accent */
  --ring: oklch(0.55 0.18 265);
  --radius: 0.625rem;

  --severity-critical:   oklch(0.58 0.21 27);   /* red */
  --severity-warning:    oklch(0.74 0.16 80);   /* amber */
  --severity-suggestion: oklch(0.68 0.15 155);  /* green */

  /* PR risk bands (G3) — a SEPARATE family: risk grades a whole PR, severity grades one comment */
  --risk-high:   oklch(0.56 0.2 355);           /* magenta-red */
  --risk-medium: oklch(0.66 0.16 55);           /* orange */
  --risk-low:    oklch(0.6 0.11 230);           /* blue */
}
.dark { /* re-declare the FULL set with adjusted lightness — see reference */ }
```

Dark mode uses Tailwind v4's `@custom-variant dark (&:is(.dark *))`. Toggle by adding `.dark` to `<html>`. Never hardcode hex colors in components — reference tokens (`bg-card`, `text-muted-foreground`, or `[color:var(--severity-critical)]`).

## Installing & composing shadcn/ui

Add primitives via the CLI (`npx shadcn@latest add dialog table form badge card tabs`); they're copied into `components/ui/` and owned by the project — customize them directly. Compose features from them; don't reach for a heavyweight component library.

## The severity system (use everywhere severity appears)

A single source of truth maps severity → token + icon, reused by `SeverityBadge`, diff gutters, and chart legends:

```tsx
const SEVERITY_STYLES: Record<Severity, { label: string; className: string }> = {
  CRITICAL:   { label: 'Critical',   className: 'bg-[var(--severity-critical)]/15 text-[var(--severity-critical)] border-[var(--severity-critical)]/30' },
  WARNING:    { label: 'Warning',    className: 'bg-[var(--severity-warning)]/15 text-[var(--severity-warning)] border-[var(--severity-warning)]/30' },
  SUGGESTION: { label: 'Suggestion', className: 'bg-[var(--severity-suggestion)]/15 text-[var(--severity-suggestion)] border-[var(--severity-suggestion)]/30' },
};

export function SeverityBadge({ severity }: { severity: Severity }) {
  const s = SEVERITY_STYLES[severity];
  return (
    <Badge variant="outline" className={cn('gap-1 font-medium', s.className)}>
      <span aria-hidden className="size-1.5 rounded-full bg-current" />
      {s.label}
    </Badge>
  );
}
```

Pair color with a shape/label (the dot + word) so severity is never conveyed by color alone — protects color-blind users.

## The risk system (PR-level, G3)

Risk has its own token family and its own single source of truth, `RISK_STYLES` in `lib/risk.ts` — never reuse a
severity token for risk (a HIGH-risk PR with only WARNING findings must not look like a CRITICAL comment). `RiskBadge`
pairs the color with a **bar glyph** (1/2/3 bars) and the label, plus the score; "Not scored" is a neutral outline badge.

```ts
export const RISK_STYLES: Record<RiskLevel, { label: string; className: string; cssVar: string }> = {
  HIGH:   { label: 'High risk',   className: 'bg-[var(--risk-high)]/15 text-[var(--risk-high)] border-[var(--risk-high)]/35',       cssVar: 'var(--risk-high)' },
  MEDIUM: { label: 'Medium risk', className: 'bg-[var(--risk-medium)]/15 text-[var(--risk-medium)] border-[var(--risk-medium)]/35', cssVar: 'var(--risk-medium)' },
  LOW:    { label: 'Low risk',    className: 'bg-[var(--risk-low)]/15 text-[var(--risk-low)] border-[var(--risk-low)]/35',          cssVar: 'var(--risk-low)' },
};
```

The risk score bar is a native `<progress class="risk-bar" data-risk="HIGH">` styled in `globals.css` from the
tokens (no inline styles); the dashboard `RiskDistributionChart` colors bars with `RISK_STYLES[level].cssVar`.

## Streaming comment + inline annotation

The comment that grows token-by-token should read as alive without distracting. Subtle blinking cursor while `isStreaming`, settle when done:

```tsx
export function InlineComment({ comment }: { comment: StreamingComment }) {
  return (
    <Card className="border-l-2 px-3 py-2 text-sm shadow-none"
          style={{ borderLeftColor: `var(--severity-${comment.severity.toLowerCase()})` }}>
      <div className="mb-1 flex items-center gap-2">
        <SeverityBadge severity={comment.severity} />
        {comment.confidence != null && (
          <span className="text-xs text-muted-foreground">
            {Math.round(comment.confidence * 100)}% confident
          </span>
        )}
      </div>
      <p className="leading-relaxed text-foreground/90">
        {comment.text}
        {comment.isStreaming && (
          <span className="ml-0.5 inline-block h-4 w-px animate-pulse bg-current align-middle" />
        )}
      </p>
    </Card>
  );
}
```

Keep animations purposeful: a streaming cursor and gentle fade-in for new comments. Use staggered fade-ins (small incremental delays) when several comments land at once, rather than everything popping simultaneously.

## Layout & density

- Diff is the focus → give it the column width (`lg:grid-cols-[minmax(0,1fr)_340px]` — `minmax(0, …)` so wide code
  cannot push the rail off-screen); findings render inline under their line, with a narrow rail beside.
- The diff is our own `table-fixed` table (gutters `w-12`, code columns share the rest; long lines wrap with
  `whitespace-pre-wrap [overflow-wrap:anywhere]`), not a third-party viewer.
- Dashboard uses a calm card grid (`grid gap-4 md:grid-cols-2 xl:grid-cols-3`) of `StatsCard`s.
- Use `Tabs` for PR detail (Diff / Summary / Comments), `Table` for the PR list with `PRStatusBadge`.
- Respect a consistent spacing rhythm (4/8px scale); don't mix arbitrary paddings.

Full composition patterns for each of these — PR list table, dashboard cards + severity chart, the PR detail Tabs+diff+rail layout, forms, and empty/loading/error states — are in `references/component-catalog.md`, along with the primitives to install and the accessibility checklist.

## Forms (repo connect, settings)

Use shadcn's `Form` (React Hook Form + Zod resolver). Inputs get labels associated via the generated `id`; validation errors render in the field message slot. Keep Radix focus/aria intact — don't replace primitives with bare `<input>`. In a Claude artifact, wire the button's `onClick` to `handleSubmit` rather than relying on native `<form>` submit. Full example in the component catalog.

## Interaction patterns (new components)

- **Feedback toggle** — two ghost `Button`s (`ThumbsUp`/`ThumbsDown` + count), `aria-pressed` on each, active vote
  shown with `bg-accent text-foreground`; `aria-label` "Helpful" / "Not helpful".
- **Apply states** — outline button "Apply suggestion" → spinner while pending → an outline link "Applied ↗" to the
  GitHub suggestion; FAILED reads "Retry apply". `SuggestionBlock` shows the code on the `--diff-added` background.
- **Presence avatar stack** — "Live" pulse dot + overlapping `Avatar`s (`-space-x-2`, `ring-2 ring-background`),
  max 5 then `+N`; tooltips give login, tab count and focused `file:line`. Focus markers are tiny avatars in the diff
  gutter.
- **Failure banner** — `role="alert"` box with `border-destructive/40 bg-destructive/10` and plain-language copy per
  `failure_reason` (budget exhausted, provider unavailable, retries exhausted).
- **Chart tooltips** — set `itemStyle`/`labelStyle` colors from tokens, or the default black text disappears in dark mode.

## MUST DO
- Define all colors as OKLCH CSS variables; reference tokens in components.
- Drive every severity color/icon from the single `SEVERITY_STYLES` map, and every risk color from `RISK_STYLES`.
- Convey severity with color **and** a shape/label (color-blind safety).
- Keep shadcn/ui (Radix) accessibility — keyboard nav, focus rings (`ring`), aria.
- Use `cn()` to merge conditional classes; keep class lists readable.
- Support light + dark via the `.dark` class strategy and token overrides.

## MUST NOT DO
- Hardcode hex/RGB colors in components instead of tokens.
- Convey severity or risk by color alone.
- Reuse a severity token for risk (or add a fourth severity color).
- Strip Radix accessibility attributes or replace primitives with unstyled raw elements.
- Add gratuitous gradients/glassmorphism/animation that fights the tool's clarity.
- Build one mega-component with dozens of boolean props (compose instead).
- Use inline styles for anything a Tailwind token can express (exception: dynamic `var(--severity-*)` borders).

## Troubleshooting
- **Dark mode colors look washed out**: you're mixing HSL and OKLCH, or overriding only some tokens — define the full token set in both `:root` and `.dark`.
- **Severity colors inconsistent across UI**: a component bypassed `SEVERITY_STYLES` — route it through the map.
- **Radix "Function components cannot be given refs" warning**: a custom component used as an `asChild` trigger (e.g. `Badge` in a `TooltipTrigger`) must `forwardRef`.
- **ScrollArea content wider than the panel**: Radix wraps children in a `display: table` div — force it to block (`[&>div]:!block` on the viewport).
- **shadcn component looks unthemed**: it's reading `--primary`/`--border` etc.; ensure your token names match shadcn's expected variable names.
- **Focus ring missing**: don't remove `focus-visible:ring-*`; that's the accessibility affordance.
- **Dark variant not working**: confirm `@custom-variant dark (&:is(.dark *))` is in your main CSS (Tailwind v4) and a `.dark` class is present on an ancestor.
- **`bg-card` / `text-muted-foreground` render as nothing**: the `@theme inline` mapping is missing — v4 needs it to turn the CSS variables into color utilities.
