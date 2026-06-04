import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/sonner';
import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { RequireAuth } from '@/components/layout/RequireAuth';
import { AppShell } from '@/components/layout/AppShell';
import { StompProvider } from '@/hooks/useWebSocket';
import { UnauthorizedError } from '@/services/api';
import { Login } from '@/routes/Login';
import { Dashboard } from '@/routes/Dashboard';
import { PRList } from '@/routes/PRList';
import { PRReview } from '@/routes/PRReview';
import { RepoSettings } from '@/routes/RepoSettings';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Don't hammer protected endpoints when the session is gone; let RequireAuth redirect.
      retry: (failureCount, error) =>
        !(error instanceof UnauthorizedError) && failureCount < 1,
      refetchOnWindowFocus: false,
    },
  },
});

/** Authenticated layout: gate, mount the STOMP client, render the app shell. */
function ProtectedLayout() {
  return (
    <RequireAuth>
      <StompProvider>
        <AppShell />
      </StompProvider>
    </RequireAuth>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider delayDuration={200}>
        <ErrorBoundary>
          <BrowserRouter>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route element={<ProtectedLayout />}>
                <Route index element={<Dashboard />} />
                <Route path="prs" element={<PRList />} />
                <Route path="prs/:id" element={<PRReview />} />
                <Route path="repos" element={<RepoSettings />} />
              </Route>
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </BrowserRouter>
          <Toaster />
        </ErrorBoundary>
      </TooltipProvider>
    </QueryClientProvider>
  );
}
