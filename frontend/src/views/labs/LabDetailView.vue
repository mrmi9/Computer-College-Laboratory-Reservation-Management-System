<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getBlackouts, getEquipment, getLab, getLabCalendar, getOpenRules } from '@/api/catalog'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { Blackout, CalendarSlot, Equipment, Lab, OpenRule } from '@/types/business'
import { addIsoDays, todayIso } from '@/utils/format'

const route = useRoute()
const auth = useAuthStore()
const lab = ref<Lab | null>(null)
const rules = ref<OpenRule[]>([])
const blackouts = ref<Blackout[]>([])
const equipment = ref<Equipment[]>([])
const calendar = ref<CalendarSlot[]>([])
const calendarFrom = ref(todayIso())
const calendarMode = ref<'calendar' | 'list'>(window.matchMedia('(max-width: 760px)').matches ? 'list' : 'calendar')
const loading = ref(true)
const error = ref('')
const labId = computed(() => Number(route.params.id))
const weekdays = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const slotLabels: Record<CalendarSlot['slotStatus'], string> = {
  AVAILABLE: '可预约', RESERVED: '已预约', BLACKOUT: '特殊停用', CLOSED: '未开放',
}
const calendarDays = computed(() => {
  const grouped = new Map<string, CalendarSlot[]>()
  calendar.value.forEach((slot) => grouped.set(slot.bookingDate, [...(grouped.get(slot.bookingDate) ?? []), slot]))
  return [...grouped.entries()].map(([date, slots]) => ({ date, slots }))
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [labData, ruleData, blackoutData, equipmentData, calendarData] = await Promise.all([
      getLab(labId.value), getOpenRules(labId.value), getBlackouts(labId.value), getEquipment({ labId: labId.value, size: 100 }),
      getLabCalendar(labId.value, calendarFrom.value, addIsoDays(calendarFrom.value, 6)),
    ])
    lab.value = labData
    rules.value = ruleData
    blackouts.value = blackoutData
    equipment.value = equipmentData.items
    calendar.value = calendarData
  } catch (caught) {
    error.value = apiErrorMessage(caught, '实验室详情加载失败。')
  } finally { loading.value = false }
}

async function refreshCalendar(): Promise<void> {
  try { calendar.value = await getLabCalendar(labId.value, calendarFrom.value, addIsoDays(calendarFrom.value, 6)) }
  catch (caught) { error.value = apiErrorMessage(caught, '预约日历加载失败。') }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <PageState
      :loading="loading"
      :error="error"
      :empty="lab === null"
      @retry="load"
    >
      <header
        v-if="lab"
        class="page-heading"
      >
        <div>
          <p class="eyebrow">
            {{ lab.code }}
          </p><h1>{{ lab.name }}</h1><p>{{ lab.building }} · {{ lab.roomNo }} · {{ lab.labType }}</p>
        </div>
        <div class="heading-actions">
          <StatusTag :status="lab.status" /><RouterLink
            v-if="auth.applicantType !== null"
            :to="{ path: '/reservations/new', query: { lab: lab.id } }"
          >
            <el-button type="primary">
              预约此实验室
            </el-button>
          </RouterLink>
        </div>
      </header>
      <div
        v-if="lab"
        class="detail-grid"
      >
        <section class="panel span-two calendar-panel">
          <div class="section-heading">
            <div>
              <h2>未来 7 天预约日历</h2><p class="muted">
                只展示槽位占用情况，不公开其他申请人的信息。
              </p>
            </div>
            <div class="calendar-controls">
              <el-date-picker
                v-model="calendarFrom"
                type="date"
                value-format="YYYY-MM-DD"
                :clearable="false"
                aria-label="日历开始日期"
                @change="refreshCalendar"
              />
              <el-segmented
                v-model="calendarMode"
                :options="[{ label: '周视图', value: 'calendar' }, { label: '列表视图', value: 'list' }]"
                aria-label="日历显示方式"
              />
            </div>
          </div>
          <div
            v-if="calendarMode === 'calendar'"
            class="week-calendar"
          >
            <article
              v-for="day in calendarDays"
              :key="day.date"
              class="calendar-day"
            >
              <strong>{{ day.date }}</strong>
              <div
                v-for="slot in day.slots"
                :key="slot.periodNo"
                class="calendar-slot"
                :class="`slot-${slot.slotStatus.toLowerCase()}`"
              >
                <span>{{ slot.periodName }}</span><small>{{ slotLabels[slot.slotStatus] }}<template v-if="slot.reason"> · {{ slot.reason }}</template></small>
              </div>
            </article>
          </div>
          <div
            v-else
            class="responsive-table"
          >
            <el-table :data="calendar">
              <el-table-column
                prop="bookingDate"
                label="日期"
                min-width="120"
              />
              <el-table-column
                label="课次"
                min-width="190"
              >
                <template #default="scope">
                  {{ scope.row.periodName }}（{{ scope.row.startTime }}–{{ scope.row.endTime }}）
                </template>
              </el-table-column>
              <el-table-column
                label="状态"
                min-width="120"
              >
                <template #default="scope">
                  <span
                    class="slot-label"
                    :class="`slot-${scope.row.slotStatus.toLowerCase()}`"
                  >{{ slotLabels[scope.row.slotStatus as CalendarSlot['slotStatus']] }}</span>
                </template>
              </el-table-column>
              <el-table-column
                prop="reason"
                label="说明"
                min-width="140"
              />
            </el-table>
          </div>
        </section>
        <section class="panel">
          <h2>资源信息</h2><dl class="description-grid">
            <div><dt>容量</dt><dd>{{ lab.capacity }} 人</dd></div><div><dt>签到</dt><dd>{{ lab.requireCheckIn ? '需要签到' : '无需签到' }}</dd></div><div><dt>学生审批</dt><dd>{{ lab.studentApprovalMode === 'AUTO' ? '自动审批' : '人工审批' }}</dd></div><div><dt>教师审批</dt><dd>{{ lab.teacherApprovalMode === 'AUTO' ? '自动审批' : '人工审批' }}</dd></div>
          </dl><p>{{ lab.description ?? '暂无说明' }}</p><div class="tag-list">
            <el-tag
              v-for="tag in lab.tags"
              :key="tag"
            >
              {{ tag }}
            </el-tag>
          </div>
        </section>
        <section class="panel">
          <h2>每周开放课次</h2><div class="compact-list">
            <div
              v-for="rule in rules"
              :key="rule.id"
              class="compact-row"
            >
              <span>{{ weekdays[rule.dayOfWeek] }} · 第 {{ rule.periodNo }} 大节</span><small>{{ rule.validFrom ?? '长期' }} 至 {{ rule.validTo ?? '长期' }}</small>
            </div>
          </div><el-empty
            v-if="rules.length === 0"
            description="暂未配置开放课次"
          />
        </section>
        <section class="panel">
          <h2>近期停用课次</h2><div class="compact-list">
            <div
              v-for="item in blackouts"
              :key="item.id"
              class="compact-row"
            >
              <span>{{ item.bookingDate }} · 第 {{ item.periodNo }} 大节</span><small>{{ item.reason }}</small>
            </div>
          </div><el-empty
            v-if="blackouts.length === 0"
            description="暂无特殊停用"
          />
        </section>
        <section class="panel">
          <h2>可借设备</h2><div class="compact-list">
            <div
              v-for="item in equipment"
              :key="item.id"
              class="compact-row"
            >
              <span><strong>{{ item.name }}</strong><small>{{ item.assetCode }} · {{ item.model ?? '无型号' }}</small></span><span>{{ item.totalQuantity }} 件</span>
            </div>
          </div><el-empty
            v-if="equipment.length === 0"
            description="暂无设备"
          />
        </section>
      </div>
    </PageState>
  </section>
</template>
