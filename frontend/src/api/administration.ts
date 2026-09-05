import { http } from '@/api/http'
import type { ApiEnvelope, PageData } from '@/types/api'
import type { AuditLog, Permission, RoleAdmin, Setting, UserAdmin } from '@/types/business'

export async function getUsers(filters: { keyword?: string; status?: string; page?: number; size?: number } = {}): Promise<PageData<UserAdmin>> {
  return (await http.get<ApiEnvelope<PageData<UserAdmin>>>('/admin/users', { params: filters })).data.data
}

export async function createUser(request: {
  username: string
  realName: string
  userType: string
  department: string
  email: string
  phone: string
  status: string
  roles: string[]
  initialPassword: string
}): Promise<UserAdmin> {
  return (await http.post<ApiEnvelope<UserAdmin>>('/admin/users', request)).data.data
}

export async function updateUser(id: number, request: {
  username: string
  realName: string
  userType: string
  department: string
  email: string
  phone: string
  status: string
  version: number
}): Promise<UserAdmin> {
  return (await http.put<ApiEnvelope<UserAdmin>>(`/admin/users/${id}`, request)).data.data
}

export async function replaceUserRoles(id: number, roles: string[], version: number): Promise<UserAdmin> {
  return (await http.put<ApiEnvelope<UserAdmin>>(`/admin/users/${id}/roles`, { roles, version })).data.data
}

export async function getRoles(): Promise<RoleAdmin[]> {
  return (await http.get<ApiEnvelope<RoleAdmin[]>>('/admin/roles')).data.data
}

export async function getPermissions(): Promise<Permission[]> {
  return (await http.get<ApiEnvelope<Permission[]>>('/admin/permissions')).data.data
}

export async function createRole(request: { code: string; name: string; description: string; permissions: string[] }): Promise<RoleAdmin> {
  return (await http.post<ApiEnvelope<RoleAdmin>>('/admin/roles', request)).data.data
}

export async function updateRole(id: number, request: { name: string; description: string; permissions: string[]; version: number }): Promise<RoleAdmin> {
  return (await http.put<ApiEnvelope<RoleAdmin>>(`/admin/roles/${id}`, request)).data.data
}

export async function importUsers(file: File): Promise<{ id: string; status: string; totalRows: number; succeededRows: number; failedRows: number; errorSummary?: string }> {
  const form = new FormData()
  form.append('file', file)
  return (await http.post<ApiEnvelope<{ id: string; status: string; totalRows: number; succeededRows: number; failedRows: number; errorSummary?: string }>>('/admin/users/import', form)).data.data
}

export async function getSettings(): Promise<Setting[]> {
  return (await http.get<ApiEnvelope<Setting[]>>('/admin/settings')).data.data
}

export async function updateSetting(key: string, value: string | number | boolean, description: string | undefined, version: number): Promise<Setting> {
  return (await http.put<ApiEnvelope<Setting>>(`/admin/settings/${encodeURIComponent(key)}`, { value, description, version })).data.data
}

export async function getAuditLogs(filters: { action?: string; actorUsername?: string; page?: number; size?: number } = {}): Promise<PageData<AuditLog>> {
  return (await http.get<ApiEnvelope<PageData<AuditLog>>>('/admin/audit-logs', { params: filters })).data.data
}
