import { test, expect } from '@playwright/test';

test('range request returns 206', async ({ request }) => {
  const up = await request.post('/api/upload', { multipart: { file: { name: 'a.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(8192) } } });
  const { id } = await up.json();
  const res = await request.get(`/api/download/${id}`, { headers: { Range: 'bytes=0-1023' } });
  expect(res.status()).toBe(206);
  expect(res.headers()['content-range']).toBe('bytes 0-1023/8192');
  expect((await res.body()).length).toBe(1024);
});
