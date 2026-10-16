/** The model's one-line replacement for the anchored line, shown as a "suggested change". */
export function SuggestionBlock({ code }: { code: string }) {
  return (
    <div className="mt-2 overflow-hidden rounded-md border" data-testid="suggestion-block">
      <div className="border-b bg-muted px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        Suggested change
      </div>
      <pre className="overflow-x-auto bg-[var(--diff-added)] px-3 py-2 font-mono text-xs text-[var(--diff-added-text)]">
        <code>{code}</code>
      </pre>
    </div>
  );
}
