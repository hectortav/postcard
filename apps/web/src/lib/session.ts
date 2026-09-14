/**
 * What the server says about this client's session.
 *
 * Every field here is a server decision delivered as data, never something the page works out
 * for itself. That is what keeps the owned desktop window and a phone browser running the same
 * code: the host's window is not special because it sniffs anything, it is special because the
 * server recognises its address and says so. See AGENTS.md on native/web parity.
 */
export type Session = {
  /** A PIN is armed, so the dashboard is gated until this client verifies. */
  pinRequired: boolean;
  /** This client may change the PIN (the host, not a receiver). */
  manageable: boolean;
  /** Downloads for this client arrive encrypted and must be decrypted in the browser. */
  encrypted: boolean;
  /** Digits the lock screen should ask for. */
  pinLength: number;
};

export const DEFAULT_SESSION: Session = {
  pinRequired: false,
  manageable: false,
  encrypted: false,
  pinLength: 4,
};

export async function fetchSession(signal?: AbortSignal): Promise<Session> {
  const res = await fetch('/api/session', { cache: 'no-store', signal });
  if (!res.ok) throw new Error(`session ${res.status}`);
  const body = (await res.json()) as Partial<Session>;
  return {
    pinRequired: body.pinRequired === true,
    manageable: body.manageable === true,
    encrypted: body.encrypted === true,
    pinLength: typeof body.pinLength === 'number' && body.pinLength > 0 ? body.pinLength : 4,
  };
}
