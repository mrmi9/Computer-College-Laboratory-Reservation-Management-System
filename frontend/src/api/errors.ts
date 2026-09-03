import { AxiosError } from 'axios'
import type { ApiErrorBody } from '@/types/api'

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof AxiosError) {
    const body = error.response?.data as ApiErrorBody | undefined
    if (body?.message !== undefined && body.message.length > 0) return body.message
  }
  return fallback
}
