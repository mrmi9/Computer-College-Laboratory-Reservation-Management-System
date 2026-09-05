<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getMyReservations } from '@/api/reservations'
import { getOverview } from '@/api/statistics'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import type { Overview, Reservation } from '@/types/business'
import { apiErrorMessage } from '@/api/errors'
import { todayIso } from '@/utils/format'

const auth = useAuthStore()
const notifications = useNotificationStore()
const reservations = ref<Reservation[]>([])
const overview = ref<Overview | null>(null)
const loading = ref(true)
const error = ref('')

const identityLabel = computed(() => auth.applicantType === 'TEACHER' ? '教师预约身份' : auth.applicantType === 'STUDENT' ? '学生预约身份' : '管理账号')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const tasks: Promise<void>[] = [notifications.load()]
    if (auth.hasPermission('reservation:read:self')) {
      tasks.push(getMyReservations({ size: 5 }).then((data) => { reservations.value = data.items }))
    }
    if (auth.hasPermission('statistics:read')) {
      tasks.push(getOverview({ from: todayIso(-30), to: todayIso(30) }).then((data) => { overview.value = data }))
    }
    await Promise.all(tasks)
  } catch (caught) {
    error.value = apiErrorMessage(caught, '首页数据暂时无法加载。')
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          {{ identityLabel }}
        </p><h1>你好，{{ auth.user?.realName }}</h1><p>查看近期安排与需要处理的事项。</p>
      </div>
      <RouterLink
        v-if="auth.hasPermission('reservation:create') && auth.applicantType !== null"
        to="/reservations/new"
      >
        <el-button type="primary">
          创建预约
        </el-button>
      </RouterLink>
    </header>
    <PageState
      :loading="loading"
      :error="error"
      @retry="load"
    >
      <div class="metric-grid">
        <article class="metric-card">
          <span>近期预约</span><strong>{{ reservations.length }}</strong><small>最近 5 条</small>
        </article>
        <article class="metric-card">
          <span>未读通知</span><strong>{{ notifications.unreadCount }}</strong><RouterLink to="/notifications">
            查看通知
          </RouterLink>
        </article>
        <article
          v-if="overview"
          class="metric-card"
        >
          <span>管辖预约</span><strong>{{ overview.total }}</strong><small>前后 30 天</small>
        </article>
        <article
          v-if="overview"
          class="metric-card"
        >
          <span>有效率</span><strong>{{ overview.approvalRate }}%</strong><small>{{ overview.effective }} 条有效</small>
        </article>
      </div>
      <section class="panel">
        <div class="section-heading">
          <div><h2>近期预约</h2><p>状态变化以后端记录为准</p></div><RouterLink
            v-if="auth.hasPermission('reservation:read:self')"
            to="/reservations/my"
          >
            查看全部
          </RouterLink>
        </div>
        <el-empty
          v-if="reservations.length === 0"
          description="暂无近期预约"
        />
        <div
          v-else
          class="compact-list"
        >
          <RouterLink
            v-for="item in reservations"
            :key="item.id"
            :to="`/reservations/${item.id}`"
            class="compact-row"
          >
            <span><strong>{{ item.labName }}</strong><small>{{ item.bookingDate }} · {{ item.periodName }}（{{ item.startTime }}–{{ item.endTime }}）</small></span>
            <StatusTag :status="item.status" />
          </RouterLink>
        </div>
      </section>
    </PageState>
  </section>
</template>
