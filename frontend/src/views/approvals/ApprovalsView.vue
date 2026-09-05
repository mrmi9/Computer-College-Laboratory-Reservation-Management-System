<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { attendanceAction, decideReservation, getManagedReservations } from '@/api/reservations'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Reservation } from '@/types/business'

const items = ref<Reservation[]>([]); const total = ref(0); const loading = ref(true); const error = ref('')
const filters = reactive({ status: 'PENDING_APPROVAL', applicantType: '', page: 0, size: 20 })

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try { const data = await getManagedReservations(filters); items.value = data.items; total.value = data.total }
  catch (caught) { error.value = apiErrorMessage(caught, '审批列表加载失败。') }
  finally { loading.value = false }
}

async function approve(item: Reservation): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确认通过 ${item.applicantName} 对 ${item.labName} 的预约？审批时系统会重新检查资源冲突。`, `审批 ${item.reservationNo}`, { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '返回' }).catch(() => false)
  if (!confirmed) return
  try { await decideReservation(item.id, 'approve', { version: item.version, comment: '管理端审批通过' }); ElMessage.success('审批已通过'); await load() }
  catch (caught) { ElMessage.error(apiErrorMessage(caught, '审批失败，列表已刷新。')); await load() }
}

async function reasonAction(item: Reservation, action: 'reject' | 'cancel'): Promise<void> {
  const label = action === 'reject' ? '驳回' : '管理员取消'
  const result = await ElMessageBox.prompt(`请填写${label}原因，该原因会通知申请人并写入审计日志。`, `${label} ${item.reservationNo}`, { type: 'warning', inputPattern: /\S+/, inputErrorMessage: '原因不能为空', confirmButtonText: `确认${label}`, cancelButtonText: '返回' }).catch(() => null)
  if (result === null) return
  const request = action === 'reject' ? { version: item.version, reason: result.value } : { version: item.version, reason: result.value }
  try { await decideReservation(item.id, action, request); ElMessage.success(`已${label}`); await load() }
  catch (caught) { ElMessage.error(apiErrorMessage(caught, `${label}失败，列表已刷新。`)); await load() }
}

async function attend(item: Reservation, action: 'check-in' | 'check-out'): Promise<void> {
  const label = action === 'check-in' ? '代签到' : '代签退'
  const confirmed = await ElMessageBox.confirm(`确认对 ${item.reservationNo} 执行${label}？操作会记录当前管理员。`, label, { type: 'warning', confirmButtonText: '确认操作', cancelButtonText: '返回' }).catch(() => false)
  if (!confirmed) return
  try { await attendanceAction(item.id, action, true, {}); ElMessage.success(`${label}成功`); await load() }
  catch (caught) { ElMessage.error(apiErrorMessage(caught, `${label}失败。`)) }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          管理工作台
        </p><h1>审批与现场操作</h1><p>审批、驳回、强制取消和代签到均会留下审计记录。</p>
      </div>
    </header>
    <section class="panel filter-panel">
      <el-form
        class="inline-form"
        @submit.prevent="load"
      >
        <el-form-item label="预约状态">
          <el-select
            v-model="filters.status"
            clearable
          >
            <el-option
              label="待审批"
              value="PENDING_APPROVAL"
            /><el-option
              label="已通过"
              value="APPROVED"
            /><el-option
              label="使用中"
              value="IN_USE"
            /><el-option
              label="已完成"
              value="COMPLETED"
            /><el-option
              label="已取消"
              value="CANCELLED"
            /><el-option
              label="已驳回"
              value="REJECTED"
            /><el-option
              label="已爽约"
              value="NO_SHOW"
            />
          </el-select>
        </el-form-item><el-form-item label="申请人类别">
          <el-select
            v-model="filters.applicantType"
            clearable
          >
            <el-option
              label="学生"
              value="STUDENT"
            /><el-option
              label="教师"
              value="TEACHER"
            />
          </el-select>
        </el-form-item><el-button
          type="primary"
          native-type="submit"
        >
          筛选
        </el-button>
      </el-form>
    </section>
    <PageState
      :loading="loading"
      :error="error"
      :empty="items.length === 0"
      empty-text="没有需要处理的预约"
      @retry="load"
    >
      <div class="responsive-table">
        <el-table :data="items">
          <el-table-column
            label="预约"
            min-width="190"
          >
            <template #default="scope">
              <strong>{{ scope.row.reservationNo }}</strong><br><RouterLink :to="`/reservations/${scope.row.id}`">
                {{ scope.row.title }}
              </RouterLink>
            </template>
          </el-table-column><el-table-column
            label="申请人"
            min-width="130"
          >
            <template #default="scope">
              {{ scope.row.applicantName }}<br><small>{{ scope.row.applicantType === 'TEACHER' ? '教师' : '学生' }}</small>
            </template>
          </el-table-column><el-table-column
            label="实验室与课次"
            min-width="220"
          >
            <template #default="scope">
              {{ scope.row.labName }}<br><small>{{ scope.row.bookingDate }} · {{ scope.row.periodName }}</small>
            </template>
          </el-table-column><el-table-column
            label="状态"
            width="110"
          >
            <template #default="scope">
              <StatusTag :status="scope.row.status" />
            </template>
          </el-table-column><el-table-column
            label="操作"
            min-width="280"
            fixed="right"
          >
            <template #default="scope">
              <div class="table-actions">
                <el-button
                  v-if="scope.row.status === 'PENDING_APPROVAL'"
                  type="primary"
                  size="small"
                  @click="approve(scope.row)"
                >
                  通过
                </el-button><el-button
                  v-if="scope.row.status === 'PENDING_APPROVAL'"
                  type="danger"
                  plain
                  size="small"
                  @click="reasonAction(scope.row, 'reject')"
                >
                  驳回
                </el-button><el-button
                  v-if="['PENDING_APPROVAL','APPROVED'].includes(scope.row.status)"
                  size="small"
                  @click="reasonAction(scope.row, 'cancel')"
                >
                  强制取消
                </el-button><el-button
                  v-if="scope.row.status === 'APPROVED'"
                  size="small"
                  @click="attend(scope.row, 'check-in')"
                >
                  代签到
                </el-button><el-button
                  v-if="scope.row.status === 'IN_USE'"
                  size="small"
                  @click="attend(scope.row, 'check-out')"
                >
                  代签退
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-pagination
        v-if="total > filters.size"
        :current-page="filters.page + 1"
        :page-size="filters.size"
        :total="total"
        layout="prev, pager, next"
        @current-change="(page: number) => { filters.page = page - 1; void load() }"
      />
    </PageState>
  </section>
</template>
