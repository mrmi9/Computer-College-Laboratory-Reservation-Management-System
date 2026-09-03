import { defineStore } from 'pinia'
import { clearSession, http, refreshSession, setSession } from '@/api/http'
import type { ApiEnvelope, SessionPayload, SessionUser } from '@/types/api'

interface AuthState {
  user: SessionUser | null
  initialized: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({ user: null, initialized: false }),
  getters: {
    authenticated: (state): boolean => state.user !== null,
    mustChangePassword: (state): boolean => state.user?.mustChangePassword ?? false,
  },
  actions: {
    async login(username: string, password: string): Promise<SessionUser> {
      const response = await http.post<ApiEnvelope<SessionPayload>>('/auth/login', { username, password })
      setSession(response.data.data)
      this.user = response.data.data.user
      this.initialized = true
      return response.data.data.user
    },
    async bootstrap(): Promise<void> {
      if (this.initialized) return
      try {
        const session = await refreshSession()
        this.user = session.user
      } catch {
        clearSession()
        this.user = null
      } finally {
        this.initialized = true
      }
    },
    async logout(): Promise<void> {
      try {
        await http.post('/auth/logout')
      } finally {
        this.clear()
      }
    },
    async changePassword(currentPassword: string, newPassword: string): Promise<void> {
      await http.put('/auth/password', { currentPassword, newPassword })
      this.clear()
    },
    clear(): void {
      clearSession()
      this.user = null
      this.initialized = true
    },
  },
})

