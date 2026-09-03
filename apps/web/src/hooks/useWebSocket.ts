import { useEffect, useRef, useState, useCallback } from 'preact/hooks';
import type { ServerEvent } from '../types';

export type WsStatus = 'connecting' | 'open' | 'closed';

export function useWebSocket(url: string) {
  const [status, setStatus] = useState<WsStatus>('connecting');
  const [events, setEvents] = useState<ServerEvent[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(1000);

  useEffect(() => {
    let cancelled = false;
    function connect() {
      if (cancelled) return;
      setStatus('connecting');
      const ws = new WebSocket(url);
      wsRef.current = ws;
      ws.onopen = () => { retryRef.current = 1000; setStatus('open'); };
      ws.onmessage = (ev) => {
        try { setEvents((prev) => [...prev, JSON.parse(ev.data) as ServerEvent]); } catch { /* ignore */ }
      };
      ws.onclose = () => {
        setStatus('closed');
        if (cancelled) return;
        const wait = Math.min(retryRef.current, 30_000);
        retryRef.current = Math.min(wait * 2, 30_000);
        setTimeout(connect, wait);
      };
    }
    connect();
    return () => { cancelled = true; wsRef.current?.close(); };
  }, [url]);

  const send = useCallback((e: ServerEvent) => {
    wsRef.current?.send(JSON.stringify(e));
  }, []);

  return { status, events, send };
}
