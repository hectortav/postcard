import { useEffect, useRef, useState, useCallback } from 'preact/hooks';
import type { ServerEvent } from '../types';

export type WsStatus = 'connecting' | 'open' | 'closed';

/**
 * The server event stream.
 *
 * Events are handed to `onEvent` as they arrive rather than accumulated into an array. The
 * array version leaked in two directions: it grew for the lifetime of the tab, copying itself
 * on every frame, and because the only consumer read `events[events.length - 1]` from an
 * effect, two frames delivered in one tick meant the first was never seen at all.
 *
 * `send` reports whether the frame actually went out. A closed or still-connecting socket
 * used to swallow it silently, and on a CONNECTING socket `send` throws, so typed clipboard
 * text could vanish while the UI showed it as saved.
 */
export function useWebSocket(url: string, onEvent?: (event: ServerEvent) => void) {
  const [status, setStatus] = useState<WsStatus>('connecting');
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(1000);
  // Held in a ref so a caller that rebuilds its handler every render does not reconnect.
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    function connect() {
      if (cancelled) return;
      setStatus('connecting');
      const ws = new WebSocket(url);
      wsRef.current = ws;
      ws.onopen = () => { retryRef.current = 1000; setStatus('open'); };
      ws.onmessage = (ev) => {
        let parsed: ServerEvent;
        try {
          parsed = JSON.parse(ev.data) as ServerEvent;
        } catch {
          return; // a truncated or non-JSON frame must not drop the connection
        }
        handlerRef.current?.(parsed);
      };
      // Without this an error with no close event leaves the UI claiming to be connected.
      ws.onerror = () => { if (ws.readyState !== WebSocket.OPEN) setStatus('closed'); };
      ws.onclose = () => {
        setStatus('closed');
        if (cancelled) return;
        const wait = Math.min(retryRef.current, 30_000);
        retryRef.current = Math.min(wait * 2, 30_000);
        timer = setTimeout(connect, wait);
      };
    }

    connect();
    return () => {
      cancelled = true;
      if (timer !== undefined) clearTimeout(timer);
      wsRef.current?.close();
    };
  }, [url]);

  /** Send a frame. Returns false when the socket could not take it. */
  const send = useCallback((e: ServerEvent): boolean => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try {
      ws.send(JSON.stringify(e));
      return true;
    } catch {
      return false;
    }
  }, []);

  return { status, send };
}
