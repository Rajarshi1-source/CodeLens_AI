import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useWebSocket } from './useWebSocket';
import { PresenceSnapshotSchema } from '@/types/schemas';
import type { PresenceViewer, PrId } from '@/types/domain';

const HEARTBEAT_MS = 15_000;
const FOCUS_THROTTLE_MS = 250;

export interface PresenceUser {
  login: string;
  avatarUrl: string | null;
  tabs: number;
  file: string | null;
  line: number | null;
  typing: boolean;
}

/** Collapse one row per STOMP session into one entry per person (two tabs = one avatar, "2 tabs"). */
export function collapseViewers(viewers: PresenceViewer[]): PresenceUser[] {
  const byLogin = new Map<string, PresenceUser>();
  for (const v of [...viewers].sort((a, b) => b.lastSeen - a.lastSeen)) {
    const existing = byLogin.get(v.login);
    if (existing) {
      existing.tabs += 1;
      existing.typing = existing.typing || v.typing;
    } else {
      byLogin.set(v.login, { login: v.login, avatarUrl: v.avatarUrl, tabs: 1, file: v.file, line: v.line, typing: v.typing });
    }
  }
  return [...byLogin.values()].sort((a, b) => a.login.localeCompare(b.login));
}

/**
 * G5 collaborative presence for a PR: who else is viewing it and which line each person is focused on.
 * Sends JOIN on mount, a heartbeat every 15s, FOCUS (throttled) as the pointer moves over the diff, and
 * LEAVE on unmount / tab close; renders the snapshot fanned out on /topic/pr/{id}/presence.
 */
export function usePresence(prId: PrId, selfLogin: string | undefined) {
  const { client, connected } = useWebSocket();
  const [viewers, setViewers] = useState<PresenceViewer[]>([]);
  const focusRef = useRef<{ file: string | null; line: number | null }>({ file: null, line: null });
  const lastSent = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const publish = useCallback(
    (type: 'JOIN' | 'HEARTBEAT' | 'FOCUS' | 'LEAVE') => {
      if (!client.connected) return;
      client.publish({
        destination: `/app/pr/${prId}/presence`,
        body: JSON.stringify({ type, file: focusRef.current.file, line: focusRef.current.line, typing: false }),
        headers: { 'content-type': 'application/json' },
      });
    },
    [client, prId],
  );

  useEffect(() => {
    if (!connected) return;
    const sub = client.subscribe(`/topic/pr/${prId}/presence`, (m) => {
      const parsed = PresenceSnapshotSchema.safeParse(JSON.parse(m.body));
      if (parsed.success) setViewers(parsed.data.viewers);
    });
    publish('JOIN');
    const heartbeat = setInterval(() => publish('HEARTBEAT'), HEARTBEAT_MS);
    const onUnload = () => publish('LEAVE');
    window.addEventListener('beforeunload', onUnload);
    return () => {
      clearInterval(heartbeat);
      window.removeEventListener('beforeunload', onUnload);
      publish('LEAVE');
      sub.unsubscribe();
    };
  }, [client, connected, prId, publish]);

  /** Report the line under the pointer (throttled). */
  const focus = useCallback(
    (file: string | null, line: number | null) => {
      if (focusRef.current.file === file && focusRef.current.line === line) return;
      focusRef.current = { file, line };
      const now = Date.now();
      const wait = FOCUS_THROTTLE_MS - (now - lastSent.current);
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(
        () => {
          lastSent.current = Date.now();
          publish('FOCUS');
        },
        Math.max(0, wait),
      );
    },
    [publish],
  );

  const others = useMemo(() => collapseViewers(viewers).filter((v) => v.login !== selfLogin), [viewers, selfLogin]);
  const everyone = useMemo(() => collapseViewers(viewers), [viewers]);

  return { viewers: everyone, others, focus };
}
