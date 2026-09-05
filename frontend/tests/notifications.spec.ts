import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useNotificationStore } from '@/stores/notifications'

const api = vi.hoisted(() => ({
  getNotifications: vi.fn(),
  readAllNotifications: vi.fn(),
  readNotification: vi.fn(),
}))

vi.mock('@/api/notifications', () => api)

describe('useNotificationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads notifications and updates unread state after server success', async () => {
    api.getNotifications.mockResolvedValue({
      items: [
        { id: 1, type: 'APPROVED', title: '预约已通过', content: '请按时签到', createdAt: '2026-09-05T08:00:00Z' },
        { id: 2, type: 'SYSTEM', title: '系统通知', content: '维护完成', createdAt: '2026-09-05T09:00:00Z', readAt: '2026-09-05T09:01:00Z' },
      ],
      page: 0,
      size: 100,
      total: 2,
    })
    api.readNotification.mockResolvedValue(undefined)

    const store = useNotificationStore()
    await store.load()
    expect(store.loading).toBe(false)
    expect(store.unreadCount).toBe(1)

    await store.markRead(1)
    expect(api.readNotification).toHaveBeenCalledWith(1)
    expect(store.unreadCount).toBe(0)
  })

  it('marks every local item read only after the API succeeds', async () => {
    api.readAllNotifications.mockResolvedValue(undefined)
    const store = useNotificationStore()
    store.items = [{ id: 3, type: 'SYSTEM', title: '提示', content: '内容', createdAt: '2026-09-05T10:00:00Z' }]

    await store.markAllRead()
    expect(api.readAllNotifications).toHaveBeenCalledOnce()
    expect(store.unreadCount).toBe(0)
  })
})
