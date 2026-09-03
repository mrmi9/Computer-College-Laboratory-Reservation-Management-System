export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
  requestId: string
  timestamp: string
}

export interface ApiErrorBody {
  code: string
  message: string
  details?: Array<{ field: string; reason: string }>
  requestId: string
  timestamp: string
}

export interface SessionUser {
  id: number
  username: string
  realName: string
  userType: 'STUDENT' | 'TEACHER' | 'STAFF'
  department?: string
  email?: string
  phone?: string
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
}

export interface SessionPayload {
  accessToken: string
  expiresAt: string
  csrfToken: string
  user: SessionUser
}

