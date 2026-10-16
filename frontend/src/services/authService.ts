import { getJson, postVoid } from './api';
import { AuthConfigSchema, CurrentUserSchema } from '@/types/schemas';
import type { AuthConfig, CurrentUser } from '@/types/domain';

export const authService = {
  me: (): Promise<CurrentUser> => getJson('/api/auth/me', CurrentUserSchema),
  logout: (): Promise<void> => postVoid('/api/auth/logout'),
  /** Which sign-in options the backend offers (the demo login exists only under the demo profile). */
  config: (): Promise<AuthConfig> => getJson('/api/auth/config', AuthConfigSchema),
  /** Demo profile only: start a session as a seeded demo user. */
  demoLogin: (as: 'alice' | 'bob'): Promise<void> => postVoid(`/api/auth/demo-login?as=${as}`),
  /** Top-level navigation to start the GitHub OAuth2 authorization-code flow. */
  loginUrl: '/oauth2/authorization/github',
};
