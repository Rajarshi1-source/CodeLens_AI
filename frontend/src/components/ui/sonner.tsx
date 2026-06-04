import { Toaster as Sonner } from 'sonner';
import { useUiStore } from '@/store/uiStore';

/** App-wide toast host; follows the active theme. */
export function Toaster() {
  const theme = useUiStore((s) => s.theme);
  return <Sonner theme={theme} position="bottom-right" richColors closeButton />;
}
