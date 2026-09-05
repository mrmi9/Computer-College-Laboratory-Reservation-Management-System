<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getAvailability, getLabs, getPeriods } from '@/api/catalog'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { AvailableLab, Lab, Period } from '@/types/business'
import { todayIso } from '@/utils/format'

const mode = ref<'browse' | 'available'>('available')
const auth = useAuthStore()
const labs = ref<Array<Lab | AvailableLab>>([])
const periods = ref<Period[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const search = reactive({ keyword: '', status: '', page: 0, size: 12 })
const availability = reactive({ bookingDate: todayIso(1), periodNo: 1, capacity: 1 })

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'available') {
      labs.value = await getAvailability(availability)
      total.value = labs.value.length
    } else {
      const result = await getLabs(search)
      labs.value = result.items
      total.value = result.total
    }
  } catch (caught) {
    error.value = apiErrorMessage(caught, '实验室数据加载失败。')
  } finally {
    loading.value = false
  }
}

function switchMode(value: string | number | boolean | undefined): void {
  mode.value = value === 'browse' ? 'browse' : 'available'
  search.page = 0
  void load()
}

onMounted(async () => {
  try { periods.value = await getPeriods() } catch { periods.value = [] }
  await load()
})
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          资源检索
        </p><h1>实验室与空闲课次</h1><p>按日期、课次和人数查找真实可预约资源。</p>
      </div>
    </header>
    <section class="panel filter-panel">
      <el-segmented
        :model-value="mode"
        :options="[{ label: '空闲查询', value: 'available' }, { label: '全部实验室', value: 'browse' }]"
        @change="switchMode"
      />
      <el-form
        v-if="mode === 'available'"
        class="inline-form"
        :model="availability"
        label-position="top"
        @submit.prevent="load"
      >
        <el-form-item label="预约日期">
          <el-date-picker
            v-model="availability.bookingDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item label="大课课次">
          <el-select v-model="availability.periodNo">
            <el-option
              v-for="period in periods"
              :key="period.periodNo"
              :value="period.periodNo"
              :label="`${period.name}（${period.startTime}–${period.endTime}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="参加人数">
          <el-input-number
            v-model="availability.capacity"
            :min="1"
            :max="500"
          />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
        >
          查询空闲
        </el-button>
      </el-form>
      <el-form
        v-else
        class="inline-form"
        :model="search"
        label-position="top"
        @submit.prevent="load"
      >
        <el-form-item label="名称或编号">
          <el-input
            v-model="search.keyword"
            clearable
            placeholder="例如：人工智能"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="search.status"
            clearable
          >
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
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
        >
          检索
        </el-button>
      </el-form>
    </section>
    <PageState
      :loading="loading"
      :error="error"
      :empty="labs.length === 0"
      empty-text="当前条件下没有可用实验室"
      @retry="load"
    >
      <p class="result-count">
        共 {{ total }} 间实验室
      </p>
      <div class="lab-grid">
        <article
          v-for="lab in labs"
          :key="lab.id"
          class="lab-card"
        >
          <div class="lab-card-head">
            <span class="lab-code">{{ lab.code }}</span><StatusTag
              v-if="'status' in lab"
              :status="lab.status"
            />
          </div>
          <h2>{{ lab.name }}</h2><p>{{ lab.building }} · {{ lab.roomNo }} · 容纳 {{ lab.capacity }} 人</p>
          <div class="tag-list">
            <el-tag
              v-for="tag in lab.tags"
              :key="tag"
              effect="plain"
            >
              {{ tag }}
            </el-tag>
          </div>
          <div class="card-actions">
            <RouterLink :to="`/labs/${lab.id}`">
              <el-button>查看详情</el-button>
            </RouterLink>
            <RouterLink
              v-if="mode === 'available' && auth.applicantType !== null"
              :to="{ path: '/reservations/new', query: { date: availability.bookingDate, period: availability.periodNo, people: availability.capacity, lab: lab.id } }"
            >
              <el-button type="primary">
                选择预约
              </el-button>
            </RouterLink>
          </div>
        </article>
      </div>
      <el-pagination
        v-if="mode === 'browse' && total > search.size"
        :current-page="search.page + 1"
        :page-size="search.size"
        :total="total"
        layout="prev, pager, next"
        @current-change="(page: number) => { search.page = page - 1; void load() }"
      />
    </PageState>
  </section>
</template>
