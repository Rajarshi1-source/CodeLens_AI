# CodeLens AI — Theme Tokens Reference

The complete OKLCH token system: base shadcn variables, CodeLens severity scale, diff chrome, and the light/dark definitions. Load when setting up theming, adding a token, or debugging dark mode / washed-out colors. SKILL.md has the principle and an excerpt; this is the full, copy-ready set.

## Contents
1. Why OKLCH
2. Full token set (`globals.css`)
3. Dark mode wiring (Tailwind v4)
4. Severity tokens — the contract
5. Diff chrome tokens
6. Referencing tokens in components

## 1. Why OKLCH

OKLCH expresses color as perceptual Lightness, Chroma, Hue. Equal lightness steps look equally light to the eye, so a light/dark pair derived by adjusting L stays balanced — no muddy mid-tones, no one color screaming louder than its peers. This matters for the severity scale, where red/amber/green must read as equally weighted signals, not a loud red and a timid green.

## 2. Full token set (`globals.css`)

```css
:root {
  /* base surfaces */
  --background: oklch(1 0 0);
  --foreground: oklch(0.21 0.01 265);
  --card: oklch(1 0 0);
  --card-foreground: oklch(0.21 0.01 265);
  --popover: oklch(1 0 0);
  --popover-foreground: oklch(0.21 0.01 265);

  /* accent (single hue) */
  --primary: oklch(0.55 0.18 265);
  --primary-foreground: oklch(0.98 0 0);
  --secondary: oklch(0.97 0.003 265);
  --secondary-foreground: oklch(0.21 0.01 265);

  /* neutrals */
  --muted: oklch(0.97 0.003 265);
  --muted-foreground: oklch(0.55 0.02 265);
  --accent: oklch(0.97 0.003 265);
  --accent-foreground: oklch(0.21 0.01 265);
  --border: oklch(0.92 0.004 265);
  --input: oklch(0.92 0.004 265);
  --ring: oklch(0.55 0.18 265);

  /* status */
  --destructive: oklch(0.58 0.21 27);
  --destructive-foreground: oklch(0.98 0 0);

  --radius: 0.625rem;

  /* CodeLens severity scale (equal perceptual weight) */
  --severity-critical: oklch(0.58 0.21 27);    /* red */
  --severity-warning:  oklch(0.74 0.16 80);    /* amber */
  --severity-suggestion: oklch(0.68 0.15 155); /* green */

  /* diff chrome */
  --diff-added:        oklch(0.95 0.05 150);
  --diff-added-text:   oklch(0.45 0.12 150);
  --diff-removed:      oklch(0.94 0.06 25);
  --diff-removed-text: oklch(0.50 0.16 25);
  --diff-gutter:       oklch(0.97 0.003 265);
}

.dark {
  --background: oklch(0.18 0.01 265);
  --foreground: oklch(0.95 0.003 265);
  --card: oklch(0.21 0.01 265);
  --card-foreground: oklch(0.95 0.003 265);
  --popover: oklch(0.21 0.01 265);
  --popover-foreground: oklch(0.95 0.003 265);

  --primary: oklch(0.66 0.17 265);
  --primary-foreground: oklch(0.18 0.01 265);
  --secondary: oklch(0.26 0.01 265);
  --secondary-foreground: oklch(0.95 0.003 265);

  --muted: oklch(0.26 0.01 265);
  --muted-foreground: oklch(0.70 0.02 265);
  --accent: oklch(0.26 0.01 265);
  --accent-foreground: oklch(0.95 0.003 265);
  --border: oklch(0.30 0.01 265);
  --input: oklch(0.30 0.01 265);
  --ring: oklch(0.66 0.17 265);

  --destructive: oklch(0.66 0.20 27);
  --destructive-foreground: oklch(0.98 0 0);

  --severity-critical: oklch(0.66 0.20 27);
  --severity-warning:  oklch(0.80 0.15 80);
  --severity-suggestion: oklch(0.72 0.14 155);

  --diff-added:        oklch(0.28 0.06 150);
  --diff-added-text:   oklch(0.80 0.12 150);
  --diff-removed:      oklch(0.30 0.07 25);
  --diff-removed-text: oklch(0.82 0.14 25);
  --diff-gutter:       oklch(0.22 0.01 265);
}
```

Define the **full set** in both `:root` and `.dark`. Overriding only some tokens is the #1 cause of washed-out dark mode.

## 3. Dark mode wiring (Tailwind v4)

This project is locked to **Tailwind v4** (CSS-first config; no `tailwind.config.js`). Wire dark mode and expose the tokens as utilities in your main CSS:

```css
@import "tailwindcss";

/* dark mode driven by a .dark class on an ancestor */
@custom-variant dark (&:is(.dark *));

/* map the CSS variables (defined in :root / .dark above) to Tailwind color utilities,
   so bg-card, text-muted-foreground, border-border, ring-ring, etc. resolve to the tokens */
@theme inline {
  --color-background: var(--background);
  --color-foreground: var(--foreground);
  --color-card: var(--card);
  --color-card-foreground: var(--card-foreground);
  --color-primary: var(--primary);
  --color-primary-foreground: var(--primary-foreground);
  --color-muted: var(--muted);
  --color-muted-foreground: var(--muted-foreground);
  --color-border: var(--border);
  --color-input: var(--input);
  --color-ring: var(--ring);
  --color-destructive: var(--destructive);
  --radius: var(--radius);
}
```

The `@theme inline` block is what makes `bg-card` / `text-muted-foreground` work in v4 — it's the replacement for v3's `theme.extend.colors`. Severity and diff-chrome tokens are referenced directly as `var(--severity-critical)` (they don't need utility classes). Toggle dark mode by adding/removing `.dark` on `<html>`. Persist the choice in app state (use React state, not browser storage, if this runs as a Claude artifact). Respect `prefers-color-scheme` as the initial default.

## 4. Severity tokens — the contract

These three tokens are the single source of truth for severity color, consumed by `SeverityBadge`, diff gutters, the dashboard `SeverityChart`, and any filter UI. Rules:
- Never introduce a fourth severity color or a one-off red — route through the tokens.
- Always pair the color with a shape or label (dot + word) so severity survives color-blindness and grayscale printing.
- Use the token at low opacity for fills (`/15`), full strength for text/borders, so badges stay legible.

## 5. Diff chrome tokens

The diff renderer is locked to **`react-diff-viewer-continued`** (the master plan's choice). It accepts a `styles`/`variables` object; point every color at a CSS-var token. Because the tokens already flip under `.dark`, the same `var(--…)` values render correctly in both themes — you do NOT need a separate dark palette here, the variables resolve per active theme:

```ts
const diffStyles = {
  variables: {
    light: {
      addedBackground: 'var(--diff-added)',
      addedColor: 'var(--diff-added-text)',
      removedBackground: 'var(--diff-removed)',
      removedColor: 'var(--diff-removed-text)',
      gutterBackground: 'var(--diff-gutter)',
    },
  },
};
// <ReactDiffViewer styles={diffStyles} useDarkTheme={false} ... />
// Keep useDarkTheme={false} and let the CSS variables (which flip under .dark) do the theming —
// one source of truth instead of maintaining the library's separate dark palette.
```

## 6. Referencing tokens in components

```tsx
<div className="bg-card text-card-foreground border-border">…</div>
<button className="bg-primary text-primary-foreground focus-visible:ring-2 ring-ring">…</button>
// dynamic severity (the one sanctioned inline-style case):
<div style={{ borderLeftColor: `var(--severity-${severity.toLowerCase()})` }} />
```

Never hardcode hex/RGB in a component. If a value isn't a token yet and you need it twice, add a token instead of repeating the literal.
