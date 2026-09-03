import { expect, test } from '@playwright/test'

test('login route is reachable without horizontal overflow', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '实验室预约' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
})
