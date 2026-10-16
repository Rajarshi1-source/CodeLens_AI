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
    ...init,
    headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
  });
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) {
    // The backend's error envelope is {status, error, message}; surface its message when present.
    let message = `${res.status} ${res.statusText}`;
    try {
      const body: unknown = await res.json();
      if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
        message = body.message;
      }
    } catch {
      // non-JSON error body
    }
    throw new ApiError(res.status, message);
  }
  return res;
}

export async function getJson<T>(path: string, schema: z.ZodSchema<T>): Promise<T> {
  const res = await request(path);
  return schema.parse(await res.json());
}

/** Like getJson but maps 204 No Content to null (e.g. a PR that has never been reviewed). */
export async function getJsonOrNull<T>(path: string, schema: z.ZodSchema<T>): Promise<T | null> {
  const res = await request(path);
  if (res.status === 204) return null;
  return schema.parse(await res.json());
}

function jsonInit(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  };
}

export async function postJson<T>(path: string, body: unknown, schema: z.ZodSchema<T>): Promise<T> {
  const res = await request(path, jsonInit('POST', body));
  return schema.parse(await res.json());
}

export async function putJson<T>(path: string, body: unknown, schema: z.ZodSchema<T>): Promise<T> {
  const res = await request(path, jsonInit('PUT', body));
  return schema.parse(await res.json());
}

export async function deleteJson<T>(path: string, schema: z.ZodSchema<T>): Promise<T> {
  const res = await request(path, { method: 'DELETE' });
  return schema.parse(await res.json());
}

export async function postVoid(path: string, body?: unknown): Promise<void> {
  await request(path, jsonInit('POST', body));
}

export async function del(path: string): Promise<void> {
  await request(path, { method: 'DELETE' });
}
