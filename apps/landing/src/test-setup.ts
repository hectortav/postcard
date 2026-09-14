import { beforeEach } from 'vitest';

// The release lookup caches in sessionStorage, which is shared across tests in a file and
// would otherwise let one test's fixture answer another test's fetch.
beforeEach(() => {
  try {
    sessionStorage.clear();
  } catch {
    // No storage in this environment; nothing to clear.
  }
});
