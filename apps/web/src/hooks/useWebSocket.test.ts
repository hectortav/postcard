import { describe, it, expect, beforeEach } from 'vitest';
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
});
