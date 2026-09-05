import { expect, test, type Page, type Route } from '@playwright/test'

const period = { periodNo: 1, name: '第一大节', startTime: '08:00:00', endTime: '09:40:00', enabled: true, version: 0 }
const lab = { id: 101, code: 'LAB-A301', name: '人工智能实验室', building: '计算机楼', roomNo: 'A301', capacity: 60, labType: '专业实验室', description: 'GPU 教学与科研实验室', tags: ['GPU', 'Linux'], status: 'ACTIVE', studentApprovalMode: 'MANUAL', teacherApprovalMode: 'AUTO', allowStudentBooking: true, maxPeriodsPerUserDay: 2, advanceDays: 14, cancelBeforeMinutes: 120, requireCheckIn: true, version: 0 }
const equipment = { id: 201, labId: 101, labName: '人工智能实验室', assetCode: 'GPU-A301', category: '计算设备', name: 'GPU 工作站', model: 'RTX', totalQuantity: 20, requiredQualification: 'GPU_LAB_TRAINING', status: 'AVAILABLE', version: 0 }

function envelope(data: unknown, code = 'OK', message = 'success'): Record<string, unknown> {
  return { code, message, data, requestId: 'e2e-request', timestamp: '2026-09-05T17:00:00+08:00' }
}

function reservation(status = 'PENDING_APPROVAL', title = '机器学习实验'): Record<string, unknown> {
  const nextAction = status === 'PENDING_APPROVAL' ? 'WAIT_FOR_APPROVAL' : status === 'APPROVED' ? 'CHECK_IN' : status === 'IN_USE' ? 'CHECK_OUT' : 'NONE'
  return { id: 9001, reservationNo: 'R202609050001', applicantId: 1001, applicantName: '演示学生', applicantType: 'STUDENT', labId: 101, labName: '人工智能实验室', building: '计算机楼', roomNo: 'A301', title, purpose: '训练课程模型', participantCount: 20, bookingDate: '2026-09-08', periodNo: 1, periodName: '第一大节', startTime: '08:00:00', endTime: '09:40:00', status, contactPhone: '13800000001', projectOrCourse: '机器学习', version: status === 'PENDING_APPROVAL' ? 0 : 1, createdAt: '2026-09-05T17:00:00+08:00', updatedAt: '2026-09-05T17:00:00+08:00', nextAction, equipmentItems: [] }
}

async function installApi(page: Page, options: { initialStatus?: string; conflictOnFirst?: boolean } = {}): Promise<{ getCreateAttempts: () => number }> {
  let currentReservation = reservation(options.initialStatus)
  let createAttempts = 0
  let currentSession: Record<string, unknown> | null = null
  let currentUsername = ''
  const blackouts: Array<Record<string, unknown>> = []
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const method = request.method()
    const json = async (data: unknown, status = 200, headers: Record<string, string> = {}): Promise<void> => route.fulfill({ status, headers, contentType: 'application/json; charset=utf-8', body: JSON.stringify(data) })
    if (path === '/auth/login' && method === 'POST') {
      const body = request.postDataJSON() as { username: string }
      currentUsername = body.username
      const profiles: Record<string, { realName: string; userType: string; roles: string[]; permissions: string[] }> = {
        student01: { realName: '演示学生', userType: 'STUDENT', roles: ['STUDENT'], permissions: ['lab:read', 'equipment:read', 'reservation:create', 'reservation:read:self'] },
        teacher01: { realName: '演示教师', userType: 'TEACHER', roles: ['TEACHER'], permissions: ['lab:read', 'equipment:read', 'reservation:create', 'reservation:read:self'] },
        labadmin01: { realName: '实验室管理员', userType: 'STAFF', roles: ['LAB_ADMIN'], permissions: ['lab:read', 'lab:write', 'equipment:read', 'equipment:write', 'reservation:read:managed', 'reservation:approve', 'reservation:cancel:any', 'checkin:manage', 'statistics:read'] },
        sysadmin01: { realName: '系统管理员', userType: 'STAFF', roles: ['SYSTEM_ADMIN'], permissions: ['lab:read', 'lab:write', 'equipment:read', 'equipment:write', 'reservation:create', 'reservation:read:self', 'reservation:read:managed', 'reservation:approve', 'reservation:cancel:any', 'checkin:manage', 'statistics:read', 'user:write', 'role:write', 'audit:read', 'settings:write'] },
      }
      const profile = profiles[body.username] ?? profiles.student01
      currentSession = { accessToken: 'mock-access-token', expiresAt: '2099-01-01T00:00:00Z', csrfToken: 'mock-csrf', user: { id: body.username === 'sysadmin01' ? 1004 : body.username === 'labadmin01' ? 1003 : body.username === 'teacher01' ? 1002 : 1001, username: body.username, department: '计算机学院', email: `${body.username}@example.invalid`, phone: '13800000001', mustChangePassword: false, ...profile } }
      await json(envelope(currentSession), 200, { 'Set-Cookie': 'lab_csrf=mock-csrf; Path=/; SameSite=Strict' })
      return
    }
    if (path === '/auth/refresh' && method === 'POST' && currentSession !== null) { await json(envelope(currentSession)); return }
    if (path === '/notifications' && method === 'GET') { await json(envelope({ items: [], page: 0, size: 100, total: 0 })); return }
    if (path === '/notifications/read-all' && method === 'PUT') { await json(envelope(0)); return }
    if (path === '/reservations/my' && method === 'GET') { await json(envelope({ items: [currentReservation], page: 0, size: 20, total: 1 })); return }
    if (path === '/course-periods') { await json(envelope([period, { ...period, periodNo: 2, name: '第二大节', startTime: '10:00:00', endTime: '11:40:00' }])); return }
    if (path === '/availability/labs') { await json(envelope([{ id: lab.id, code: lab.code, name: lab.name, building: lab.building, roomNo: lab.roomNo, capacity: lab.capacity, labType: lab.labType, tags: lab.tags }])); return }
    if (path === '/labs' && method === 'GET') { await json(envelope({ items: [lab], page: 0, size: 100, total: 1 })); return }
    if (path === '/labs/101' && method === 'GET') { await json(envelope(lab)); return }
    if (path === '/labs/101/calendar' && method === 'GET') {
      const from = url.searchParams.get('from') ?? '2026-09-06'
      await json(envelope(Array.from({ length: 4 }, (_, index) => ({ bookingDate: from, periodNo: index + 1, periodName: `第${index + 1}大节`, startTime: '08:00:00', endTime: '09:40:00', slotStatus: index === 0 ? 'RESERVED' : 'AVAILABLE' })))); return
    }
    if (path === '/labs/101/open-rules' && method === 'GET') { await json(envelope([{ id: 1, labId: 101, dayOfWeek: 1, periodNo: 1 }])); return }
    if (path === '/labs/101/open-rules' && method === 'PUT') { await json(envelope(request.postDataJSON())); return }
    if (path === '/labs/101/blackouts' && method === 'GET') { await json(envelope(blackouts)); return }
    if (path === '/labs/101/blackouts' && method === 'POST') { const body = request.postDataJSON() as Record<string, unknown>; blackouts.push({ id: 71, labId: 101, createdBy: 1003, createdByName: '实验室管理员', createdAt: '2026-09-05T17:00:00+08:00', ...body }); await json(envelope(blackouts[0])); return }
    if (path.startsWith('/labs/101/blackouts/') && method === 'DELETE') { await json(envelope(null)); return }
    if (path === '/equipment' && method === 'GET') { await json(envelope({ items: [equipment], page: 0, size: 100, total: 1 })); return }
    if (path === '/reservations' && method === 'POST') {
      createAttempts += 1
      if (options.conflictOnFirst !== false && createAttempts === 1) { await json(envelope(null, 'RESERVATION_SLOT_CONFLICT', '所选实验室课次已被占用'), 409); return }
      currentReservation = reservation(currentUsername === 'teacher01' ? 'APPROVED' : 'PENDING_APPROVAL', (request.postDataJSON() as { title: string }).title)
      if (currentUsername === 'teacher01') currentReservation = { ...currentReservation, applicantId: 1002, applicantName: '演示教师', applicantType: 'TEACHER' }
      await json(envelope(currentReservation)); return
    }
    if (path === '/reservations/9001' && method === 'GET') { await json(envelope(currentReservation)); return }
    if (path === '/reservations/9002' && method === 'GET') { await json(envelope(null, 'RESERVATION_SCOPE_DENIED', '无权查看他人预约'), 403); return }
    if (path === '/reservations/9001/cancel' && method === 'POST') { currentReservation = { ...currentReservation, status: 'CANCELLED', cancellationReason: (request.postDataJSON() as { reason: string }).reason, nextAction: 'NONE', version: 1 }; await json(envelope(currentReservation)); return }
    if (path === '/admin/reservations' && method === 'GET') { await json(envelope({ items: [currentReservation], page: 0, size: 20, total: 1 })); return }
    if (path === '/admin/reservations/9001/approve' && method === 'POST') { currentReservation = reservation('APPROVED'); await json(envelope(currentReservation)); return }
    if (path === '/admin/reservations/9001/reject' && method === 'POST') { currentReservation = { ...reservation('REJECTED'), cancellationReason: (request.postDataJSON() as { reason: string }).reason }; await json(envelope(currentReservation)); return }
    if (path === '/admin/reservations/9001/check-in' && method === 'POST') { currentReservation = reservation('IN_USE'); await json(envelope(currentReservation)); return }
    if (path === '/admin/reservations/9001/check-out' && method === 'POST') { currentReservation = reservation('COMPLETED'); await json(envelope(currentReservation)); return }
    if (path === '/admin/reservations/9001/history') { await json(envelope([{ id: 1, toStatus: 'APPROVED', source: 'ADMIN', createdAt: '2026-09-05T17:00:00+08:00' }])); return }
    if (path === '/statistics/overview') { await json(envelope({ from: '2026-08-01', to: '2026-10-01', total: 5, effective: 3, cancelled: 1, noShow: 1, approvalRate: 60, cancellationRate: 20, noShowRate: 20, student: { total: 3, effective: 2 }, teacher: { total: 2, effective: 1 } })); return }
    if (path === '/statistics/lab-usage') { await json(envelope([{ labId: 101, labCode: 'LAB-A301', labName: '人工智能实验室', availableSlots: 20, occupiedSlots: 10, utilizationRate: 50 }])); return }
    if (path === '/statistics/peak-hours') { await json(envelope([{ periodNo: 1, periodName: '第一大节', startTime: '08:00', endTime: '09:40', total: 3, effective: 2 }])); return }
    if (path === '/statistics/equipment-ranking') { await json(envelope([{ equipmentId: 201, assetCode: 'GPU-A301', name: 'GPU 工作站', labId: 101, labCode: 'LAB-A301', quantity: 6 }])); return }
    if (path === '/statistics/export.csv') { await route.fulfill({ status: 200, contentType: 'text/csv; charset=utf-8', headers: { 'Content-Disposition': 'attachment; filename=reservation-report.csv' }, body: '预约编号\r\nR202609050001\r\n' }); return }
    if (path === '/admin/users') { await json(envelope({ items: [], page: 0, size: 100, total: 0 })); return }
    if (path === '/admin/roles') { await json(envelope([])); return }
    if (path === '/admin/permissions') { await json(envelope([])); return }
    if (path === '/admin/settings') { await json(envelope([])); return }
    if (path === '/admin/audit-logs') { await json(envelope({ items: [], page: 0, size: 20, total: 0 })); return }
    await json(envelope(null, 'NOT_FOUND', `Mock route missing: ${method} ${path}`), 404)
  })
  return { getCreateAttempts: () => createAttempts }
}

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  const inputs = page.locator('.auth-panel input')
  await inputs.nth(0).fill(username)
  await inputs.nth(1).fill('ChangeMe123!')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

async function navigate(page: Page, label: string): Promise<void> {
  if ((page.viewportSize()?.width ?? 1280) <= 760) {
    await page.getByRole('button', { name: '打开导航菜单' }).click()
    await page.locator('.el-drawer').getByRole('link', { name: label }).click()
  } else {
    await page.locator('.sidebar').getByRole('link', { name: label }).click()
  }
}

test('学生在 409 后保留表单并完成预约与取消', async ({ page }) => {
  const consoleErrors: string[] = []
  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()) })
  const api = await installApi(page)
  await login(page, 'student01')
  await navigate(page, '创建预约')
  await page.getByRole('button', { name: '查询可用实验室' }).click()
  await page.locator('button.selection-card').click()
  await page.getByRole('button', { name: '下一步' }).click()
  const inputs = page.locator('.wizard-panel input')
  await inputs.nth(0).fill('机器学习课程实验')
  await inputs.nth(1).fill('机器学习 2026 秋')
  await inputs.nth(2).fill('13800000001')
  await page.locator('.wizard-panel textarea').nth(0).fill('完成神经网络训练实验')
  await page.getByRole('button', { name: '核对申请' }).click()
  await page.getByRole('button', { name: '确认并提交' }).click()
  await expect(page.getByText('已保留填写内容并刷新可选列表')).toBeVisible()
  await page.locator('button.selection-card').click()
  await page.getByRole('button', { name: '下一步' }).click()
  await expect(page.locator('.wizard-panel input').nth(0)).toHaveValue('机器学习课程实验')
  await page.getByRole('button', { name: '核对申请' }).click()
  await page.getByRole('button', { name: '确认并提交' }).click()
  await expect(page).toHaveURL(/\/reservations\/9001$/)
  await expect(page.getByRole('heading', { name: '机器学习课程实验' })).toBeVisible()
  await page.getByRole('button', { name: '取消预约' }).click()
  await page.locator('.el-message-box input').fill('计划变更')
  await page.locator('.el-message-box').getByRole('button', { name: '确认取消' }).click()
  await expect(page.locator('.page-heading .el-tag')).toContainText('已取消')
  expect(api.getCreateAttempts()).toBe(2)
  expect(consoleErrors.filter((message) => !message.includes('status of 409'))).toEqual([])
})

test('教师身份固定显示且核心页面在当前视口无横向溢出', async ({ page }) => {
  await installApi(page)
  await login(page, 'teacher01')
  await expect(page.getByText('教师预约身份')).toBeVisible()
  await navigate(page, '实验室与空闲')
  await expect(page.getByRole('heading', { name: '实验室与空闲课次' })).toBeVisible()
  await page.getByRole('button', { name: '查看详情' }).click()
  await expect(page.getByRole('heading', { name: '未来 7 天预约日历' })).toBeVisible()
  if ((page.viewportSize()?.width ?? 1280) <= 760) await expect(page.locator('.calendar-panel .el-table')).toBeVisible()
  else await expect(page.locator('.calendar-panel .week-calendar')).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
})

test('教师按实验室策略自动审批且身份不能手动切换', async ({ page }) => {
  await installApi(page, { conflictOnFirst: false })
  await login(page, 'teacher01')
  await navigate(page, '创建预约')
  await expect(page.getByText('教师预约')).toBeVisible()
  await expect(page.getByRole('radio', { name: /学生/ })).toHaveCount(0)
  await page.getByRole('button', { name: '查询可用实验室' }).click()
  await page.locator('button.selection-card').click()
  await page.getByRole('button', { name: '下一步' }).click()
  const inputs = page.locator('.wizard-panel input')
  await inputs.nth(0).fill('教师科研实验')
  await inputs.nth(1).fill('科研项目')
  await inputs.nth(2).fill('13800000002')
  await page.locator('.wizard-panel textarea').nth(0).fill('科研数据处理')
  await page.getByRole('button', { name: '核对申请' }).click()
  await page.getByRole('button', { name: '确认并提交' }).click()
  await expect(page.locator('.page-heading .el-tag')).toContainText('已通过')
})

test('纯系统管理员不能进入创建预约页面', async ({ page }) => {
  await installApi(page)
  await login(page, 'sysadmin01')
  await expect(page.locator('.sidebar').getByRole('link', { name: '创建预约' })).toHaveCount(0)
  await page.goto('/reservations/new')
  await expect(page.getByText('当前账号没有访问此功能的权限。')).toBeVisible()
})

test('实验室管理员完成审批、停用课次配置和统计导出', async ({ page }) => {
  await installApi(page)
  await login(page, 'labadmin01')
  await navigate(page, '审批与现场')
  await page.getByRole('button', { name: '通过', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认通过' }).click()
  await expect(page.locator('.el-table__body .el-tag')).toContainText('已通过')
  await page.getByRole('button', { name: '代签到' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认操作' }).click()
  await expect(page.locator('.el-table__body .el-tag')).toContainText('使用中')
  await page.getByRole('button', { name: '代签退' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认操作' }).click()
  await expect(page.locator('.el-table__body .el-tag')).toContainText('已完成')

  await navigate(page, '实验室管理')
  await page.getByRole('button', { name: '停用课次' }).click()
  const dialog = page.locator('.el-dialog')
  const textInputs = dialog.locator('input:not([type="hidden"])')
  await textInputs.nth(0).fill('2026-09-11')
  await textInputs.nth(2).fill('考试安排')
  await dialog.getByRole('button', { name: '添加' }).click()
  await expect(dialog.getByText('考试安排')).toBeVisible()

  await page.keyboard.press('Escape')
  await navigate(page, '统计分析')
  await expect(page.getByRole('heading', { name: '预约统计' })).toBeVisible()
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出当前筛选 CSV' }).click()])
  expect(download.suggestedFilename()).toContain('reservation-report')
})

test('管理员驳回必须填写原因', async ({ page }) => {
  await installApi(page)
  await login(page, 'labadmin01')
  await navigate(page, '审批与现场')
  await page.getByRole('button', { name: '驳回', exact: true }).click()
  const prompt = page.locator('.el-message-box')
  await prompt.locator('input').fill('材料不完整')
  await prompt.getByRole('button', { name: '确认驳回' }).click()
  await expect(page.locator('.el-table__body .el-tag')).toContainText('已驳回')
})

test('爽约状态可见且普通用户读取他人预约被拒绝', async ({ page }) => {
  await installApi(page, { initialStatus: 'NO_SHOW' })
  await login(page, 'student01')
  await navigate(page, '我的预约')
  await expect(page.locator('.el-table__body .el-tag')).toContainText('已爽约')
  await page.goto('/reservations/9002')
  await expect(page.getByText('无权查看他人预约')).toBeVisible()
})
