<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

interface NavigationItem { label: string; path: string; permission?: string; role?: string; businessIdentity?: boolean }

const auth = useAuthStore()
const notifications = useNotificationStore()
const route = useRoute()
const router = useRouter()
const drawerOpen = ref(false)

const navigation: NavigationItem[] = [
  { label: '首页概览', path: '/dashboard' },
  { label: '实验室与空闲', path: '/labs', permission: 'lab:read' },
  { label: '创建预约', path: '/reservations/new', permission: 'reservation:create', businessIdentity: true },
  { label: '我的预约', path: '/reservations/my', permission: 'reservation:read:self' },
  { label: '审批与现场', path: '/approvals', permission: 'reservation:read:managed' },
  { label: '实验室管理', path: '/manage/labs', permission: 'lab:write' },
  { label: '设备管理', path: '/manage/equipment', permission: 'equipment:write' },
  { label: '用户与配置', path: '/manage/users', role: 'SYSTEM_ADMIN' },
  { label: '统计分析', path: '/statistics', permission: 'statistics:read' },
  { label: '审计日志', path: '/audit', permission: 'audit:read' },
]

const visibleNavigation = computed(() => navigation.filter((item) =>
  (item.permission === undefined || auth.hasPermission(item.permission))
  && (item.role === undefined || auth.hasRole(item.role))
  && (item.businessIdentity !== true || auth.applicantType !== null)))

const roleLabels: Record<string, string> = {
  STUDENT: '学生', TEACHER: '教师', LAB_ADMIN: '实验室管理员', SYSTEM_ADMIN: '系统管理员',
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => { void notifications.load().catch(() => undefined) })
</script>

<template>
  <div class="app-frame">
    <aside
      class="sidebar"
      aria-label="主导航"
    >
      <RouterLink
        class="brand"
        to="/dashboard"
      >
        <span
          class="brand-mark"
          aria-hidden="true"
        >LAB</span>
        <span><strong>实验室预约</strong><small>计算机学院</small></span>
      </RouterLink>
      <nav class="navigation">
        <RouterLink
          v-for="item in visibleNavigation"
          :key="item.path"
          :to="item.path"
          :class="{ active: route.path === item.path || (item.path !== '/dashboard' && route.path.startsWith(`${item.path}/`)) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <span>{{ auth.user?.realName }}</span>
        <small>{{ auth.user?.roles.map((role) => roleLabels[role] ?? role).join(' · ') }}</small>
      </div>
    </aside>

    <div class="app-main">
      <header class="topbar">
        <el-button
          class="mobile-menu"
          plain
          aria-label="打开导航菜单"
          @click="drawerOpen = true"
        >
          菜单
        </el-button>
        <div class="topbar-title">
          <span>{{ String(route.meta.title ?? '实验室预约') }}</span>
          <small>{{ auth.user?.department ?? '计算机学院' }}</small>
        </div>
        <div class="topbar-actions">
          <RouterLink
            class="notification-link"
            to="/notifications"
          >
            通知
            <span
              v-if="notifications.unreadCount > 0"
              class="count-badge"
            >{{ notifications.unreadCount }}</span>
          </RouterLink>
          <el-button
            text
            @click="logout"
          >
            退出
          </el-button>
        </div>
      </header>
      <main class="main-content">
        <RouterView />
      </main>
    </div>

    <el-drawer
      v-model="drawerOpen"
      title="功能导航"
      direction="ltr"
      size="280px"
    >
      <nav class="mobile-navigation">
        <RouterLink
          v-for="item in visibleNavigation"
          :key="item.path"
          :to="item.path"
          @click="drawerOpen = false"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </el-drawer>
  </div>
</template>
