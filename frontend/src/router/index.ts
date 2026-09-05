import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/auth/LoginView.vue'
import ChangePasswordView from '@/views/auth/ChangePasswordView.vue'
import AppShell from '@/layouts/AppShell.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/change-password', component: ChangePasswordView, meta: { title: '修改初始密码' } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('@/views/dashboard/DashboardView.vue'), meta: { title: '首页概览' } },
        { path: 'labs', component: () => import('@/views/labs/LabsView.vue'), meta: { title: '实验室与空闲', permissions: ['lab:read'] } },
        { path: 'labs/:id', component: () => import('@/views/labs/LabDetailView.vue'), meta: { title: '实验室详情', permissions: ['lab:read'] } },
        { path: 'reservations/new', component: () => import('@/views/reservations/ReservationWizardView.vue'), meta: { title: '创建预约', permissions: ['reservation:create'], businessIdentity: true } },
        { path: 'reservations/my', component: () => import('@/views/reservations/MyReservationsView.vue'), meta: { title: '我的预约', permissions: ['reservation:read:self'] } },
        { path: 'reservations/:id', component: () => import('@/views/reservations/ReservationDetailView.vue'), meta: { title: '预约详情' } },
        { path: 'approvals', component: () => import('@/views/approvals/ApprovalsView.vue'), meta: { title: '审批与现场', permissions: ['reservation:read:managed'] } },
        { path: 'manage/labs', component: () => import('@/views/management/LabManagementView.vue'), meta: { title: '实验室管理', permissions: ['lab:write'] } },
        { path: 'manage/equipment', component: () => import('@/views/management/EquipmentManagementView.vue'), meta: { title: '设备管理', permissions: ['equipment:write'] } },
        { path: 'manage/users', component: () => import('@/views/management/UserManagementView.vue'), meta: { title: '用户与系统配置', roles: ['SYSTEM_ADMIN'] } },
        { path: 'statistics', component: () => import('@/views/statistics/StatisticsView.vue'), meta: { title: '统计分析', permissions: ['statistics:read'] } },
        { path: 'audit', component: () => import('@/views/audit/AuditView.vue'), meta: { title: '审计日志', permissions: ['audit:read'] } },
        { path: 'notifications', component: () => import('@/views/notifications/NotificationsView.vue'), meta: { title: '站内通知' } },
        { path: 'forbidden', component: () => import('@/views/system/ForbiddenView.vue'), meta: { title: '无权访问' } },
        { path: ':pathMatch(.*)*', component: () => import('@/views/system/NotFoundView.vue'), meta: { title: '页面不存在' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public === true) {
    if (auth.authenticated) return auth.mustChangePassword ? '/change-password' : '/dashboard'
    return true
  }
  await auth.bootstrap()
  if (!auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (auth.mustChangePassword && to.path !== '/change-password') return '/change-password'
  if (!auth.mustChangePassword && to.path === '/change-password') return '/dashboard'
  const permissions = Array.isArray(to.meta.permissions) ? to.meta.permissions as string[] : []
  const roles = Array.isArray(to.meta.roles) ? to.meta.roles as string[] : []
  if (permissions.some((permission) => !auth.hasPermission(permission))) return '/forbidden'
  if (roles.length > 0 && !roles.some((role) => auth.hasRole(role))) return '/forbidden'
  if (to.meta.businessIdentity === true && auth.applicantType === null) return '/forbidden'
  return true
})

export default router
