<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getMyReservations } from '@/api/reservations'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Reservation } from '@/types/business'

const items = ref<Reservation[]>([])
const loading = ref(true)
const error = ref('')
const total = ref(0)
const filters = reactive({ status: '', page: 0, size: 20 })

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try { const data = await getMyReservations(filters); items.value = data.items; total.value = data.total }
  catch (caught) { error.value = apiErrorMessage(caught, '预约列表加载失败。') }
  finally { loading.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          个人中心
        </p><h1>我的预约</h1><p>查看审批、签到和使用状态。</p>
      </div><RouterLink to="/reservations/new">
        <el-button type="primary">
          创建预约
        </el-button>
      </RouterLink>
    </header>
    <section class="panel filter-panel">
      <el-form
        class="inline-form"
        @submit.prevent="load"
      >
        <el-form-item label="状态">
          <el-select
            v-model="filters.status"
            clearable
            placeholder="全部状态"
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
      empty-text="当前筛选下没有预约"
      @retry="load"
    >
      <div class="responsive-table">
        <el-table :data="items">
          <el-table-column
            prop="reservationNo"
            label="预约编号"
            min-width="170"
          /><el-table-column
            label="实验室"
            min-width="180"
          >
            <template #default="scope">
              <strong>{{ scope.row.labName }}</strong><br><small>{{ scope.row.building }} · {{ scope.row.roomNo }}</small>
            </template>
          </el-table-column><el-table-column
            label="日期与课次"
            min-width="190"
          >
            <template #default="scope">
              {{ scope.row.bookingDate }}<br><small>{{ scope.row.periodName }}（{{ scope.row.startTime }}–{{ scope.row.endTime }}）</small>
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
            width="100"
          >
            <template #default="scope">
              <RouterLink :to="`/reservations/${scope.row.id}`">
                查看详情
              </RouterLink>
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
