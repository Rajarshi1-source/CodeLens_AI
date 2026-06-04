import { z } from 'zod';

/** Thrown when a protected call hits an expired/missing session (backend returns 401 for /api/**). */
export class UnauthorizedError extends Error {
  constructor() {
    super('Unauthorized');
    this.name = 'UnauthorizedError';
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// In dev the Vite proxy makes these same-origin; VITE_API_URL stays empty.
const BASE = import.meta.env.VITE_API_URL ?? '';

async function request(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'include',
    headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
    ...init,
  });
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  return res;
}

export async function getJson<T>(path: string, schema: z.ZodSchema<T>): Promise<T> {
  const res = await request(path);
  return schema.parse(await res.json());
}

export async function postJson<T>(
  path: string,
  body: unknown,
  schema: z.ZodSchema<T>,
): Promise<T> {
  const res = await request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return schema.parse(await res.json());
}

export async function postVoid(path: string, body?: unknown): Promise<void> {
  await request(path, {
    method: 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function del(path: string): Promise<void> {
  await request(path, { method: 'DELETE' });
}
