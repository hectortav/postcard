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
  onerror: ((ev: Event) => void) | null = null;
  static readonly OPEN = 1;
  constructor(url: string) {
    this.url = url;
    FakeWS.instances.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1000 } as CloseEvent);
  }
  static instances: FakeWS[] = [];
  triggerOpen() {
    this.readyState = 1;
    this.onopen?.({} as Event);
  }
  triggerMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent);
  }
}

beforeEach(() => {
  FakeWS.instances.length = 0;
  (globalThis as unknown as { WebSocket: typeof FakeWS }).WebSocket = FakeWS;
});

describe('useWebSocket', () => {
  it('connects, dispatches a snapshot, and exposes send', async () => {
    const seen: unknown[] = [];
    const { result } = renderHook(() => useWebSocket('ws://test/ws', (e) => seen.push(e)));
    const ws = FakeWS.instances[0]!;
    await act(async () => {
      ws.triggerOpen();
      ws.triggerMessage({ type: 'snapshot', files: [], clipboard: '' });
    });
    expect(result.current.status).toBe('open');
    expect(seen).toHaveLength(1);
    act(() => {
      result.current.send({ type: 'clipboard', text: 'hi' });
    });
    expect(JSON.parse(ws.sent[0]!)).toEqual({ type: 'clipboard', text: 'hi' });
  });

  it('delivers every frame, including two in the same tick', () => {
    // The previous version accumulated frames into state and the only consumer read the last
    // one from an effect, so the first of a pair arriving together was silently dropped.
    const seen: unknown[] = [];
    renderHook(() => useWebSocket('ws://test/ws', (e) => seen.push(e)));
    const ws = FakeWS.instances[0]!;
    act(() => {
      ws.triggerOpen();
      ws.triggerMessage({ type: 'file_added', id: 'a', name: 'a', size: 1, mtime: 0, sha256: 'x' });
      ws.triggerMessage({ type: 'file_added', id: 'b', name: 'b', size: 1, mtime: 0, sha256: 'x' });
    });
    expect(seen).toHaveLength(2);
  });

  it('refuses to send on a socket that is not open, rather than losing the frame quietly', () => {
    const { result } = renderHook(() => useWebSocket('ws://test/ws'));
    const ws = FakeWS.instances[0]!;
    // Still CONNECTING: the real WebSocket throws InvalidStateError here.
    expect(result.current.send({ type: 'clipboard', text: 'lost' })).toBe(false);
    expect(ws.sent).toHaveLength(0);
    act(() => {
      ws.triggerOpen();
    });
    expect(result.current.send({ type: 'clipboard', text: 'kept' })).toBe(true);
  });

  it('reports a socket error as not connected', () => {
    const { result } = renderHook(() => useWebSocket('ws://test/ws'));
    const ws = FakeWS.instances[0]!;
    act(() => {
      ws.triggerOpen();
    });
    expect(result.current.status).toBe('open');
    act(() => {
      ws.readyState = 3;
      ws.onerror?.({} as Event);
    });
    expect(result.current.status).toBe('closed');
  });
  it('ignores a malformed frame rather than dropping the connection', () => {
    // A truncated or non-JSON frame must not throw out of onmessage: the socket stays open
    // and the page keeps working.
    const { result } = renderHook(() => useWebSocket('ws://test/ws'));
    const ws = FakeWS.instances[0]!;
    act(() => {
      ws.triggerOpen();
    });
    act(() => {
      ws.onmessage?.({ data: 'not json' } as MessageEvent);
    });
    expect(result.current.status).toBe('open');
  });

  it('reports closed and reconnects after the socket drops', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useWebSocket('ws://test/ws'));
      const ws = FakeWS.instances[0]!;
      act(() => {
        ws.triggerOpen();
      });
      act(() => {
        ws.onclose?.({ code: 1006 } as CloseEvent);
      });
      expect(result.current.status).toBe('closed');

      // A Wi-Fi blip should not require a page reload: the hook retries on a backoff.
      act(() => {
        vi.advanceTimersByTime(1000);
      });
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
      act(() => {
        ws.triggerOpen();
      });
      unmount();
      act(() => {
        vi.advanceTimersByTime(60_000);
      });
      // close() during teardown fires onclose, but the cancelled guard must stop the retry.
      expect(FakeWS.instances).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
