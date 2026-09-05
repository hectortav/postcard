#!/usr/bin/env node
// Fails the build if dev-time observability leaked into the shipped bundle.
//
// `src/dev/spotlight.ts` is only reachable behind `import.meta.env.DEV`, which
// Vite replaces with `false` in a production build so Rollup drops the branch
// and the chunk. That is the entire reason it is safe to point a Sentry SDK at
// postcard at all — but it is a property of the bundler's dead-code pass, not
// something the type system or the unit tests can enforce. If a refactor ever
// moves the import out from behind that guard (a top-level `import`, an
// `await import()` in a non-dev path, a `process.env` swap), the bundle would
// start carrying an outbound telemetry client into an app whose entire pitch
// is that it never phones home.
//
// So we check the artifact itself. This runs against outDir, which is
// apps/cli-server/src/main/resources/public — the files that get embedded in
// the JAR users run offline.
import { readdir, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const OUT_DIR = resolve(import.meta.dirname, '../../cli-server/src/main/resources/public');

// Matched against bundle text. Kept narrow on purpose: a bare /sentry/ would
// trip over unrelated words, and the sidecar port is the unambiguous tell.
const FORBIDDEN = [
  ['@sentry/browser import', /@sentry\/(browser|core)/],
  ['Spotlight sidecar URL', /localhost:8969/],
  ['Sentry envelope content type', /application\/x-sentry-envelope/],
  ['Sentry SDK global', /getSentryCarrier|SentryBrowser|spotlightBrowserIntegration/],
];

const walk = async (dir) => {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = await Promise.all(
    entries.map((e) => {
      const path = join(dir, e.name);
      return e.isDirectory() ? walk(path) : [path];
    }),
  );
  return files.flat();
};

let files;
try {
  files = (await walk(OUT_DIR)).filter((f) => /\.(js|mjs|cjs|html|css)$/.test(f));
} catch (err) {
  if (err.code === 'ENOENT') {
    console.error(`✗ ${OUT_DIR} does not exist — run \`pnpm build\` first.`);
    process.exit(1);
  }
  throw err;
}

if (files.length === 0) {
  console.error(`✗ No bundle files found in ${OUT_DIR} — did the build succeed?`);
  process.exit(1);
}

const violations = [];
for (const file of files) {
  const text = await readFile(file, 'utf8');
  for (const [label, pattern] of FORBIDDEN) {
    if (pattern.test(text)) violations.push(`${file.slice(OUT_DIR.length + 1)}: ${label}`);
  }
}

if (violations.length > 0) {
  console.error('✗ Dev-only observability leaked into the production bundle:\n');
  for (const v of violations) console.error(`    ${v}`);
  console.error(
    '\n  src/dev/spotlight.ts must stay behind `if (import.meta.env.DEV)` in main.tsx.\n',
  );
  process.exit(1);
}

console.log(`✓ No Sentry/Spotlight code in the production bundle (${files.length} files checked)`);
