import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

export function LoadingSpinner({ className, label }: { className?: string; label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 p-8 text-muted-foreground" role="status">
      <Loader2 className={cn('h-5 w-5 animate-spin', className)} aria-hidden />
      <span className="text-sm">{label ?? 'Loading…'}</span>
    </div>
  );
}
