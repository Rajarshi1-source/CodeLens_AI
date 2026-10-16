import { create } from 'zustand';

type Theme = 'light' | 'dark';
export type DiffMode = 'split' | 'unified';

interface UiState {
  theme: Theme;
  statusFilter: string; // 'ALL' | ReviewStatus
  riskFilter: string; // 'ALL' | RiskLevel
  search: string;
  diffMode: DiffMode;
  setTheme: (t: Theme) => void;
  toggleTheme: () => void;
  setStatusFilter: (s: string) => void;
  setRiskFilter: (s: string) => void;
  setSearch: (s: string) => void;
  setDiffMode: (m: DiffMode) => void;
}

function initialTheme(): Theme {
  if (typeof window === 'undefined') return 'light';
  const stored = window.localStorage.getItem('codelens-theme');
  if (stored === 'light' || stored === 'dark') return stored;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function initialDiffMode(): DiffMode {
  if (typeof window === 'undefined') return 'split';
  return window.localStorage.getItem('codelens-diff-mode') === 'unified' ? 'unified' : 'split';
}

function applyTheme(theme: Theme) {
  if (typeof document === 'undefined') return;
  document.documentElement.classList.toggle('dark', theme === 'dark');
  window.localStorage.setItem('codelens-theme', theme);
}

export const useUiStore = create<UiState>((set, get) => ({
  theme: initialTheme(),
  statusFilter: 'ALL',
  riskFilter: 'ALL',
  search: '',
  diffMode: initialDiffMode(),
  setTheme: (theme) => {
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
  setStatusFilter: (statusFilter) => set({ statusFilter }),
  setRiskFilter: (riskFilter) => set({ riskFilter }),
  setSearch: (search) => set({ search }),
  setDiffMode: (diffMode) => {
    window.localStorage.setItem('codelens-diff-mode', diffMode);
    set({ diffMode });
  },
}));

/** Apply the persisted/preferred theme to <html> on first load. */
export function bootstrapTheme() {
  applyTheme(useUiStore.getState().theme);
}
