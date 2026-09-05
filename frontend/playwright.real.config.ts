import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:58088',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'real-backend-chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 720 } },
    },
  ],
})
