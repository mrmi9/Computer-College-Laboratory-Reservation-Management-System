<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { apiErrorMessage } from '@/api/errors'
import { attendanceAction, cancelReservation, getReservation, getReservationHistory } from '@/api/reservations'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { HistoryItem, Reservation } from '@/types/business'
import { formatDateTime } from '@/utils/format'

const route = useRoute(); const auth = useAuthStore()
const reservation = ref<Reservation | null>(null); const history = ref<HistoryItem[]>([])
const loading = ref(true); const acting = ref(false); const error = ref('')
const id = computed(() => Number(route.params.id))

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try {
    reservation.value = await getReservation(id.value)
    if (auth.hasPermission('reservation:read:managed')) history.value = await getReservationHistory(id.value)
  } catch (caught) { error.value = apiErrorMessage(caught, '预约详情加载失败。') }
  finally { loading.value = false }
}

async function cancel(): Promise<void> {
  if (reservation.value === null) return
  const result = await ElMessageBox.prompt('请填写取消原因。取消后不可恢复。', `取消预约 ${reservation.value.reservationNo}`, { confirmButtonText: '确认取消', cancelButtonText: '返回', inputPattern: /\S+/, inputErrorMessage: '取消原因不能为空', type: 'warning' }).catch(() => null)
  if (result === null) return
  acting.value = true
  try { reservation.value = await cancelReservation(id.value, result.value); ElMessage.success('预约已取消') }
  catch (caught) { ElMessage.error(apiErrorMessage(caught, '取消失败。')); await load() }
  finally { acting.value = false }
}

async function attend(action: 'check-in' | 'check-out'): Promise<void> {
  acting.value = true
  try { await attendanceAction(id.value, action, false, {}); ElMessage.success(action === 'check-in' ? '签到成功' : '签退成功'); await load() }
  catch (caught) { ElMessage.error(apiErrorMessage(caught, '操作失败。')) }
  finally { acting.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <PageState
      :loading="loading"
      :error="error"
      :empty="reservation === null"
      @retry="load"
    >
      <header
        v-if="reservation"
        class="page-heading"
      >
        <div>
          <p class="eyebrow">
            {{ reservation.reservationNo }}
          </p><h1>{{ reservation.title }}</h1><p>{{ reservation.labName }} · {{ reservation.bookingDate }} · {{ reservation.periodName }}</p>
        </div><StatusTag :status="reservation.status" />
      </header>
      <div
        v-if="reservation"
        class="detail-grid"
      >
        <section class="panel span-two">
          <div class="section-heading">
            <h2>预约信息</h2><div class="heading-actions">
              <el-button
                v-if="['PENDING_APPROVAL','APPROVED'].includes(reservation.status) && reservation.applicantId === auth.user?.id"
                :loading="acting"
                type="danger"
                plain
                @click="cancel"
              >
                取消预约
              </el-button><el-button
                v-if="reservation.status === 'APPROVED' && reservation.applicantId === auth.user?.id"
                :loading="acting"
                type="primary"
                @click="attend('check-in')"
              >
                签到
              </el-button><el-button
                v-if="reservation.status === 'IN_USE' && reservation.applicantId === auth.user?.id"
                :loading="acting"
                type="primary"
                @click="attend('check-out')"
              >
                签退
              </el-button>
            </div>
          </div>
          <dl class="description-grid">
            <div><dt>申请人</dt><dd>{{ reservation.applicantName }}（{{ reservation.applicantType === 'TEACHER' ? '教师' : '学生' }}）</dd></div><div><dt>地点</dt><dd>{{ reservation.building }} · {{ reservation.roomNo }}</dd></div><div><dt>日期</dt><dd>{{ reservation.bookingDate }}</dd></div><div><dt>课次</dt><dd>{{ reservation.periodName }}（{{ reservation.startTime }}–{{ reservation.endTime }}）</dd></div><div><dt>人数</dt><dd>{{ reservation.participantCount }}</dd></div><div><dt>联系电话</dt><dd>{{ reservation.contactPhone }}</dd></div><div><dt>课程/项目</dt><dd>{{ reservation.projectOrCourse }}</dd></div><div><dt>下一步</dt><dd>{{ reservation.nextAction }}</dd></div>
          </dl>
          <h3>用途</h3><p class="pre-wrap">
            {{ reservation.purpose }}
          </p><p
            v-if="reservation.remark"
            class="muted"
          >
            备注：{{ reservation.remark }}
          </p><el-alert
            v-if="reservation.cancellationReason"
            :title="`处理原因：${reservation.cancellationReason}`"
            type="info"
            :closable="false"
          />
        </section>
        <section class="panel">
          <h2>设备</h2><div class="compact-list">
            <div
              v-for="item in reservation.equipmentItems"
              :key="item.equipmentId"
              class="compact-row"
            >
              <span>{{ item.name }}<small>{{ item.assetCode }}</small></span><strong>× {{ item.quantity }}</strong>
            </div>
          </div><el-empty
            v-if="reservation.equipmentItems.length === 0"
            description="未申请设备"
          />
        </section>
        <section class="panel">
          <h2>状态时间线</h2><el-timeline v-if="history.length">
            <el-timeline-item
              v-for="item in history"
              :key="item.id"
              :timestamp="formatDateTime(item.createdAt)"
              placement="top"
            >
              <StatusTag :status="item.toStatus" /><p>{{ item.reason ?? '状态更新' }}<span v-if="item.operatorName"> · {{ item.operatorName }}</span></p>
            </el-timeline-item>
          </el-timeline><div
            v-else
            class="timeline-fallback"
          >
            <StatusTag :status="reservation.status" /><p>最近更新：{{ formatDateTime(reservation.updatedAt) }}</p>
          </div>
        </section>
      </div>
    </PageState>
  </section>
</template>
