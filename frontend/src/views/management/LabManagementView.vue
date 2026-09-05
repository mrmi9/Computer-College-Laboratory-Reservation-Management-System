<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiErrorMessage } from '@/api/errors'
import { createBlackout, deleteBlackout, getBlackouts, getLabs, getOpenRules, replaceOpenRules, saveLab, type LabMutation } from '@/api/catalog'
import { getUsers } from '@/api/administration'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { Blackout, Lab, UserAdmin } from '@/types/business'

const auth = useAuthStore()
const labs = ref<Lab[]>([]); const loading = ref(true); const error = ref(''); const editorOpen = ref(false)
const managerOptions = ref<UserAdmin[]>([])
const rulesOpen = ref(false); const blackoutOpen = ref(false); const editingId = ref<number | null>(null); const selectedLab = ref<Lab | null>(null)
const selectedRules = ref<string[]>([]); const blackouts = ref<Blackout[]>([])
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const labForm = reactive<LabMutation>(emptyLab())
const blackoutForm = reactive({ bookingDate: '', periodNo: 1, reason: '' })

function emptyLab(): LabMutation { return { code: '', name: '', building: '', roomNo: '', capacity: 30, labType: '通用机房', description: '', imageUrl: '', tags: [], status: 'ACTIVE', studentApprovalMode: 'MANUAL', teacherApprovalMode: 'MANUAL', allowStudentBooking: true, maxPeriodsPerUserDay: 2, advanceDays: 14, cancelBeforeMinutes: 120, requireCheckIn: true, responsibleUserId: null, version: 0 } }

async function load(): Promise<void> { loading.value = true; error.value = ''; try { labs.value = (await getLabs({ size: 100 })).items; if (auth.hasRole('SYSTEM_ADMIN')) managerOptions.value = (await getUsers({ status: 'ACTIVE', size: 100 })).items.filter((user) => user.roles.includes('LAB_ADMIN')) } catch (caught) { error.value = apiErrorMessage(caught, '实验室列表加载失败。') } finally { loading.value = false } }
function edit(lab?: Lab): void { editingId.value = lab?.id ?? null; Object.assign(labForm, lab === undefined ? emptyLab() : { ...lab, description: lab.description ?? '', imageUrl: lab.imageUrl ?? '', tags: [...lab.tags], responsibleUserId: lab.responsibleUserId ?? null }); editorOpen.value = true }
async function submitLab(): Promise<void> { if (editingId.value === null && labForm.responsibleUserId === null) { ElMessage.warning('请选择实验室负责人'); return } try { await saveLab(editingId.value, labForm); ElMessage.success(editingId.value === null ? '实验室已创建' : '实验室已更新'); editorOpen.value = false; await load() } catch (caught) { ElMessage.error(apiErrorMessage(caught, '保存失败。')) } }

async function manageRules(lab: Lab): Promise<void> { selectedLab.value = lab; try { const rules = await getOpenRules(lab.id); selectedRules.value = rules.map((rule) => `${rule.dayOfWeek}-${rule.periodNo}`); rulesOpen.value = true } catch (caught) { ElMessage.error(apiErrorMessage(caught, '开放规则加载失败。')) } }
async function saveRules(): Promise<void> { if (selectedLab.value === null) return; const rules = selectedRules.value.map((value) => { const [day, period] = value.split('-').map(Number); return { dayOfWeek: day ?? 1, periodNo: period ?? 1 } }); try { await replaceOpenRules(selectedLab.value.id, rules); ElMessage.success('开放规则已保存'); rulesOpen.value = false } catch (caught) { ElMessage.error(apiErrorMessage(caught, '规则保存失败。')) } }

async function manageBlackouts(lab: Lab): Promise<void> { selectedLab.value = lab; blackoutForm.bookingDate = ''; blackoutForm.periodNo = 1; blackoutForm.reason = ''; try { blackouts.value = await getBlackouts(lab.id); blackoutOpen.value = true } catch (caught) { ElMessage.error(apiErrorMessage(caught, '停用课次加载失败。')) } }
async function addBlackout(): Promise<void> { if (selectedLab.value === null || !blackoutForm.bookingDate || !blackoutForm.reason.trim()) return; try { await createBlackout(selectedLab.value.id, blackoutForm); blackouts.value = await getBlackouts(selectedLab.value.id); blackoutForm.reason = ''; ElMessage.success('停用课次已添加') } catch (caught) { ElMessage.error(apiErrorMessage(caught, '添加失败。')) } }
async function removeBlackout(item: Blackout): Promise<void> { if (selectedLab.value === null) return; const confirmed = await ElMessageBox.confirm(`删除 ${item.bookingDate} 第 ${item.periodNo} 大节的停用安排？`, '删除停用课次', { type: 'warning' }).catch(() => false); if (!confirmed) return; await deleteBlackout(selectedLab.value.id, item.id); blackouts.value = blackouts.value.filter((candidate) => candidate.id !== item.id) }
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          资源配置
        </p><h1>实验室管理</h1><p>维护实验室状态、预约策略、每周开放和特殊停用课次。</p>
      </div><el-button
        v-if="auth.hasRole('SYSTEM_ADMIN')"
        type="primary"
        @click="edit()"
      >
        新增实验室
      </el-button>
    </header>
    <PageState
      :loading="loading"
      :error="error"
      :empty="labs.length === 0"
      @retry="load"
    >
      <div class="responsive-table">
        <el-table :data="labs">
          <el-table-column
            prop="code"
            label="编号"
            width="120"
          /><el-table-column
            label="实验室"
            min-width="180"
          >
            <template #default="scope">
              <strong>{{ scope.row.name }}</strong><br><small>{{ scope.row.building }} · {{ scope.row.roomNo }}</small>
            </template>
          </el-table-column><el-table-column
            prop="capacity"
            label="容量"
            width="80"
          /><el-table-column
            label="负责人"
            min-width="120"
          >
            <template #default="scope">
              {{ scope.row.responsibleUserName ?? '未设置' }}
            </template>
          </el-table-column><el-table-column
            label="审批策略"
            min-width="150"
          >
            <template #default="scope">
              <small>学生 {{ scope.row.studentApprovalMode === 'AUTO' ? '自动' : '人工' }} · 教师 {{ scope.row.teacherApprovalMode === 'AUTO' ? '自动' : '人工' }}</small>
            </template>
          </el-table-column><el-table-column
            label="状态"
            width="100"
          >
            <template #default="scope">
              <StatusTag :status="scope.row.status" />
            </template>
          </el-table-column><el-table-column
            label="操作"
            min-width="260"
            fixed="right"
          >
            <template #default="scope">
              <div class="table-actions">
                <el-button
                  size="small"
                  @click="edit(scope.row)"
                >
                  编辑
                </el-button><el-button
                  size="small"
                  @click="manageRules(scope.row)"
                >
                  开放规则
                </el-button><el-button
                  size="small"
                  @click="manageBlackouts(scope.row)"
                >
                  停用课次
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </PageState>

    <el-dialog
      v-model="editorOpen"
      :title="editingId === null ? '新增实验室' : '编辑实验室'"
      width="min(760px, 94vw)"
    >
      <el-form
        :model="labForm"
        label-position="top"
        @submit.prevent="submitLab"
      >
        <div class="form-grid">
          <el-form-item
            label="编号"
            required
          >
            <el-input v-model="labForm.code" />
          </el-form-item><el-form-item
            label="名称"
            required
          >
            <el-input v-model="labForm.name" />
          </el-form-item><el-form-item
            label="楼宇"
            required
          >
            <el-input v-model="labForm.building" />
          </el-form-item><el-form-item
            label="房间"
            required
          >
            <el-input v-model="labForm.roomNo" />
          </el-form-item><el-form-item label="容量">
            <el-input-number
              v-model="labForm.capacity"
              :min="1"
            />
          </el-form-item><el-form-item label="类型">
            <el-input v-model="labForm.labType" />
          </el-form-item><el-form-item label="状态">
            <el-select v-model="labForm.status">
              <el-option
                label="启用"
                value="ACTIVE"
              /><el-option
                label="维护中"
                value="MAINTENANCE"
              /><el-option
                label="停用"
                value="DISABLED"
              />
            </el-select>
          </el-form-item><el-form-item label="标签（回车分隔）">
            <el-select
              v-model="labForm.tags"
              multiple
              filterable
              allow-create
              default-first-option
            />
          </el-form-item><el-form-item
            label="主负责人"
            required
          >
            <el-select
              v-model="labForm.responsibleUserId"
              :disabled="!auth.hasRole('SYSTEM_ADMIN')"
              placeholder="选择实验室管理员"
            >
              <el-option
                v-for="manager in managerOptions"
                :key="manager.id"
                :label="`${manager.realName}（${manager.username}）`"
                :value="manager.id"
              />
            </el-select>
          </el-form-item><el-form-item label="学生审批">
            <el-select v-model="labForm.studentApprovalMode">
              <el-option
                label="人工"
                value="MANUAL"
              /><el-option
                label="自动"
                value="AUTO"
              />
            </el-select>
          </el-form-item><el-form-item label="教师审批">
            <el-select v-model="labForm.teacherApprovalMode">
              <el-option
                label="人工"
                value="MANUAL"
              /><el-option
                label="自动"
                value="AUTO"
              />
            </el-select>
          </el-form-item><el-form-item label="提前预约天数">
            <el-input-number
              v-model="labForm.advanceDays"
              :min="0"
            />
          </el-form-item><el-form-item label="取消提前分钟">
            <el-input-number
              v-model="labForm.cancelBeforeMinutes"
              :min="0"
            />
          </el-form-item><el-form-item label="每日最多课次">
            <el-input-number
              v-model="labForm.maxPeriodsPerUserDay"
              :min="1"
              :max="4"
            />
          </el-form-item><el-form-item label="预约资格">
            <el-checkbox v-model="labForm.allowStudentBooking">
              允许学生预约
            </el-checkbox><el-checkbox v-model="labForm.requireCheckIn">
              要求签到
            </el-checkbox>
          </el-form-item>
        </div><el-form-item label="说明">
          <el-input
            v-model="labForm.description"
            type="textarea"
            :rows="3"
          />
        </el-form-item><div class="dialog-actions">
          <el-button @click="editorOpen = false">
            取消
          </el-button><el-button
            type="primary"
            native-type="submit"
          >
            保存
          </el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-dialog
      v-model="rulesOpen"
      :title="`${selectedLab?.name ?? ''} · 每周开放规则`"
      width="min(720px, 94vw)"
    >
      <p class="muted">
        勾选允许预约的星期和大课课次；保存会整体替换现有规则。
      </p><div class="rule-matrix">
        <div
          v-for="(day, dayIndex) in weekdays"
          :key="day"
        >
          <strong>{{ day }}</strong><el-checkbox-group v-model="selectedRules">
            <el-checkbox
              v-for="period in 4"
              :key="period"
              :value="`${dayIndex + 1}-${period}`"
            >
              第 {{ period }} 大节
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div><div class="dialog-actions">
        <el-button @click="rulesOpen = false">
          取消
        </el-button><el-button
          type="primary"
          @click="saveRules"
        >
          保存规则
        </el-button>
      </div>
    </el-dialog>

    <el-dialog
      v-model="blackoutOpen"
      :title="`${selectedLab?.name ?? ''} · 特殊停用课次`"
      width="min(760px, 94vw)"
    >
      <el-form
        class="inline-form"
        @submit.prevent="addBlackout"
      >
        <el-form-item label="日期">
          <el-date-picker
            v-model="blackoutForm.bookingDate"
            type="date"
            value-format="YYYY-MM-DD"
          />
        </el-form-item><el-form-item label="课次">
          <el-select v-model="blackoutForm.periodNo">
            <el-option
              v-for="period in 4"
              :key="period"
              :value="period"
              :label="`第 ${period} 大节`"
            />
          </el-select>
        </el-form-item><el-form-item label="原因">
          <el-input v-model="blackoutForm.reason" />
        </el-form-item><el-button
          type="primary"
          native-type="submit"
        >
          添加
        </el-button>
      </el-form><div class="compact-list">
        <div
          v-for="item in blackouts"
          :key="item.id"
          class="compact-row"
        >
          <span>{{ item.bookingDate }} · 第 {{ item.periodNo }} 大节<small>{{ item.reason }}</small></span><el-button
            type="danger"
            text
            @click="removeBlackout(item)"
          >
            删除
          </el-button>
        </div>
      </div><el-empty
        v-if="blackouts.length === 0"
        description="暂无特殊停用课次"
      />
    </el-dialog>
  </section>
</template>
