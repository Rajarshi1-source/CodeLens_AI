import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { FlaskConical, Github } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { ThemeToggle } from '@/components/layout/ThemeToggle';
import { authService } from '@/services/authService';
import { useAuth } from '@/hooks/useAuth';

type DemoUser = 'alice' | 'bob';

export function Login() {
  const { isLoading, isAuthenticated } = useAuth();
  const config = useQuery({ queryKey: ['auth-config'], queryFn: authService.config, staleTime: Infinity, retry: 0 });
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [pending, setPending] = useState<DemoUser | null>(null);

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingSpinner label="Checking session…" />
      </div>
    );
  }
  if (isAuthenticated) return <Navigate to="/" replace />;

  // If /api/auth/config is unreachable, still offer GitHub sign-in (the pre-demo behaviour).
  const githubEnabled = config.data?.githubLoginEnabled ?? true;
  const demoEnabled = config.data?.demoEnabled ?? false;

  const demoLogin = async (as: DemoUser) => {
    setPending(as);
    try {
      await authService.demoLogin(as);
      await qc.invalidateQueries({ queryKey: ['auth'] });
      navigate('/', { replace: true });
    } catch {
      toast.error('Demo login failed');
    } finally {
      setPending(null);
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>
      <Card className="w-full max-w-sm">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">CodeLens AI</CardTitle>
          <CardDescription>AI-powered code review for your pull requests.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {githubEnabled && (
            <Button
              className="w-full"
              onClick={() => {
                window.location.href = authService.loginUrl;
              }}
            >
              <Github className="h-4 w-4" aria-hidden />
              Sign in with GitHub
            </Button>
          )}
          {demoEnabled && (
            <div className="space-y-3" data-testid="demo-login">
              {githubEnabled && <Separator />}
              <p className="flex items-center justify-center gap-1.5 text-sm text-muted-foreground">
                <FlaskConical className="h-4 w-4" aria-hidden />
                Try the demo — no GitHub account needed
              </p>
              <div className="grid grid-cols-2 gap-2">
                <Button variant="secondary" disabled={pending !== null} onClick={() => void demoLogin('alice')}>
                  {pending === 'alice' ? 'Signing in…' : 'Demo as Alice'}
                </Button>
                <Button variant="secondary" disabled={pending !== null} onClick={() => void demoLogin('bob')}>
                  {pending === 'bob' ? 'Signing in…' : 'Demo as Bob'}
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
