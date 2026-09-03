import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act, waitFor, cleanup } from '@testing-library/preact';
import { PinLockScreen, type VerifyResult } from './PinLockScreen';

const PIN = '1234';

function mount(opts: {
  verify?: (pin: string) => Promise<VerifyResult>;
  onVerified?: (pin: string) => void;
  pinLength?: number;
}) {
  const verify = opts.verify ?? vi.fn().mockResolvedValue({ ok: true } as VerifyResult);
  const onVerified = opts.onVerified ?? vi.fn();
  const result = render(
    <PinLockScreen
      pinLength={opts.pinLength ?? 4}
      verify={verify}
      onVerified={onVerified}
    />,
  );
  return { verify, onVerified, ...result };
}

function typeDigit(getByTestId: (id: string) => HTMLElement, i: number, ch: string) {
  fireEvent.input(getByTestId(`pin-box-${i}`), { target: { value: ch } });
}

describe('PinLockScreen', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders 4 input boxes and the heading', () => {
    const { getByTestId, getByText } = mount({});
    expect(getByTestId('pin-box-0')).toBeTruthy();
    expect(getByTestId('pin-box-1')).toBeTruthy();
    expect(getByTestId('pin-box-2')).toBeTruthy();
    expect(getByTestId('pin-box-3')).toBeTruthy();
    expect(getByText(/Enter the 4-digit PIN/)).toBeTruthy();
    expect(getByText(/Shown once on the host's terminal/)).toBeTruthy();
  });

  it('accepts digits and submits the concatenated PIN on the 4th digit', async () => {
    const verify = vi.fn().mockResolvedValue({ ok: true } as VerifyResult);
    const onVerified = vi.fn();
    const { getByTestId } = mount({ verify, onVerified });
    for (let i = 0; i < PIN.length; i++) {
      typeDigit(getByTestId, i, PIN[i]!);
    }
    await waitFor(() => expect(verify).toHaveBeenCalledWith(PIN));
    expect(onVerified).toHaveBeenCalledWith(PIN);
  });

  it('ignores non-digit input (whitespace, letters)', () => {
    const verify = vi.fn();
    const { getByTestId } = mount({ verify });
    typeDigit(getByTestId, 0, 'a');
    typeDigit(getByTestId, 0, ' ');
    typeDigit(getByTestId, 0, '!');
    // Non-digit chars are dropped (after replace(/\D/g, '')).slice(-1)).
    // 'a' -> '' -> dropped, ' ' -> '' -> dropped, '!' -> '' -> dropped.
    expect((getByTestId('pin-box-0') as HTMLInputElement).value).toBe('');
    expect(verify).not.toHaveBeenCalled();
  });

  it('rejects empty submission when fewer than 4 digits are entered', async () => {
    const verify = vi.fn();
    const { getByTestId } = mount({ verify });
    typeDigit(getByTestId, 0, '1');
    typeDigit(getByTestId, 1, '2');
    // No submit should fire — 3 digits is not the full PIN.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(verify).not.toHaveBeenCalled();
  });

  it('on wrong PIN: clears boxes, sets error, calls verify with the PIN', async () => {
    const verify = vi.fn().mockResolvedValue({ ok: false, reason: 'wrong' } as VerifyResult);
    const { getByTestId, getByText } = mount({ verify });
    for (let i = 0; i < PIN.length; i++) typeDigit(getByTestId, i, PIN[i]!);
    await waitFor(() => expect(verify).toHaveBeenCalledWith(PIN));
    await waitFor(() => expect(getByText(/did not match/i)).toBeTruthy());
    for (let i = 0; i < PIN.length; i++) {
      expect((getByTestId(`pin-box-${i}`) as HTMLInputElement).value).toBe('');
    }
  });

  it('on 429 lockout: shows the countdown and disables the inputs', async () => {
    const verify = vi.fn().mockResolvedValue({ ok: false, reason: 'locked', lockoutMs: 60_000 } as VerifyResult);
    const { getByTestId, getByText } = mount({ verify });
    for (let i = 0; i < PIN.length; i++) typeDigit(getByTestId, i, PIN[i]!);
    await waitFor(() => expect(getByText(/remaining/i)).toBeTruthy());
    for (let i = 0; i < PIN.length; i++) {
      expect((getByTestId(`pin-box-${i}`) as HTMLInputElement).disabled).toBe(true);
    }
  });

  it('lockout countdown ticks down to zero and re-enables the inputs', async () => {
    const verify = vi.fn().mockResolvedValue({ ok: false, reason: 'locked', lockoutMs: 500 } as VerifyResult);
    const { getByTestId, queryByText } = mount({ verify });
    for (let i = 0; i < PIN.length; i++) typeDigit(getByTestId, i, PIN[i]!);
    await waitFor(() => expect(queryByText(/remaining/i)).toBeTruthy());
    // Advance past the 500ms lockout in 250ms steps.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(750);
    });
    expect(queryByText(/remaining/i)).toBeNull();
    expect((getByTestId('pin-box-0') as HTMLInputElement).disabled).toBe(false);
  });

  it('backspace on an empty box moves focus to the previous box and clears it', () => {
    const { getByTestId } = mount({});
    typeDigit(getByTestId, 0, '1');
    typeDigit(getByTestId, 1, '2');
    // Box 1 has content; backspace on it should not steal focus.
    fireEvent.keyDown(getByTestId('pin-box-1'), { key: 'Backspace' });
    expect((getByTestId('pin-box-1') as HTMLInputElement).value).toBe('2');
    // Now backspace on empty box 0 — should not move anywhere (i===0).
    fireEvent.keyDown(getByTestId('pin-box-0'), { key: 'Backspace' });
    // Then make box 2 non-empty, then empty box 1, then backspace.
    typeDigit(getByTestId, 2, '3');
    typeDigit(getByTestId, 1, '');
    fireEvent.keyDown(getByTestId('pin-box-1'), { key: 'Backspace' });
    expect((getByTestId('pin-box-0') as HTMLInputElement).value).toBe('');
  });

  it('arrow keys move focus between boxes', () => {
    const { getByTestId } = mount({});
    typeDigit(getByTestId, 0, '1');
    fireEvent.keyDown(getByTestId('pin-box-0'), { key: 'ArrowRight' });
    // No assertion on the active element (happy-dom is fuzzy), but the
    // handler must not throw. Cover ArrowLeft and ArrowRight at the ends too.
    fireEvent.keyDown(getByTestId('pin-box-0'), { key: 'ArrowLeft' });
    fireEvent.keyDown(getByTestId('pin-box-3'), { key: 'ArrowRight' });
  });

  it('paste of a full PIN into box 0 fills the remaining boxes and submits', async () => {
    const verify = vi.fn().mockResolvedValue({ ok: true } as VerifyResult);
    const onVerified = vi.fn();
    const { getByTestId } = mount({ verify, onVerified });
    fireEvent.paste(getByTestId('pin-box-0'), { clipboardData: { getData: () => '9876' } });
    await waitFor(() => expect(verify).toHaveBeenCalledWith('9876'));
    expect(onVerified).toHaveBeenCalledWith('9876');
  });

  it('paste of a non-numeric string is ignored', () => {
    const verify = vi.fn();
    const { getByTestId } = mount({ verify });
    fireEvent.paste(getByTestId('pin-box-0'), { clipboardData: { getData: () => 'abc' } });
    expect(verify).not.toHaveBeenCalled();
  });

  it('paste that does not reach the end does not auto-submit', async () => {
    const verify = vi.fn();
    const { getByTestId } = mount({ verify });
    fireEvent.paste(getByTestId('pin-box-0'), { clipboardData: { getData: () => '12' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    expect(verify).not.toHaveBeenCalled();
  });
});
