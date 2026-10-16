import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { PresenceUser } from '@/hooks/usePresence';

const MAX = 5;

/** Live avatars of everyone viewing this PR (G5). One avatar per person, however many tabs they have open. */
export function PresenceAvatars({ viewers }: { viewers: PresenceUser[] }) {
  if (viewers.length === 0) return null;
  const shown = viewers.slice(0, MAX);
  const extra = viewers.length - shown.length;
  return (
    <div className="flex items-center" aria-label={`${viewers.length} viewing`} data-testid="presence-avatars">
      <span className="mr-2 flex items-center gap-1 text-xs text-muted-foreground">
        <span aria-hidden className="size-2 animate-pulse rounded-full bg-[var(--severity-suggestion)]" />
        Live
      </span>
      <div className="flex -space-x-2">
        {shown.map((v) => (
          <Tooltip key={v.login}>
            <TooltipTrigger asChild>
              <Avatar className="size-7 ring-2 ring-background" data-testid="presence-avatar" data-login={v.login}>
                <AvatarImage src={v.avatarUrl ?? undefined} alt="" />
                <AvatarFallback className="text-[10px]">{v.login.slice(0, 2).toUpperCase()}</AvatarFallback>
              </Avatar>
            </TooltipTrigger>
            <TooltipContent>
              {v.login}
              {v.tabs > 1 ? ` · ${v.tabs} tabs` : ''}
              {v.file && v.line ? ` · ${v.file.split('/').pop()}:${v.line}` : ''}
            </TooltipContent>
          </Tooltip>
        ))}
        {extra > 0 && (
          <span className="flex size-7 items-center justify-center rounded-full bg-muted text-[10px] ring-2 ring-background">
            +{extra}
          </span>
        )}
      </div>
    </div>
  );
}
