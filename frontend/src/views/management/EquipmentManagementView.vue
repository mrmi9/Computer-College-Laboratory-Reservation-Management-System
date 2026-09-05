<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiErrorMessage } from '@/api/errors'
import { getEquipment, getLabs, saveEquipment, type EquipmentMutation } from '@/api/catalog'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Equipment, Lab } from '@/types/business'

const items = ref<Equipment[]>([]); const labs = ref<Lab[]>([]); const loading = ref(true); const error = ref(''); const editorOpen = ref(false); const editingId = ref<number | null>(null)
const filters = reactive({ labId: undefined as number | undefined, status: '', page: 0, size: 100 })
const form = reactive<EquipmentMutation>(emptyEquipment())
function emptyEquipment(): EquipmentMutation { return { labId: 0, assetCode: '', category: '', name: '', model: '', totalQuantity: 1, requiredQualification: '', status: 'AVAILABLE', version: 0 } }
async function load(): Promise<void> { loading.value = true; error.value = ''; try { const [equipment, labData] = await Promise.all([getEquipment(filters), getLabs({ size: 100 })]); items.value = equipment.items; labs.value = labData.items } catch (caught) { error.value = apiErrorMessage(caught, '设备列表加载失败。') } finally { loading.value = false } }
function edit(item?: Equipment): void { editingId.value = item?.id ?? null; Object.assign(form, item === undefined ? emptyEquipment() : { ...item, model: item.model ?? '', requiredQualification: item.requiredQualification ?? '' }); editorOpen.value = true }
async function submit(): Promise<void> { try { await saveEquipment(editingId.value, form); ElMessage.success('设备已保存'); editorOpen.value = false; await load() } catch (caught) { ElMessage.error(apiErrorMessage(caught, '设备保存失败。')) } }
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          资产配置
        </p><h1>设备管理</h1><p>维护设备数量、归属、状态和所需资质。</p>
      </div><el-button
        type="primary"
        @click="edit()"
      >
        新增设备
      </el-button>
    </header><section class="panel filter-panel">
      <el-form
        class="inline-form"
        @submit.prevent="load"
      >
        <el-form-item label="实验室">
          <el-select
            v-model="filters.labId"
            clearable
          >
            <el-option
              v-for="lab in labs"
              :key="lab.id"
              :label="lab.name"
              :value="lab.id"
            />
          </el-select>
        </el-form-item><el-form-item label="状态">
          <el-select
            v-model="filters.status"
            clearable
          >
            <el-option
              label="可用"
              value="AVAILABLE"
            /><el-option
              label="维护中"
              value="MAINTENANCE"
            /><el-option
              label="已报废"
              value="RETIRED"
            />
          </el-select>
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
        <el-table :data="items">
          <el-table-column
            prop="assetCode"
            label="资产编号"
            width="140"
          /><el-table-column
            label="设备"
            min-width="180"
          >
            <template #default="scope">
              <strong>{{ scope.row.name }}</strong><br><small>{{ scope.row.category }} · {{ scope.row.model ?? '无型号' }}</small>
            </template>
          </el-table-column><el-table-column
            prop="labName"
            label="所属实验室"
            min-width="150"
          /><el-table-column
            prop="totalQuantity"
            label="数量"
            width="80"
          /><el-table-column
            label="状态"
            width="100"
          >
            <template #default="scope">
              <StatusTag :status="scope.row.status" />
            </template>
          </el-table-column><el-table-column
            label="操作"
            width="90"
          >
            <template #default="scope">
              <el-button
                text
                @click="edit(scope.row)"
              >
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </PageState><el-dialog
      v-model="editorOpen"
      :title="editingId === null ? '新增设备' : '编辑设备'"
      width="min(680px, 94vw)"
    >
      <el-form
        :model="form"
        label-position="top"
        @submit.prevent="submit"
      >
        <div class="form-grid">
          <el-form-item
            label="所属实验室"
            required
          >
            <el-select v-model="form.labId">
              <el-option
                v-for="lab in labs"
                :key="lab.id"
                :label="lab.name"
                :value="lab.id"
              />
            </el-select>
          </el-form-item><el-form-item
            label="资产编号"
            required
          >
            <el-input v-model="form.assetCode" />
          </el-form-item><el-form-item
            label="设备名称"
            required
          >
            <el-input v-model="form.name" />
          </el-form-item><el-form-item
            label="分类"
            required
          >
            <el-input v-model="form.category" />
          </el-form-item><el-form-item label="型号">
            <el-input v-model="form.model" />
          </el-form-item><el-form-item label="总数量">
            <el-input-number
              v-model="form.totalQuantity"
              :min="1"
            />
          </el-form-item><el-form-item label="状态">
            <el-select v-model="form.status">
              <el-option
                label="可用"
                value="AVAILABLE"
              /><el-option
                label="维护中"
                value="MAINTENANCE"
              /><el-option
                label="已报废"
                value="RETIRED"
              />
            </el-select>
          </el-form-item><el-form-item label="所需资质">
            <el-input
              v-model="form.requiredQualification"
              placeholder="可留空"
            />
          </el-form-item>
        </div><div class="dialog-actions">
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
  </section>
</template>
