import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import type { SessionUser } from '@/types/api'

function user(roles: string[], permissions: string[] = []): SessionUser {
  return {
    id: 1,
    username: 'tester',
    realName: '测试用户',
    userType: 'STAFF',
    roles,
    permissions,
    mustChangePassword: false,
  }
}

describe('useAuthStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('derives an immutable business identity from roles', () => {
    const auth = useAuthStore()
    auth.$patch({ user: user(['SYSTEM_ADMIN']) })
    expect(auth.applicantType).toBeNull()

    auth.$patch({ user: user(['SYSTEM_ADMIN', 'STUDENT']) })
    expect(auth.applicantType).toBe('STUDENT')

    auth.$patch({ user: user(['STUDENT', 'TEACHER']) })
    expect(auth.applicantType).toBe('TEACHER')
  })

  it('checks permissions and roles from the session payload', () => {
    const auth = useAuthStore()
    auth.$patch({ user: user(['LAB_ADMIN'], ['reservation:approve']) })
    expect(auth.hasRole('LAB_ADMIN')).toBe(true)
    expect(auth.hasPermission('reservation:approve')).toBe(true)
    expect(auth.hasPermission('user:write')).toBe(false)
  })
})
