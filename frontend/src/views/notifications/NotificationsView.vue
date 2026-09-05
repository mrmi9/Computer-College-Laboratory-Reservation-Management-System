<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import { useNotificationStore } from '@/stores/notifications'
import { formatDateTime } from '@/utils/format'

const store = useNotificationStore(); const error = ref(''); const unreadOnly = ref(false)
async function load(): Promise<void> { error.value = ''; try { await store.load() } catch (caught) { error.value = apiErrorMessage(caught, '通知加载失败。') } }
async function open(id: number, readAt: string | undefined): Promise<void> { if (readAt === undefined) await store.markRead(id) }
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          消息中心
        </p><h1>站内通知</h1><p>审批、取消、签到和爽约结果会在这里留存。</p>
      </div><el-button
        :disabled="store.unreadCount === 0"
        @click="store.markAllRead"
      >
        全部标为已读
      </el-button>
    </header>
    <section class="panel filter-panel">
      <el-checkbox v-model="unreadOnly">
        只看未读
      </el-checkbox>
    </section>
    <PageState
      :loading="store.loading"
      :error="error"
      :empty="store.items.length === 0"
      empty-text="暂无通知"
      @retry="load"
    >
      <div class="notification-list">
        <article
          v-for="item in store.items.filter((entry) => !unreadOnly || entry.readAt === undefined)"
          :key="item.id"
          class="notification-card"
          :class="{ unread: item.readAt === undefined }"
          tabindex="0"
          @click="open(item.id, item.readAt)"
          @keydown.enter="open(item.id, item.readAt)"
        >
          <div>
            <span
              v-if="item.readAt === undefined"
              class="unread-dot"
              aria-label="未读"
            /><strong>{{ item.title }}</strong>
          </div><p>{{ item.content }}</p><small>{{ formatDateTime(item.createdAt) }}</small><RouterLink
            v-if="item.relatedType === 'RESERVATION' && item.relatedId"
            :to="`/reservations/${item.relatedId}`"
          >
            查看相关预约
          </RouterLink>
        </article>
      </div>
    </PageState>
  </section>
</template>
