import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Github, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/ui/skeleton';
import { useConnectRepo, useDisconnectRepo, useRepos } from '@/hooks/useRepos';
import { RepoId } from '@/types/domain';

const schema = z.object({
  fullName: z
    .string()
    .min(1, 'Repository is required')
    .regex(/^[\w.-]+\/[\w.-]+$/, 'Use the owner/repo format'),
});
type FormValues = z.infer<typeof schema>;

export function RepoSettings() {
  const { data: repos, isLoading, isError, refetch } = useRepos();
  const connect = useConnectRepo();
  const disconnect = useDisconnectRepo();

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { fullName: '' } });

  const onConnect = (values: FormValues) => {
    connect.mutate(values.fullName, { onSuccess: () => form.reset() });
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Repositories</h1>
        <p className="text-sm text-muted-foreground">
          Connect a repository to enable automated reviews on its pull requests.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Connect a repository</CardTitle>
        </CardHeader>
        <CardContent>
          <Form {...form}>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
              <FormField
                control={form.control}
                name="fullName"
                render={({ field }) => (
                  <FormItem className="flex-1">
                    <FormLabel className="sr-only">Repository</FormLabel>
                    <FormControl>
                      <Input placeholder="owner/repo" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <Button onClick={form.handleSubmit(onConnect)} disabled={connect.isPending}>
                <Plus className="h-4 w-4" aria-hidden />
                {connect.isPending ? 'Connecting…' : 'Connect'}
              </Button>
            </div>
          </Form>
        </CardContent>
      </Card>

      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
        </div>
      ) : isError ? (
        <ErrorState message="Could not load repositories." onRetry={() => void refetch()} />
      ) : !repos || repos.length === 0 ? (
        <EmptyState
          icon={<Github className="h-6 w-6" />}
          title="No repositories connected"
          description="Add one above to start receiving AI reviews on its pull requests."
        />
      ) : (
        <div className="space-y-2">
          {repos.map((repo) => (
            <Card key={repo.id} className="flex items-center justify-between p-4">
              <div className="flex items-center gap-3">
                <Github className="h-4 w-4 text-muted-foreground" aria-hidden />
                <span className="font-mono text-sm">{repo.fullName}</span>
                <Badge variant={repo.active ? 'secondary' : 'outline'}>
                  {repo.active ? 'Active' : 'Inactive'}
                </Badge>
              </div>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Disconnect ${repo.fullName}`}
                disabled={disconnect.isPending}
                onClick={() => disconnect.mutate(RepoId(repo.id))}
              >
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
