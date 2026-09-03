import axios, {
  AxiosError,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { ApiEnvelope, SessionPayload } from '@/types/api'

type RetryRequest = InternalAxiosRequestConfig & { _retriedAfterRefresh?: boolean }

let accessToken: string | null = null
let csrfToken: string | null = null
let authExpiredHandler: () => void = () => undefined

export class SingleFlight<T> {
  private pending: Promise<T> | null = null

  run(task: () => Promise<T>): Promise<T> {
    if (this.pending === null) {
      this.pending = task().finally(() => {
        this.pending = null
      })
    }
    return this.pending
  }
}

const refreshFlight = new SingleFlight<SessionPayload>()
const refreshClient = axios.create({ baseURL: '/api/v1', withCredentials: true })

export const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 15_000,
})

http.interceptors.request.use((config) => {
  if (accessToken !== null) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  config.headers['X-Request-Id'] = crypto.randomUUID()
  config.headers['X-Timezone'] = Intl.DateTimeFormat().resolvedOptions().timeZone
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const request = error.config as RetryRequest | undefined
    const isAuthEndpoint = request?.url?.startsWith('/auth/') ?? false
    if (error.response?.status !== 401 || request === undefined || request._retriedAfterRefresh || isAuthEndpoint) {
      return Promise.reject(error)
    }
    request._retriedAfterRefresh = true
    try {
      await refreshSession()
      request.headers.Authorization = `Bearer ${accessToken}`
      return await http(request)
    } catch (refreshError) {
      clearSession()
      authExpiredHandler()
      return Promise.reject(refreshError)
    }
  },
)

export function setSession(session: SessionPayload): void {
  accessToken = session.accessToken
  csrfToken = session.csrfToken
}

export function clearSession(): void {
  accessToken = null
  csrfToken = null
}

export function setAuthExpiredHandler(handler: () => void): void {
  authExpiredHandler = handler
}

export async function refreshSession(): Promise<SessionPayload> {
  return refreshFlight.run(async () => {
    const token = csrfToken ?? readCookie('lab_csrf')
    if (token === null) {
      throw new Error('No refresh CSRF token is available')
    }
    const response = await refreshClient.post<ApiEnvelope<SessionPayload>>(
      '/auth/refresh',
      {},
      { headers: { 'X-CSRF-TOKEN': token, 'X-Request-Id': crypto.randomUUID() } },
    )
    setSession(response.data.data)
    return response.data.data
  })
}

function readCookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`
  const item = document.cookie.split('; ').find((value) => value.startsWith(prefix))
  return item === undefined ? null : decodeURIComponent(item.slice(prefix.length))
}

