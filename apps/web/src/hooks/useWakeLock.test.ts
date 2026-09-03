import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/preact';
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

beforeEach(() => {
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
});
