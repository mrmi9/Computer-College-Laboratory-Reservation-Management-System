<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { AxiosError } from 'axios'
import { useRoute, useRouter } from 'vue-router'
import { getAvailability, getEquipment, getPeriods } from '@/api/catalog'
import { apiErrorMessage } from '@/api/errors'
import { createReservation } from '@/api/reservations'
import PageState from '@/components/PageState.vue'
import { useAuthStore } from '@/stores/auth'
import type { AvailableLab, Equipment, Period } from '@/types/business'
import { todayIso } from '@/utils/format'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const step = ref(0)
const periods = ref<Period[]>([])
const availableLabs = ref<AvailableLab[]>([])
const equipment = ref<Equipment[]>([])
const selectedLab = ref<AvailableLab | null>(null)
const quantities = reactive<Record<number, number>>({})
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const idempotencyKey = ref(crypto.randomUUID())
const draft = reactive({
  bookingDate: typeof route.query.date === 'string' ? route.query.date : todayIso(1),
  periodNo: Number(route.query.period ?? 1),
  participantCount: Number(route.query.people ?? 1),
  title: '', purpose: '', projectOrCourse: '', contactPhone: auth.user?.phone ?? '', remark: '',
})

const identityLabel = computed(() => auth.applicantType === 'TEACHER' ? '教师预约' : '学生预约')
const selectedEquipment = computed(() => equipment.value
  .filter((item) => (quantities[item.id] ?? 0) > 0)
  .map((item) => ({ equipmentId: item.id, quantity: quantities[item.id] ?? 0, name: item.name })))
const selectedPeriod = computed(() => periods.value.find((item) => item.periodNo === draft.periodNo))

async function searchAvailability(): Promise<void> {
  if (!draft.bookingDate || draft.participantCount < 1) return
  loading.value = true
  error.value = ''
  try {
    availableLabs.value = await getAvailability({
      bookingDate: draft.bookingDate, periodNo: draft.periodNo, capacity: draft.participantCount,
    })
    if (selectedLab.value !== null && !availableLabs.value.some((item) => item.id === selectedLab.value?.id)) {
      selectedLab.value = null
    }
    step.value = 1
  } catch (caught) {
    error.value = apiErrorMessage(caught, '空闲实验室查询失败。')
  } finally { loading.value = false }
}

async function chooseLab(lab: AvailableLab): Promise<void> {
  selectedLab.value = lab
  loading.value = true
  error.value = ''
  try {
    equipment.value = (await getEquipment({ labId: lab.id, status: 'AVAILABLE', size: 100 })).items
    step.value = 2
  } catch (caught) {
    error.value = apiErrorMessage(caught, '设备数据加载失败。')
  } finally { loading.value = false }
}

function toReview(): void {
  if (!draft.title.trim() || !draft.purpose.trim() || !draft.projectOrCourse.trim() || !draft.contactPhone.trim()) {
    error.value = '请完整填写标题、用途、课程或项目和联系电话。'
    return
  }
  error.value = ''
  step.value = 3
}

async function submit(): Promise<void> {
  if (selectedLab.value === null || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const result = await createReservation({
      labId: selectedLab.value.id, title: draft.title.trim(), purpose: draft.purpose.trim(),
      participantCount: draft.participantCount, bookingDate: draft.bookingDate, periodNo: draft.periodNo,
      projectOrCourse: draft.projectOrCourse.trim(), contactPhone: draft.contactPhone.trim(),
      equipmentItems: selectedEquipment.value.map(({ equipmentId, quantity }) => ({ equipmentId, quantity })),
      remark: draft.remark.trim(),
    }, idempotencyKey.value)
    await router.push(`/reservations/${result.id}`)
  } catch (caught) {
    error.value = apiErrorMessage(caught, '预约提交失败，请稍后重试。')
    if (caught instanceof AxiosError && caught.response?.status === 409) {
      step.value = 1
      selectedLab.value = null
      await searchAvailability()
      error.value = '所选实验室刚刚发生冲突，已保留填写内容并刷新可选列表。'
    }
  } finally { submitting.value = false }
}

onMounted(async () => {
  try {
    periods.value = await getPeriods()
    if (route.query.lab !== undefined) {
      await searchAvailability()
      const requested = availableLabs.value.find((item) => item.id === Number(route.query.lab))
      if (requested !== undefined) selectedLab.value = requested
    }
  } catch (caught) { error.value = apiErrorMessage(caught, '课次配置加载失败。') }
})
</script>

<template>
  <section class="page-stack wizard-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          {{ identityLabel }}
        </p><h1>创建实验室预约</h1><p>申请人身份由系统确认，不能在页面切换。</p>
      </div>
    </header>
    <el-steps
      :active="step"
      finish-status="success"
      align-center
    >
      <el-step title="日期与课次" /><el-step title="选择实验室" /><el-step title="用途与设备" /><el-step title="确认提交" />
    </el-steps>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    />
    <section class="panel wizard-panel">
      <el-form
        v-if="step === 0"
        :model="draft"
        label-position="top"
        @submit.prevent="searchAvailability"
      >
        <div class="form-grid">
          <el-form-item
            label="预约日期"
            required
          >
            <el-date-picker
              v-model="draft.bookingDate"
              type="date"
              value-format="YYYY-MM-DD"
              :clearable="false"
            />
          </el-form-item>
          <el-form-item
            label="大课课次"
            required
          >
            <el-select v-model="draft.periodNo">
              <el-option
                v-for="period in periods"
                :key="period.periodNo"
                :label="`${period.name}（${period.startTime}–${period.endTime}）`"
                :value="period.periodNo"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            label="参加人数"
            required
          >
            <el-input-number
              v-model="draft.participantCount"
              :min="1"
              :max="500"
            />
          </el-form-item>
        </div>
        <div class="wizard-actions">
          <el-button
            type="primary"
            native-type="submit"
            :loading="loading"
          >
            查询可用实验室
          </el-button>
        </div>
      </el-form>

      <PageState
        v-else-if="step === 1"
        :loading="loading"
        :empty="availableLabs.length === 0"
        empty-text="没有符合条件的空闲实验室"
      >
        <div class="selection-list">
          <button
            v-for="lab in availableLabs"
            :key="lab.id"
            type="button"
            class="selection-card"
            :class="{ selected: selectedLab?.id === lab.id }"
            @click="selectedLab = lab"
          >
            <span><strong>{{ lab.name }}</strong><small>{{ lab.building }} · {{ lab.roomNo }} · {{ lab.capacity }} 人</small></span><span>{{ lab.tags.join(' · ') }}</span>
          </button>
        </div>
        <div class="wizard-actions">
          <el-button @click="step = 0">
            上一步
          </el-button><el-button
            type="primary"
            :disabled="selectedLab === null"
            @click="selectedLab && chooseLab(selectedLab)"
          >
            下一步
          </el-button>
        </div>
      </PageState>

      <el-form
        v-else-if="step === 2"
        :model="draft"
        label-position="top"
        @submit.prevent="toReview"
      >
        <div class="form-grid">
          <el-form-item
            label="预约标题"
            required
          >
            <el-input
              v-model="draft.title"
              maxlength="120"
              show-word-limit
            />
          </el-form-item>
          <el-form-item
            label="课程或项目"
            required
          >
            <el-input
              v-model="draft.projectOrCourse"
              maxlength="120"
            />
          </el-form-item>
          <el-form-item
            label="联系电话"
            required
          >
            <el-input
              v-model="draft.contactPhone"
              maxlength="32"
            />
          </el-form-item>
        </div>
        <el-form-item
          label="用途说明"
          required
        >
          <el-input
            v-model="draft.purpose"
            type="textarea"
            :rows="4"
            maxlength="5000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="draft.remark"
            type="textarea"
            :rows="2"
            maxlength="5000"
          />
        </el-form-item>
        <fieldset class="equipment-fieldset">
          <legend>设备申请（可选）</legend><div
            v-if="equipment.length"
            class="equipment-picker"
          >
            <label
              v-for="item in equipment"
              :key="item.id"
            ><span><strong>{{ item.name }}</strong><small>{{ item.assetCode }} · 最多 {{ item.totalQuantity }}</small></span><el-input-number
              v-model="quantities[item.id]"
              :min="0"
              :max="item.totalQuantity"
            /></label>
          </div><el-empty
            v-else
            description="该实验室没有可申请设备"
          />
        </fieldset>
        <div class="wizard-actions">
          <el-button @click="step = 1">
            上一步
          </el-button><el-button
            type="primary"
            native-type="submit"
          >
            核对申请
          </el-button>
        </div>
      </el-form>

      <div
        v-else
        class="review-stack"
      >
        <el-descriptions
          title="预约信息"
          :column="2"
          border
        >
          <el-descriptions-item label="申请身份">
            {{ identityLabel }}
          </el-descriptions-item><el-descriptions-item label="实验室">
            {{ selectedLab?.name }}
          </el-descriptions-item>
          <el-descriptions-item label="日期">
            {{ draft.bookingDate }}
          </el-descriptions-item><el-descriptions-item label="课次">
            {{ selectedPeriod?.name }}（{{ selectedPeriod?.startTime }}–{{ selectedPeriod?.endTime }}）
          </el-descriptions-item>
          <el-descriptions-item label="人数">
            {{ draft.participantCount }}
          </el-descriptions-item><el-descriptions-item label="课程/项目">
            {{ draft.projectOrCourse }}
          </el-descriptions-item>
          <el-descriptions-item
            label="标题"
            :span="2"
          >
            {{ draft.title }}
          </el-descriptions-item><el-descriptions-item
            label="用途"
            :span="2"
          >
            {{ draft.purpose }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          title="提交后将按实验室对当前身份的策略自动通过或进入人工审批。取消需遵守该实验室截止规则。"
          type="info"
          show-icon
          :closable="false"
        />
        <div
          v-if="selectedEquipment.length"
          class="tag-list"
        >
          <el-tag
            v-for="item in selectedEquipment"
            :key="item.equipmentId"
          >
            {{ item.name }} × {{ item.quantity }}
          </el-tag>
        </div>
        <div class="wizard-actions">
          <el-button @click="step = 2">
            上一步
          </el-button><el-button
            type="primary"
            :loading="submitting"
            @click="submit"
          >
            确认并提交
          </el-button>
        </div>
      </div>
    </section>
  </section>
</template>
