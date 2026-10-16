import { request, type FullConfig } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

export const AUTH_DIR = path.join(import.meta.dirname, '.auth');
export const storageStateFor = (user: 'alice' | 'bob') => path.join(AUTH_DIR, `${user}.json`);

/**
 * 1. wait for the backend (demo profile) to be healthy, through the preview proxy;
 * 2. reset the demo data so every run starts from the same five seeded PRs;
 * 3. sign in as Alice and Bob and save their session cookies as storage states.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL!;
  const ctx = await request.newContext({ baseURL });

  const deadline = Date.now() + 120_000;
  for (;;) {
    const ok = await ctx
      .get('/api/auth/config')
      .then((r) => r.ok())
      .catch(() => false);
    if (ok) break;
    if (Date.now() > deadline) throw new Error(`Backend not reachable through ${baseURL}/api — is it running with SPRING_PROFILES_ACTIVE=demo?`);
    await new Promise((r) => setTimeout(r, 2000));
  }
  const cfg = await (await ctx.get('/api/auth/config')).json();
  if (!cfg.demoEnabled) throw new Error('Backend is not running the demo profile (demoEnabled=false)');

  const reset = await ctx.post('/api/demo/reset', { timeout: 120_000 });
  if (!reset.ok()) throw new Error(`demo reset failed: ${reset.status()} ${await reset.text()}`);

  fs.mkdirSync(AUTH_DIR, { recursive: true });
  for (const user of ['alice', 'bob'] as const) {
    const session = await request.newContext({ baseURL });
    const res = await session.post(`/api/auth/demo-login?as=${user}`);
    if (!res.ok()) throw new Error(`demo login as ${user} failed: ${res.status()}`);
    await session.storageState({ path: storageStateFor(user) });
    await session.dispose();
  }
  await ctx.dispose();
}
