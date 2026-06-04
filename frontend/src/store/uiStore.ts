import { create } from 'zustand';

type Theme = 'light' | 'dark';

interface UiState {
  theme: Theme;
  statusFilter: string; // 'ALL' | ReviewStatus
  search: string;
  setTheme: (t: Theme) => void;
  toggleTheme: () => void;
  setStatusFilter: (s: string) => void;
  setSearch: (s: string) => void;
}

function initialTheme(): Theme {
  if (typeof window === 'undefined') return 'light';
  const stored = window.localStorage.getItem('codelens-theme');
  if (stored === 'light' || stored === 'dark') return stored;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function applyTheme(theme: Theme) {
  if (typeof document === 'undefined') return;
  document.documentElement.classList.toggle('dark', theme === 'dark');
  window.localStorage.setItem('codelens-theme', theme);
}

export const useUiStore = create<UiState>((set, get) => ({
  theme: initialTheme(),
  statusFilter: 'ALL',
  search: '',
  setTheme: (theme) => {
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
  setStatusFilter: (statusFilter) => set({ statusFilter }),
  setSearch: (search) => set({ search }),
}));

/** Apply the persisted/preferred theme to <html> on first load. */
export function bootstrapTheme() {
  applyTheme(useUiStore.getState().theme);
}
