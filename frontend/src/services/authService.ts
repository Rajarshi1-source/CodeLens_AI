import { getJson, postVoid } from './api';
import { CurrentUserSchema } from '@/types/schemas';
import type { CurrentUser } from '@/types/domain';

export const authService = {
  me: (): Promise<CurrentUser> => getJson('/api/auth/me', CurrentUserSchema),
  logout: (): Promise<void> => postVoid('/api/auth/logout'),
  /** Top-level navigation to start the GitHub OAuth2 authorization-code flow. */
  loginUrl: '/oauth2/authorization/github',
};
