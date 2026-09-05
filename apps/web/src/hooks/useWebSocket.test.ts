import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/preact';
import { useWebSocket } from './useWebSocket';

class FakeWS {
  url: string;
  readyState = 0; // CONNECTING
  sent: string[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  constructor(url: string) { this.url = url; FakeWS.instances.push(this); }
  send(data: string) { this.sent.push(data); }
  close() { this.readyState = 3; this.onclose?.({ code: 1000 } as CloseEvent); }
  static instances: FakeWS[] = [];
  triggerOpen() { this.readyState = 1; this.onopen?.({} as Event); }
  triggerMessage(data: unknown) { this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent); }
}

beforeEach(() => { FakeWS.instances.length = 0; (globalThis as unknown as { WebSocket: typeof FakeWS }).WebSocket = FakeWS; });

describe('useWebSocket', () => {
  it('connects, dispatches a snapshot, and exposes send', async () => {
    const { result } = renderHook(() => useWebSocket('ws://test/ws'));
    const ws = FakeWS.instances[0]!;
    await act(async () => { ws.triggerOpen(); ws.triggerMessage({ type: 'snapshot', files: [], clipboard: '' }); });
    expect(result.current.status).toBe('open');
    expect(result.current.events).toHaveLength(1);
    act(() => { result.current.send({ type: 'clipboard', text: 'hi' }); });
    expect(JSON.parse(ws.sent[0]!)).toEqual({ type: 'clipboard', text: 'hi' });
  });
  it('ignores a malformed frame rather than dropping the connection', () => {
    // A truncated or non-JSON frame must not throw out of onmessage: the socket stays open
    // and the page keeps working.
    const { result } = renderHook(() => useWebSocket('ws://test/ws'));
    const ws = FakeWS.instances[0]!;
    act(() => { ws.triggerOpen(); });
    act(() => { ws.onmessage?.({ data: 'not json' } as MessageEvent); });
    expect(result.current.events).toHaveLength(0);
    expect(result.current.status).toBe('open');
  });

  it('reports closed and reconnects after the socket drops', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useWebSocket('ws://test/ws'));
      const ws = FakeWS.instances[0]!;
      act(() => { ws.triggerOpen(); });
      act(() => { ws.onclose?.({ code: 1006 } as CloseEvent); });
      expect(result.current.status).toBe('closed');

      // A Wi-Fi blip should not require a page reload: the hook retries on a backoff.
      act(() => { vi.advanceTimersByTime(1000); });
      expect(FakeWS.instances).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not reconnect after unmount', async () => {
    vi.useFakeTimers();
    try {
      const { unmount } = renderHook(() => useWebSocket('ws://test/ws'));
      const ws = FakeWS.instances[0]!;
      act(() => { ws.triggerOpen(); });
      unmount();
      act(() => { vi.advanceTimersByTime(60_000); });
      // close() during teardown fires onclose, but the cancelled guard must stop the retry.
      expect(FakeWS.instances).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
