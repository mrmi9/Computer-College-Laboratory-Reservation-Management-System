import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/auth/LoginView.vue'
import ChangePasswordView from '@/views/auth/ChangePasswordView.vue'
import DashboardView from '@/views/dashboard/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/change-password', component: ChangePasswordView },
    { path: '/dashboard', component: DashboardView },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
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
  return true
})

export default router
