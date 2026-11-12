import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the full Docker stack (Phase 14): the real web app behind Caddy (with production security
 * headers), the gateway and all five services, with the fake Google sign-in from docker-compose.e2e.yml.
 * The stack must already be running; see e2e/README.md.
 */
export default defineConfig({
  testDir: './tests',
  // The journeys share one database, and the setup project publishes the exam the others use.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }], ['github']] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8088',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'setup', testMatch: /00-.*\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'desktop', testIgnore: /00-.*\.spec\.ts/, dependencies: ['setup'], use: { ...devices['Desktop Chrome'] } },
  ],
});
