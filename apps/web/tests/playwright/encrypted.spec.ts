import { test, expect } from '@playwright/test';

test('encrypted download ciphertext is not plaintext', async ({ request }) => {
  const up = await request.post('/api/upload', { multipart: { file: { name: 'secret.txt', mimeType: 'text/plain', buffer: Buffer.from('super-secret-payload') } } });
  const { id } = await up.json();
  const res = await request.get(`/api/download/${id}`);
  expect(res.status()).toBe(200);
  const body = await res.body();
  expect(body.toString('utf8')).not.toContain('super-secret-payload');
});
