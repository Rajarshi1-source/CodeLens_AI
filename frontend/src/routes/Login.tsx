import { Navigate } from 'react-router-dom';
import { Github } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingSpinner } from '@/components/common/LoadingSpinner';
import { ThemeToggle } from '@/components/layout/ThemeToggle';
import { authService } from '@/services/authService';
import { useAuth } from '@/hooks/useAuth';

export function Login() {
  const { isLoading, isAuthenticated } = useAuth();

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingSpinner label="Checking session…" />
      </div>
    );
  }
  if (isAuthenticated) return <Navigate to="/" replace />;

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
        <CardContent>
          <Button
            className="w-full"
            onClick={() => {
              window.location.href = authService.loginUrl;
            }}
          >
            <Github className="h-4 w-4" aria-hidden />
            Sign in with GitHub
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
