import { test, expect } from '@playwright/test';

test('the websocket sends no snapshot before the PIN is verified', async ({ page, baseURL }) => {
  // /api/files was gated on the PIN and /ws was not, and the snapshot carries every filename,
  // size and hash plus the shared clipboard text. Anyone on the LAN could read the lot by
  // opening a socket, without ever knowing the PIN.
  const wsUrl = baseURL!.replace(/^http/, 'ws').replace(/#.*$/, '').replace(/\/$/, '') + '/ws';

  await page.goto(baseURL!);
  const outcome = await page.evaluate(
    (url) =>
      new Promise<string>((resolve) => {
        const ws = new WebSocket(url);
        const timer = setTimeout(() => resolve('silent'), 3000);
        ws.onmessage = (e) => {
          clearTimeout(timer);
          resolve('frame:' + String(e.data).slice(0, 40));
        };
        ws.onclose = () => {
          clearTimeout(timer);
          resolve('closed');
        };
        ws.onerror = () => {
          clearTimeout(timer);
          resolve('closed');
        };
      }),
    wsUrl,
  );
  expect(outcome, 'an unverified socket must receive no snapshot').not.toContain('snapshot');
});
