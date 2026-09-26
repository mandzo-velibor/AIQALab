import { defineConfig } from '@playwright/test';

/**
 * Config for the golden eval suite.
 *
 * <p>Two runs are needed to score recall: one against the defective fixtures (where a
 * correct oracle test must fail) and one against the correct twins (where it must pass).
 * A test that fails in both is a false positive, not a detection — the harness
 * distinguishes them, which is why the correct twins exist at all.</p>
 *
 * <p>Deterministic on purpose: workers 1, no retries. A flake in the measurement
 * instrument is worse than no instrument, and the harness reports flake rate separately
 * from recall for exactly this reason.</p>
 */
export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['json', { outputFile: process.env.QALAB_EVAL_JSON || '/tmp/qalab-eval.json' }]],
  use: { headless: true, screenshot: 'off', video: 'off', trace: 'off' },
  timeout: 15_000,
});
