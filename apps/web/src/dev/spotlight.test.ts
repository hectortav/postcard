import { describe, expect, it, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({ init: vi.fn() }));

vi.mock('@sentry/browser', () => ({
  init: mocks.init,
  spotlightBrowserIntegration: (options: unknown) => ({ name: 'SpotlightBrowser', options }),
  browserTracingIntegration: () => ({ name: 'BrowserTracing' }),
  consoleLoggingIntegration: (options: unknown) => ({ name: 'ConsoleLogging', options }),
}));

import { initSpotlight, SIDECAR_STREAM_URL } from './spotlight';

const optionsFromLastInit = () => mocks.init.mock.calls.at(-1)?.[0];

const integration = (name: string) =>
  optionsFromLastInit().integrations.find((i: { name: string }) => i.name === name);

describe('initSpotlight', () => {
  beforeEach(() => mocks.init.mockClear());

  // The load-bearing test. postcard promises "no cloud, no accounts"; a DSN
  // is the single line that would turn the dev SDK into an outbound reporter,
  // so it is asserted rather than trusted. Without a DSN the SDK builds no
  // transport at all and the only sink is the local sidecar.
  it('never configures a DSN, so no envelope can leave the machine', () => {
    initSpotlight();

    expect(optionsFromLastInit()).toHaveProperty('dsn', undefined);
  });

  it('points the Spotlight integration at the local sidecar by default', () => {
    initSpotlight();

    expect(SIDECAR_STREAM_URL).toBe('http://localhost:8969/stream');
    expect(integration('SpotlightBrowser').options).toEqual({ sidecarUrl: SIDECAR_STREAM_URL });
  });

  it('honours an overridden sidecar URL', () => {
    initSpotlight('http://127.0.0.1:9999/stream');

    expect(integration('SpotlightBrowser').options).toEqual({
      sidecarUrl: 'http://127.0.0.1:9999/stream',
    });
  });

  // Each integration backs one of the four tools the sentry-spotlight MCP
  // server exposes; dropping one silently blinds `search_traces` or
  // `search_logs` with no other visible symptom.
  it('enables the tracing and log feeds the MCP tools read from', () => {
    initSpotlight();

    expect(optionsFromLastInit().tracesSampleRate).toBe(1);
    expect(optionsFromLastInit().enableLogs).toBe(true);
    expect(integration('BrowserTracing')).toBeDefined();
    expect(integration('ConsoleLogging').options).toEqual({ levels: ['warn', 'error'] });
  });
});
