import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act, cleanup } from '@testing-library/preact';
import { useWakeLock } from './useWakeLock';

type WakeLockApi = { request: (type?: WakeLockType) => Promise<WakeLockSentinel> };

function setWakeLock(api: WakeLockApi | undefined): void {
  (navigator as unknown as { wakeLock?: WakeLockApi }).wakeLock = api;
}

function makeSentinel(): WakeLockSentinel {
  return {
    release: async () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => true,
    released: false,
    type: 'screen',
    onrelease: null,
  } as unknown as WakeLockSentinel;
}


function setVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
}

// Without this every mounted hook leaves its visibilitychange listener on the shared
// document, and a later dispatch fires all of them.
afterEach(cleanup);

beforeEach(() => {
  vi.restoreAllMocks();
  setWakeLock({ request: async () => makeSentinel() });
});

describe('useWakeLock', () => {
  it('is a silent no-op when wakeLock is missing', () => {
    setWakeLock(undefined);
    expect(() => renderHook(() => useWakeLock())).not.toThrow();
  });

  it('requests a screen lock on mount', async () => {
    const req = vi.fn().mockResolvedValue(makeSentinel());
    setWakeLock({ request: req });
    renderHook(() => useWakeLock());
    await act(async () => {});
    expect(req).toHaveBeenCalledWith('screen');
  });
  it('does not touch the API at all when the browser lacks it', () => {
    // setWakeLock(undefined) leaves the property present; the early return is keyed on the
    // property existing, so it has to actually be removed to exercise that path.
    delete (navigator as unknown as { wakeLock?: WakeLockApi }).wakeLock;
    expect(() => renderHook(() => useWakeLock())).not.toThrow();
    expect('wakeLock' in navigator).toBe(false);
  });

  it('survives a rejected request', async () => {
    // Safari refuses the lock in a background tab. That must not surface to the user.
    const req = vi.fn().mockRejectedValue(new Error('denied'));
    setWakeLock({ request: req });
    expect(() => renderHook(() => useWakeLock())).not.toThrow();
    await act(async () => {});
    expect(req).toHaveBeenCalled();
  });

  it('re-acquires the lock when the tab becomes visible again', async () => {
    // Backgrounding a tab releases the lock, so returning to it has to ask again -- otherwise
    // the phone sleeps mid-transfer.
    const req = vi.fn().mockResolvedValue(null as unknown as WakeLockSentinel);
    setWakeLock({ request: req });
    renderHook(() => useWakeLock());
    await act(async () => {});
    expect(req).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(req).toHaveBeenCalledTimes(2);
  });

  it('does not re-acquire while a lock is already held', async () => {
    const req = vi.fn().mockResolvedValue(makeSentinel());
    setWakeLock({ request: req });
    renderHook(() => useWakeLock());
    await act(async () => {});

    setVisibility('visible');
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(req).toHaveBeenCalledTimes(1);
  });

  it('releases the lock and unsubscribes on unmount', async () => {
    const release = vi.fn().mockResolvedValue(undefined);
    const sentinel = { ...makeSentinel(), release } as unknown as WakeLockSentinel;
    setWakeLock({ request: vi.fn().mockResolvedValue(sentinel) });
    const removeSpy = vi.spyOn(document, 'removeEventListener');
    const { unmount } = renderHook(() => useWakeLock());
    await act(async () => {});
    unmount();
    expect(release).toHaveBeenCalled();
    expect(removeSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
  });
});
