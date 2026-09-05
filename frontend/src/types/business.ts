export type ReservationStatus =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'IN_USE'
  | 'COMPLETED'
  | 'NO_SHOW'

export interface Period {
  periodNo: number
  name: string
  startTime: string
  endTime: string
  enabled: boolean
  version: number
}

export interface Lab {
  id: number
  code: string
  name: string
  building: string
  roomNo: string
  capacity: number
  labType: string
  description?: string
  imageUrl?: string
  tags: string[]
  status: 'ACTIVE' | 'DISABLED' | 'MAINTENANCE'
  studentApprovalMode: 'AUTO' | 'MANUAL'
  teacherApprovalMode: 'AUTO' | 'MANUAL'
  allowStudentBooking: boolean
  maxPeriodsPerUserDay: number
  advanceDays: number
  cancelBeforeMinutes: number
  requireCheckIn: boolean
  responsibleUserId?: number
  responsibleUserName?: string
  version: number
}

export interface CalendarSlot {
  bookingDate: string
  periodNo: number
  periodName: string
  startTime: string
  endTime: string
  slotStatus: 'AVAILABLE' | 'RESERVED' | 'BLACKOUT' | 'CLOSED'
  reason?: string
}

export type AvailableLab = Pick<Lab, 'id' | 'code' | 'name' | 'building' | 'roomNo' | 'capacity' | 'labType' | 'tags'>

export interface OpenRule {
  id: number
  labId: number
  dayOfWeek: number
  periodNo: number
  validFrom?: string
  validTo?: string
}

export interface Blackout {
  id: number
  labId: number
  bookingDate: string
  periodNo: number
  reason: string
  createdBy: number
  createdByName: string
  createdAt: string
}

export interface Equipment {
  id: number
  labId: number
  labName: string
  assetCode: string
  category: string
  name: string
  model?: string
  totalQuantity: number
  requiredQualification?: string
  status: 'AVAILABLE' | 'MAINTENANCE' | 'RETIRED'
  version: number
}

export interface ReservationEquipment {
  equipmentId: number
  assetCode: string
  name: string
  quantity: number
}

export interface Reservation {
  id: number
  reservationNo: string
  applicantId: number
  applicantName: string
  applicantType: 'STUDENT' | 'TEACHER'
  labId: number
  labName: string
  building: string
  roomNo: string
  title: string
  purpose: string
  participantCount: number
  bookingDate: string
  periodNo: number
  periodName: string
  startTime: string
  endTime: string
  status: ReservationStatus
  contactPhone: string
  projectOrCourse: string
  remark?: string
  cancellationReason?: string
  version: number
  createdAt: string
  updatedAt: string
  nextAction: string
  equipmentItems: ReservationEquipment[]
}

export interface HistoryItem {
  id: number
  fromStatus?: string
  toStatus: string
  reason?: string
  operatorId?: number
  operatorName?: string
  source: string
  createdAt: string
}

export interface NotificationItem {
  id: number
  type: string
  title: string
  content: string
  relatedType?: string
  relatedId?: number
  readAt?: string
  createdAt: string
}

export interface UserAdmin {
  id: number
  username: string
  realName: string
  userType: 'STUDENT' | 'TEACHER' | 'STAFF'
  department?: string
  email?: string
  phone?: string
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  mustChangePassword: boolean
  bookingFrozenUntil?: string
  noShowCount: number
  version: number
  roles: string[]
  createdAt: string
}

export interface RoleAdmin {
  id: number
  code: string
  name: string
  description?: string
  builtIn: boolean
  version: number
  permissions: string[]
}

export interface Permission {
  id: number
  code: string
  name: string
}

export interface Setting {
  key: string
  value: string | number | boolean
  description?: string
  version: number
  updatedBy?: number
  updatedAt: string
}

export interface AuditLog {
  id: number
  actorId?: number
  actorUsername?: string
  action: string
  targetType?: string
  targetId?: string
  requestId: string
  result: 'SUCCESS' | 'FAILURE'
  detail: Record<string, unknown>
  ipAddress?: string
  createdAt: string
}

export interface StatisticsFilter {
  from: string
  to: string
  labId?: number
  applicantType?: 'STUDENT' | 'TEACHER'
  status?: ReservationStatus
}

export interface Overview {
  from: string
  to: string
  total: number
  effective: number
  cancelled: number
  noShow: number
  approvalRate: number
  cancellationRate: number
  noShowRate: number
  student: { total: number; effective: number }
  teacher: { total: number; effective: number }
}

export interface LabUsage {
  labId: number
  labCode: string
  labName: string
  availableSlots: number
  occupiedSlots: number
  utilizationRate: number
}

export interface PeakHour {
  periodNo: number
  periodName: string
  startTime: string
  endTime: string
  total: number
  effective: number
}

export interface EquipmentUsage {
  equipmentId: number
  assetCode: string
  name: string
  labId: number
  labCode: string
  quantity: number
}
