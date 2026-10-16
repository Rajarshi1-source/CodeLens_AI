import { z } from 'zod';
import { del, getJson, postJson, postVoid } from './api';
import { RepoSchema } from '@/types/schemas';
import { RepoId, type Repository } from '@/types/domain';

function toRepository(r: z.infer<typeof RepoSchema>): Repository {
  return { ...r, id: RepoId(r.id) };
}

export const repoService = {
  list: (): Promise<Repository[]> =>
    getJson('/api/repos', z.array(RepoSchema)).then((rows) => rows.map(toRepository)),

  connect: (fullName: string): Promise<Repository> =>
    postJson('/api/repos/connect', { fullName }, RepoSchema).then(toRepository),

  disconnect: (id: RepoId): Promise<void> => del(`/api/repos/${id}`),

  /** Queue a full re-index of the repo's default branch (G1 retrieval index). */
  reindex: (id: RepoId): Promise<void> => postVoid(`/api/repos/${id}/reindex`),
};
