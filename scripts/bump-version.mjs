#!/usr/bin/env node
/**
 * Set the project version in every place that holds one.
 *
 * The version used to live in six files and hold three different values at once: the tag said
 * v0.0.2, package.json said 0.1.0, and the installers said 1.0. Nothing bumped any of them and
 * nothing checked they agreed, so `postcard --version` on a released binary reported a number
 * that appeared nowhere else.
 *
 *   node scripts/bump-version.mjs 0.2.0
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

const version = process.argv[2];
if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error('usage: node scripts/bump-version.mjs <major.minor.patch>');
  process.exit(2);
}

const packages = [
  'package.json',
  'apps/web/package.json',
  'apps/landing/package.json',
  'apps/cli-server/package.json',
  'packages/config-typescript/package.json',
];

let changed = 0;
for (const rel of packages) {
  const path = join(root, rel);
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch {
    continue; // an optional workspace package
  }
  const next = text.replace(/("version"\s*:\s*")[^"]+(")/, `$1${version}$2`);
  if (next !== text) {
    writeFileSync(path, next);
    console.log(`  ${rel}`);
    changed++;
  }
}

const gradlePath = join(root, 'apps/cli-server/build.gradle.kts');
const gradle = readFileSync(gradlePath, 'utf8');
const gradleVersion = /^version = ".*"$/m;
// Tested separately from the replacement: when the version is already the requested one the
// replace is a no-op, so comparing the strings cannot tell "already correct" from "not found".
if (!gradleVersion.test(gradle)) {
  console.error('could not find `version = "..."` in build.gradle.kts');
  process.exit(1);
}
writeFileSync(gradlePath, gradle.replace(gradleVersion, `version = "${version}"`));
console.log('  apps/cli-server/build.gradle.kts');
changed++;

console.log(`\npostcard is now ${version} in ${changed} files.`);
console.log('Next: update CHANGELOG.md, commit, then tag v' + version + '.');
