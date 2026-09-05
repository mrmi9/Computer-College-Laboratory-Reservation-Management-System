import crypto from 'k6/crypto'
import encoding from 'k6/encoding'
import http from 'k6/http'
import { check } from 'k6'
import { Rate, Trend } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://frontend:8080/api'
const jwtSecret = __ENV.JWT_SECRET
const availabilityDuration = new Trend('availability_duration', true)
const reservationDuration = new Trend('reservation_duration', true)
const statisticsDuration = new Trend('statistics_duration', true)
const businessErrors = new Rate('business_errors')

export const options = {
  scenarios: {
    authenticatedReservations: {
      executor: 'per-vu-iterations',
      vus: 300,
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds: {
    availability_duration: ['p(95)<500'],
    reservation_duration: ['p(95)<1000'],
    statistics_duration: ['p(95)<500'],
    business_errors: ['rate<0.01'],
  },
}
function base64UrlJson(value) {
  return encoding.b64encode(JSON.stringify(value), 'rawurl')
}

function issueToken(userId, username, roles, permissions) {
  if (!jwtSecret || jwtSecret.length < 32) {
    throw new Error('JWT_SECRET must contain at least 32 characters')
  }
  const now = Math.floor(Date.now() / 1000)
  const header = base64UrlJson({ alg: 'HS256', typ: 'JWT' })
  const payload = base64UrlJson({
    iss: 'lab-booking-api',
    sub: username,
    iat: now,
    exp: now + 900,
    jti: `k6-${userId}-${__VU}`,
    uid: userId,
    session_version: 0,
    must_change_password: false,
    roles,
    permissions,
  })
  const signingInput = `${header}.${payload}`
  const signature = crypto.hmac('sha256', jwtSecret, signingInput, 'base64rawurl')
  return `${signingInput}.${signature}`
}

function isoDateDaysAhead(days) {
  const value = new Date(Date.now() + days * 24 * 60 * 60 * 1000)
  return value.toISOString().slice(0, 10)
}

export default function () {
  const sequence = __VU
  const userId = 20000 + sequence
  const username = `perf${String(sequence).padStart(5, '0')}`
  const token = issueToken(userId, username, ['STUDENT'], [
    'lab:read',
    'equipment:read',
    'reservation:create',
    'reservation:read:self',
  ])
  const bookingDate = isoDateDaysAhead(3)
  const labId = 10001 + Math.floor((sequence - 1) / 4)
  const periodNo = ((sequence - 1) % 4) + 1
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'X-Idempotency-Key': `k6-create-${sequence}-${bookingDate}`,
  }

  const availability = http.get(
    `${baseUrl}/v1/availability/labs?bookingDate=${bookingDate}&periodNo=${periodNo}&capacity=1`,
    { headers, tags: { operation: 'availability' } },
  )
  availabilityDuration.add(availability.timings.duration)
  const availabilityOk = check(availability, {
    'availability returns 200': (response) => response.status === 200,
    'selected lab is available': (response) => {
      if (response.status !== 200) return false
      return response.json('data').some((lab) => lab.id === labId)
    },
  })
  businessErrors.add(!availabilityOk)

  const reservation = http.post(
    `${baseUrl}/v1/reservations`,
    JSON.stringify({
      labId,
      title: `k6 并发预约 ${sequence}`,
      purpose: '第一阶段性能验收',
      participantCount: 1,
      bookingDate,
      periodNo,
      projectOrCourse: '性能测试',
      contactPhone: `139${String(sequence).padStart(8, '0')}`,
      equipmentItems: [],
      remark: 'k6 generated',
    }),
    { headers, tags: { operation: 'reservation-create' } },
  )
  reservationDuration.add(reservation.timings.duration)
  const reservationOk = check(reservation, {
    'reservation returns 200': (response) => response.status === 200,
    'reservation is auto approved': (response) => response.json('data.status') === 'APPROVED',
  })
  businessErrors.add(!reservationOk)

  const adminToken = issueToken(1004, 'sysadmin01', ['SYSTEM_ADMIN'], ['statistics:read'])
  const statistics = http.get(
    `${baseUrl}/v1/statistics/overview?from=${bookingDate}&to=${bookingDate}`,
    { headers: { Authorization: `Bearer ${adminToken}` }, tags: { operation: 'statistics-overview' } },
  )
  statisticsDuration.add(statistics.timings.duration)
  const statisticsOk = check(statistics, {
    'statistics returns 200': (response) => response.status === 200,
  })
  businessErrors.add(!statisticsOk)
}
