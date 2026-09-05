// Dev-only observability bridge to the Spotlight sidecar.
//
// Spotlight (https://spotlightjs.com) runs a local sidecar on :8969 that
// collects Sentry envelopes and serves a UI at http://localhost:8969. The
// `sentry-spotlight` MCP server in `.mcp.json` reads the same buffer, which is
// what lets an agent answer "what blew up in the browser just now?" without a
// screenshot or a pasted stack trace.
//
// Two properties of this file matter more than usual for postcard:
//
//  1. **No DSN, ever.** Without `dsn` the SDK never constructs a transport, so
//     there is no code path that reaches sentry.io. `Client.sendEnvelope`
//     emits its `beforeEnvelope` hook *before* the transport check, and that
//     hook is the only thing `spotlightBrowserIntegration` needs — so a
//     DSN-less client still feeds the local sidecar and nothing else. That
//     keeps the "no cloud, no accounts" promise literally true rather than
//     merely configured.
//
//  2. **It never ships.** `main.tsx` reaches this module through a dynamic
//     import guarded by `import.meta.env.DEV`, which Vite replaces with the
//     literal `false` in a production build. Rollup then drops the branch and
//     never emits the chunk, so `@sentry/browser` stays a devDependency and
//     contributes zero bytes to `src/main/resources/public` — the bundle that
//     gets embedded in the JAR users run offline. `spotlight.test.ts` pins the
//     DSN-less contract; the bundle itself is guarded by `scripts/assert-no-
//     sentry-in-bundle.mjs`, which the release build runs after `vite build`.
import {
  browserTracingIntegration,
  consoleLoggingIntegration,
  init,
  spotlightBrowserIntegration,
} from '@sentry/browser';

/** Where the sidecar accepts envelopes. `spotlight` CLI default. */
export const SIDECAR_STREAM_URL = 'http://localhost:8969/stream';

/**
 * Point the Sentry browser SDK at a local Spotlight sidecar.
 *
 * Safe to call when no sidecar is running: the integration retries a few
 * times, logs one "is it running?" warning, then disables itself. A developer
 * who never starts `pnpm spotlight` pays nothing but that warning.
 */
export function initSpotlight(sidecarUrl: string = SIDECAR_STREAM_URL): void {
  init({
    // Deliberately absent — see note 1 above. Do not add a DSN here.
    dsn: undefined,
    // Dev builds only, so there is no release/environment story to tell; the
    // sidecar buffer is per-session and thrown away when it restarts.
    environment: 'development',
    // Every transaction, because a dev session produces a handful of page
    // loads and uploads rather than production traffic worth sampling.
    tracesSampleRate: 1,
    // Surfaces `console.warn`/`console.error` as structured logs so the MCP's
    // `search_logs` has something to search. `debug`/`info` are left out on
    // purpose: Preact and Vite are chatty in dev and the noise buries the
    // lines that matter.
    enableLogs: true,
    integrations: [
      spotlightBrowserIntegration({ sidecarUrl }),
      browserTracingIntegration(),
      consoleLoggingIntegration({ levels: ['warn', 'error'] }),
    ],
  });
}
