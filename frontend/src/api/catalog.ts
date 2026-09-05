import { http } from '@/api/http'
import type { ApiEnvelope, PageData } from '@/types/api'
import type { AvailableLab, Blackout, CalendarSlot, Equipment, Lab, OpenRule, Period } from '@/types/business'

export interface LabMutation {
  code: string
  name: string
  building: string
  roomNo: string
  capacity: number
  labType: string
  description: string
  imageUrl: string
  tags: string[]
  status: Lab['status']
  studentApprovalMode: 'AUTO' | 'MANUAL'
  teacherApprovalMode: 'AUTO' | 'MANUAL'
  allowStudentBooking: boolean
  maxPeriodsPerUserDay: number
  advanceDays: number
  cancelBeforeMinutes: number
  requireCheckIn: boolean
  responsibleUserId: number | null
  version: number
}

export async function getPeriods(): Promise<Period[]> {
  return (await http.get<ApiEnvelope<Period[]>>('/course-periods')).data.data
}

export async function getLabs(filters: { keyword?: string; status?: string; page?: number; size?: number } = {}): Promise<PageData<Lab>> {
  return (await http.get<ApiEnvelope<PageData<Lab>>>('/labs', { params: filters })).data.data
}

export async function getLab(id: number): Promise<Lab> {
  return (await http.get<ApiEnvelope<Lab>>(`/labs/${id}`)).data.data
}

export async function getLabCalendar(id: number, from: string, to: string): Promise<CalendarSlot[]> {
  return (await http.get<ApiEnvelope<CalendarSlot[]>>(`/labs/${id}/calendar`, { params: { from, to } })).data.data
}

export async function saveLab(id: number | null, request: LabMutation): Promise<Lab> {
  const response = id === null
    ? await http.post<ApiEnvelope<Lab>>('/labs', request)
    : await http.put<ApiEnvelope<Lab>>(`/labs/${id}`, request)
  return response.data.data
}

export async function getAvailability(filters: {
  bookingDate: string
  periodNo: number
  capacity: number
  equipmentIds?: number[]
}): Promise<AvailableLab[]> {
  return (await http.get<ApiEnvelope<AvailableLab[]>>('/availability/labs', { params: filters })).data.data
}

export async function getOpenRules(labId: number): Promise<OpenRule[]> {
  return (await http.get<ApiEnvelope<OpenRule[]>>(`/labs/${labId}/open-rules`)).data.data
}

export async function replaceOpenRules(labId: number, rules: Array<Omit<OpenRule, 'id' | 'labId'>>): Promise<OpenRule[]> {
  return (await http.put<ApiEnvelope<OpenRule[]>>(`/labs/${labId}/open-rules`, rules)).data.data
}

export async function getBlackouts(labId: number, from?: string, to?: string): Promise<Blackout[]> {
  return (await http.get<ApiEnvelope<Blackout[]>>(`/labs/${labId}/blackouts`, { params: { from, to } })).data.data
}

export async function createBlackout(labId: number, request: { bookingDate: string; periodNo: number; reason: string }): Promise<Blackout> {
  return (await http.post<ApiEnvelope<Blackout>>(`/labs/${labId}/blackouts`, request)).data.data
}

export async function deleteBlackout(labId: number, blackoutId: number): Promise<void> {
  await http.delete(`/labs/${labId}/blackouts/${blackoutId}`)
}

export async function getEquipment(filters: { labId?: number; status?: string; page?: number; size?: number } = {}): Promise<PageData<Equipment>> {
  return (await http.get<ApiEnvelope<PageData<Equipment>>>('/equipment', { params: filters })).data.data
}

export type EquipmentMutation = Omit<Equipment, 'id' | 'labName'>

export async function saveEquipment(id: number | null, request: EquipmentMutation): Promise<Equipment> {
  const response = id === null
    ? await http.post<ApiEnvelope<Equipment>>('/equipment', request)
    : await http.put<ApiEnvelope<Equipment>>(`/equipment/${id}`, request)
  return response.data.data
}
