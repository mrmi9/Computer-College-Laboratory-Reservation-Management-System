import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'

function nextWeekdayIso(): string {
  const date = new Date()
  date.setDate(date.getDate() + 2)
  while (date.getDay() === 0 || date.getDay() === 6) date.setDate(date.getDate() + 1)
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

async function login(browser: Browser, username: string): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/login')
  const inputs = page.locator('.auth-panel input')
  await inputs.nth(0).fill(username)
  await inputs.nth(1).fill('ChangeMe123!')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  return { context, page }
}

async function navigate(page: Page, label: string): Promise<void> {
  await page.locator('.sidebar').getByRole('link', { name: label }).click()
}

async function createReservation(page: Page, title: string, date: string): Promise<number> {
  await page.goto(`/reservations/new?date=${date}&period=1&people=1`)
  await expect(page.getByRole('heading', { name: '创建实验室预约' })).toBeVisible()
  await page.getByRole('button', { name: '查询可用实验室' }).click()
  const lab = page.locator('button.selection-card').filter({ hasText: '人工智能实验室' })
  await expect(lab).toBeVisible()
  await lab.click()
  await page.getByRole('button', { name: '下一步' }).click()
  const inputs = page.locator('.wizard-panel input')
  await inputs.nth(0).fill(title)
  await inputs.nth(1).fill('真实后端 E2E')
  await inputs.nth(2).fill('13800000001')
  await page.locator('.wizard-panel textarea').nth(0).fill('验证 Nginx、真实 API、事务和页面闭环')
  await page.getByRole('button', { name: '核对申请' }).click()
  await page.getByRole('button', { name: '确认并提交' }).click()
  await expect(page).toHaveURL(/\/reservations\/\d+$/)
  const match = page.url().match(/\/reservations\/(\d+)$/)
  if (match === null) throw new Error('reservation id missing from detail URL')
  return Number(match[1])
}

test('真实 PostgreSQL 上完成学生人工审批、教师自动审批和取消闭环', async ({ browser }) => {
  const date = nextWeekdayIso()
  const suffix = Date.now().toString()
  const studentTitle = `学生真实链路 ${suffix}`
  const teacherTitle = `教师真实链路 ${suffix}`
  const student = await login(browser, 'student01')
  const administrator = await login(browser, 'labadmin01')
  const teacher = await login(browser, 'teacher01')

  try {
    const studentReservationId = await createReservation(student.page, studentTitle, date)
    await expect(student.page.locator('.page-heading .el-tag')).toContainText('待审批')

    await navigate(administrator.page, '审批与现场')
    const row = administrator.page.locator('.el-table__row').filter({ hasText: studentTitle })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '通过', exact: true }).click()
    await administrator.page.locator('.el-message-box').getByRole('button', { name: '确认通过' }).click()
    await expect(administrator.page.getByText('审批已通过')).toBeVisible()

    await student.page.goto(`/reservations/${studentReservationId}`)
    await expect(student.page.locator('.page-heading .el-tag')).toContainText('已通过')
    await student.page.getByRole('button', { name: '取消预约' }).click()
    await student.page.locator('.el-message-box input').fill('真实链路验证完成')
    await student.page.locator('.el-message-box').getByRole('button', { name: '确认取消' }).click()
    await expect(student.page.locator('.page-heading .el-tag')).toContainText('已取消')

    const teacherReservationId = await createReservation(teacher.page, teacherTitle, date)
    await expect(teacher.page.locator('.page-heading .el-tag')).toContainText('已通过')
    await teacher.page.getByRole('button', { name: '取消预约' }).click()
    await teacher.page.locator('.el-message-box input').fill('教师自动审批链路验证完成')
    await teacher.page.locator('.el-message-box').getByRole('button', { name: '确认取消' }).click()
    await expect(teacher.page.locator('.page-heading .el-tag')).toContainText('已取消')
    expect(teacherReservationId).toBeGreaterThan(studentReservationId)
  } finally {
    await Promise.all([student.context.close(), administrator.context.close(), teacher.context.close()])
  }
})
