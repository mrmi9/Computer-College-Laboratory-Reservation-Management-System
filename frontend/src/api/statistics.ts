import { http } from '@/api/http'
import type { ApiEnvelope } from '@/types/api'
import type { EquipmentUsage, LabUsage, Overview, PeakHour, StatisticsFilter } from '@/types/business'

export async function getOverview(filter: StatisticsFilter): Promise<Overview> {
  return (await http.get<ApiEnvelope<Overview>>('/statistics/overview', { params: filter })).data.data
}

export async function getLabUsage(filter: StatisticsFilter): Promise<LabUsage[]> {
  return (await http.get<ApiEnvelope<LabUsage[]>>('/statistics/lab-usage', { params: filter })).data.data
}

export async function getPeakHours(filter: StatisticsFilter): Promise<PeakHour[]> {
  return (await http.get<ApiEnvelope<PeakHour[]>>('/statistics/peak-hours', { params: filter })).data.data
}

export async function getEquipmentRanking(filter: StatisticsFilter): Promise<EquipmentUsage[]> {
  return (await http.get<ApiEnvelope<EquipmentUsage[]>>('/statistics/equipment-ranking', { params: filter })).data.data
}

export async function exportStatistics(filter: StatisticsFilter): Promise<Blob> {
  return (await http.get<Blob>('/statistics/export.csv', { params: filter, responseType: 'blob' })).data
}
