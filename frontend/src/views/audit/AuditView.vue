<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getAuditLogs } from '@/api/administration'
import { apiErrorMessage } from '@/api/errors'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { AuditLog } from '@/types/business'
import { formatDateTime } from '@/utils/format'

const items = ref<AuditLog[]>([]); const total = ref(0); const loading = ref(true); const error = ref(''); const detail = ref<AuditLog | null>(null)
const filters = reactive({ action: '', actorUsername: '', page: 0, size: 20 })
async function load(): Promise<void> { loading.value = true; error.value = ''; try { const data = await getAuditLogs(filters); items.value = data.items; total.value = data.total } catch (caught) { error.value = apiErrorMessage(caught, '审计日志加载失败。') } finally { loading.value = false } }
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          安全追踪
        </p><h1>审计日志</h1><p>日志只记录必要的操作元数据，不展示密码或令牌。</p>
      </div>
    </header><section class="panel filter-panel">
      <el-form
        class="inline-form"
        @submit.prevent="load"
      >
        <el-form-item label="动作">
          <el-input
            v-model="filters.action"
            clearable
            placeholder="例如 LOGIN_SUCCESS"
          />
        </el-form-item><el-form-item label="操作者">
          <el-input
            v-model="filters.actorUsername"
            clearable
          />
        </el-form-item><el-button
          type="primary"
          native-type="submit"
        >
          筛选
        </el-button>
      </el-form>
    </section><PageState
      :loading="loading"
      :error="error"
      :empty="items.length === 0"
      @retry="load"
    >
      <div class="responsive-table">
        <el-table
          :data="items"
          @row-click="(row: AuditLog) => detail = row"
        >
          <el-table-column
            label="时间"
            min-width="170"
          >
            <template #default="scope">
              {{ formatDateTime(scope.row.createdAt) }}
            </template>
          </el-table-column><el-table-column
            prop="actorUsername"
            label="操作者"
            min-width="120"
          /><el-table-column
            prop="action"
            label="动作"
            min-width="190"
          /><el-table-column
            label="对象"
            min-width="150"
          >
            <template #default="scope">
              {{ scope.row.targetType ?? '—' }} · {{ scope.row.targetId ?? '—' }}
            </template>
          </el-table-column><el-table-column
            label="结果"
            width="100"
          >
            <template #default="scope">
              <StatusTag :status="scope.row.result" />
            </template>
          </el-table-column><el-table-column
            prop="requestId"
            label="请求 ID"
            min-width="210"
          />
        </el-table>
      </div><el-pagination
        v-if="total > filters.size"
        :current-page="filters.page + 1"
        :page-size="filters.size"
        :total="total"
        layout="prev, pager, next"
        @current-change="(page: number) => { filters.page = page - 1; void load() }"
      />
    </PageState><el-drawer
      :model-value="detail !== null"
      title="审计详情"
      size="min(520px, 92vw)"
      @close="detail = null"
    >
      <el-descriptions
        v-if="detail"
        :column="1"
        border
      >
        <el-descriptions-item label="动作">
          {{ detail.action }}
        </el-descriptions-item><el-descriptions-item label="操作者">
          {{ detail.actorUsername ?? '系统' }}
        </el-descriptions-item><el-descriptions-item label="请求 ID">
          {{ detail.requestId }}
        </el-descriptions-item><el-descriptions-item label="结果">
          <StatusTag :status="detail.result" />
        </el-descriptions-item><el-descriptions-item label="必要详情">
          <pre>{{ JSON.stringify(detail.detail, null, 2) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </section>
</template>
