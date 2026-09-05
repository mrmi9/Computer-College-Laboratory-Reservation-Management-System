import { http } from '@/api/http'
import type { ApiEnvelope, PageData } from '@/types/api'
import type { HistoryItem, Reservation } from '@/types/business'

export interface ReservationDraft {
  labId: number
  title: string
  purpose: string
  participantCount: number
  bookingDate: string
  periodNo: number
  projectOrCourse: string
  contactPhone: string
  equipmentItems: Array<{ equipmentId: number; quantity: number }>
  remark: string
}

export async function createReservation(draft: ReservationDraft, idempotencyKey: string): Promise<Reservation> {
  return (await http.post<ApiEnvelope<Reservation>>('/reservations', draft, {
    headers: { 'X-Idempotency-Key': idempotencyKey },
  })).data.data
}

export async function getMyReservations(filters: { status?: string; page?: number; size?: number } = {}): Promise<PageData<Reservation>> {
  return (await http.get<ApiEnvelope<PageData<Reservation>>>('/reservations/my', { params: filters })).data.data
}

export async function getManagedReservations(filters: {
  labId?: number
  applicantType?: string
  status?: string
  page?: number
  size?: number
} = {}): Promise<PageData<Reservation>> {
  return (await http.get<ApiEnvelope<PageData<Reservation>>>('/admin/reservations', { params: filters })).data.data
}

export async function getReservation(id: number): Promise<Reservation> {
  return (await http.get<ApiEnvelope<Reservation>>(`/reservations/${id}`)).data.data
}

export async function cancelReservation(id: number, reason: string): Promise<Reservation> {
  return (await http.post<ApiEnvelope<Reservation>>(`/reservations/${id}/cancel`, { reason })).data.data
}

export async function decideReservation(
  id: number,
  action: 'approve' | 'reject' | 'cancel',
  request: { version: number; comment?: string; reason?: string },
): Promise<Reservation> {
  return (await http.post<ApiEnvelope<Reservation>>(`/admin/reservations/${id}/${action}`, request, {
    headers: { 'X-Idempotency-Key': crypto.randomUUID() },
  })).data.data
}

export async function getReservationHistory(id: number): Promise<HistoryItem[]> {
  return (await http.get<ApiEnvelope<HistoryItem[]>>(`/admin/reservations/${id}/history`)).data.data
}

export async function attendanceAction(
  id: number,
  action: 'check-in' | 'check-out',
  administrative: boolean,
  request: { actualParticipantCount?: number; note?: string },
): Promise<void> {
  const prefix = administrative ? '/admin' : ''
  await http.post(`${prefix}/reservations/${id}/${action}`, request)
}
