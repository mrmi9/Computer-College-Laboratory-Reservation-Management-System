<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const statusMap: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' | 'primary'; mark: string }> = {
  PENDING_APPROVAL: { label: '待审批', type: 'warning', mark: '待' },
  APPROVED: { label: '已通过', type: 'success', mark: '通' },
  REJECTED: { label: '已驳回', type: 'danger', mark: '驳' },
  CANCELLED: { label: '已取消', type: 'info', mark: '取' },
  IN_USE: { label: '使用中', type: 'primary', mark: '用' },
  COMPLETED: { label: '已完成', type: 'success', mark: '完' },
  NO_SHOW: { label: '已爽约', type: 'danger', mark: '爽' },
  ACTIVE: { label: '启用', type: 'success', mark: '启' },
  DISABLED: { label: '停用', type: 'info', mark: '停' },
  LOCKED: { label: '锁定', type: 'danger', mark: '锁' },
  MAINTENANCE: { label: '维护中', type: 'warning', mark: '维' },
  AVAILABLE: { label: '可用', type: 'success', mark: '可' },
  RETIRED: { label: '已报废', type: 'info', mark: '废' },
  SUCCESS: { label: '成功', type: 'success', mark: '成' },
  FAILURE: { label: '失败', type: 'danger', mark: '失' },
}

const item = computed(() => statusMap[props.status] ?? { label: props.status, type: 'info' as const, mark: '·' })
</script>

<template>
  <el-tag
    :type="item.type"
    effect="light"
  >
    <span aria-hidden="true">{{ item.mark }}</span>
    {{ item.label }}
  </el-tag>
</template>
