import { http } from '@/api/http'
import type { ApiEnvelope, PageData } from '@/types/api'
import type { NotificationItem } from '@/types/business'

export async function getNotifications(unreadOnly = false): Promise<PageData<NotificationItem>> {
  return (await http.get<ApiEnvelope<PageData<NotificationItem>>>('/notifications', {
    params: { unreadOnly, page: 0, size: 100 },
  })).data.data
}

export async function readNotification(id: number): Promise<void> {
  await http.put(`/notifications/${id}/read`)
}

export async function readAllNotifications(): Promise<void> {
  await http.put('/notifications/read-all')
}
