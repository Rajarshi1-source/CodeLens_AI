import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

interface StompContextValue {
  client: Client;
  connected: boolean;
}

const StompContext = createContext<StompContextValue | null>(null);

const WS_URL = import.meta.env.VITE_WS_URL ?? '/ws';

/** One STOMP client over SockJS for the whole app, shared via context with a live `connected` flag. */
export function StompProvider({ children }: { children: ReactNode }) {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = useState(false);

  if (!clientRef.current) {
    clientRef.current = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
    });
  }

  useEffect(() => {
    const client = clientRef.current!;
    client.onConnect = () => setConnected(true);
    client.onWebSocketClose = () => setConnected(false);
    client.onDisconnect = () => setConnected(false);
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, []);

  return (
    <StompContext.Provider value={{ client: clientRef.current, connected }}>
      {children}
    </StompContext.Provider>
  );
}

export function useWebSocket(): StompContextValue {
  const ctx = useContext(StompContext);
  if (!ctx) throw new Error('useWebSocket must be used within StompProvider');
  return ctx;
}
