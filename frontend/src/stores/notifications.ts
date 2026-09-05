import { defineStore } from 'pinia'
import { getNotifications, readAllNotifications, readNotification } from '@/api/notifications'
import type { NotificationItem } from '@/types/business'

interface NotificationState {
  items: NotificationItem[]
  loading: boolean
}

export const useNotificationStore = defineStore('notifications', {
  state: (): NotificationState => ({ items: [], loading: false }),
  getters: {
    unreadCount: (state): number => state.items.filter((item) => item.readAt === undefined).length,
  },
  actions: {
    async load(): Promise<void> {
      this.loading = true
      try {
        this.items = (await getNotifications()).items
      } finally {
        this.loading = false
      }
    },
    async markRead(id: number): Promise<void> {
      await readNotification(id)
      const item = this.items.find((candidate) => candidate.id === id)
      if (item !== undefined) item.readAt = new Date().toISOString()
    },
    async markAllRead(): Promise<void> {
      await readAllNotifications()
      const now = new Date().toISOString()
      this.items.forEach((item) => { item.readAt = item.readAt ?? now })
    },
  },
})
