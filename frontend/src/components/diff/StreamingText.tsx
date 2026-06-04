import { cn } from '@/lib/utils';

/** Renders comment text with a blinking caret while the comment is still streaming. */
export function StreamingText({ text, isStreaming }: { text: string; isStreaming: boolean }) {
  return (
    <span className="whitespace-pre-wrap break-words text-sm leading-relaxed">
      {text}
      {isStreaming && (
        <span
          aria-hidden
          className={cn('ml-0.5 inline-block h-3.5 w-1.5 translate-y-0.5 animate-pulse bg-current')}
        />
      )}
    </span>
  );
}
