import { useEffect } from 'preact/hooks';

export function useWakeLock() {
  useEffect(() => {
    if (!('wakeLock' in navigator)) return;
    let sentinel: WakeLockSentinel | null = null;
    const request = async () => { try { sentinel = await navigator.wakeLock.request('screen'); } catch { /* ignore */ } };
    const onVisibility = () => { if (document.visibilityState === 'visible' && !sentinel) request(); };
    request();
    document.addEventListener('visibilitychange', onVisibility);
    return () => { document.removeEventListener('visibilitychange', onVisibility); sentinel?.release().catch(() => {}); };
  }, []);
}
