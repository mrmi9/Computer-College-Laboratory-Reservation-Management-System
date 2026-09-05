<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { BarChart } from 'echarts/charts'
import { AriaComponent, GridComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import type { ECharts } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { ElMessage } from 'element-plus'
import { apiErrorMessage } from '@/api/errors'
import { getLabs } from '@/api/catalog'
import { exportStatistics, getEquipmentRanking, getLabUsage, getOverview, getPeakHours } from '@/api/statistics'
import PageState from '@/components/PageState.vue'
import type { EquipmentUsage, Lab, LabUsage, Overview, PeakHour, StatisticsFilter } from '@/types/business'
import { downloadBlob, todayIso } from '@/utils/format'

echarts.use([AriaComponent, BarChart, CanvasRenderer, GridComponent, TooltipComponent])

const overview = ref<Overview | null>(null); const usage = ref<LabUsage[]>([]); const peaks = ref<PeakHour[]>([]); const equipment = ref<EquipmentUsage[]>([]); const labs = ref<Lab[]>([])
const loading = ref(true); const exporting = ref(false); const error = ref(''); const usageElement = ref<HTMLElement | null>(null); const peakElement = ref<HTMLElement | null>(null)
const filter = reactive<StatisticsFilter>({ from: todayIso(-30), to: todayIso(30) })
let usageChart: ECharts | null = null; let peakChart: ECharts | null = null

async function load(): Promise<void> { loading.value = true; error.value = ''; try { const [overviewData, usageData, peakData, equipmentData] = await Promise.all([getOverview(filter), getLabUsage(filter), getPeakHours(filter), getEquipmentRanking(filter)]); overview.value = overviewData; usage.value = usageData; peaks.value = peakData; equipment.value = equipmentData; await nextTick(); renderCharts() } catch (caught) { error.value = apiErrorMessage(caught, '统计数据加载失败。') } finally { loading.value = false } }
function renderCharts(): void { if (usageElement.value !== null) { usageChart?.dispose(); usageChart = echarts.init(usageElement.value); usageChart.setOption({ aria: { enabled: true, description: '各实验室利用率柱状图' }, tooltip: { trigger: 'axis' }, grid: { left: 42, right: 16, top: 24, bottom: 70 }, xAxis: { type: 'category', data: usage.value.map((item) => item.labCode), axisLabel: { rotate: 28 } }, yAxis: { type: 'value', max: 100, name: '利用率 %' }, series: [{ type: 'bar', data: usage.value.map((item) => item.utilizationRate), itemStyle: { color: '#0b6b5f' } }] }) } if (peakElement.value !== null) { peakChart?.dispose(); peakChart = echarts.init(peakElement.value); peakChart.setOption({ aria: { enabled: true, description: '大课课次预约量柱状图' }, tooltip: { trigger: 'axis' }, grid: { left: 42, right: 16, top: 24, bottom: 40 }, xAxis: { type: 'category', data: peaks.value.map((item) => item.periodName) }, yAxis: { type: 'value', minInterval: 1 }, series: [{ name: '总预约', type: 'bar', data: peaks.value.map((item) => item.total), itemStyle: { color: '#3f6b95' } }, { name: '有效预约', type: 'bar', data: peaks.value.map((item) => item.effective), itemStyle: { color: '#0b6b5f' } }] }) } }
async function exportCsv(): Promise<void> { exporting.value = true; try { downloadBlob(await exportStatistics(filter), `reservation-report-${filter.from}-${filter.to}.csv`); ElMessage.success('CSV 已导出') } catch (caught) { ElMessage.error(apiErrorMessage(caught, '导出失败。')) } finally { exporting.value = false } }
function resize(): void { usageChart?.resize(); peakChart?.resize() }
onMounted(async () => { try { labs.value = (await getLabs({ size: 100 })).items } catch { labs.value = [] } await load(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); usageChart?.dispose(); peakChart?.dispose() })
</script>

<template>
  <section class="page-stack">
    <header class="page-heading">
      <div>
        <p class="eyebrow">
          数据分析
        </p><h1>预约统计</h1><p>有效占用只计已通过、使用中和已完成预约；取消、驳回和爽约不计入利用率分子。</p>
      </div><el-button
        :loading="exporting"
        @click="exportCsv"
      >
        导出当前筛选 CSV
      </el-button>
    </header><section class="panel filter-panel">
      <el-form
        class="inline-form"
        @submit.prevent="load"
      >
        <el-form-item label="开始日期">
          <el-date-picker
            v-model="filter.from"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
          />
        </el-form-item><el-form-item label="结束日期">
          <el-date-picker
            v-model="filter.to"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
          />
        </el-form-item><el-form-item label="实验室">
          <el-select
            v-model="filter.labId"
            clearable
          >
            <el-option
              v-for="lab in labs"
              :key="lab.id"
              :label="lab.name"
              :value="lab.id"
            />
          </el-select>
        </el-form-item><el-form-item label="申请人类别">
          <el-select
            v-model="filter.applicantType"
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
          更新统计
        </el-button>
      </el-form>
    </section><PageState
      :loading="loading"
      :error="error"
      :empty="overview === null"
      @retry="load"
    >
      <div
        v-if="overview"
        class="metric-grid"
      >
        <article class="metric-card">
          <span>预约总量</span><strong>{{ overview.total }}</strong><small>学生 {{ overview.student.total }} · 教师 {{ overview.teacher.total }}</small>
        </article><article class="metric-card">
          <span>有效预约</span><strong>{{ overview.effective }}</strong><small>有效率 {{ overview.approvalRate }}%</small>
        </article><article class="metric-card">
          <span>取消</span><strong>{{ overview.cancelled }}</strong><small>取消率 {{ overview.cancellationRate }}%</small>
        </article><article class="metric-card">
          <span>爽约</span><strong>{{ overview.noShow }}</strong><small>爽约率 {{ overview.noShowRate }}%</small>
        </article>
      </div><div class="chart-grid">
        <section class="panel">
          <h2>实验室利用率</h2><div
            ref="usageElement"
            class="chart"
            role="img"
            aria-label="实验室利用率柱状图"
          /><div class="responsive-table">
            <el-table
              :data="usage"
              size="small"
            >
              <el-table-column
                prop="labCode"
                label="实验室"
              /><el-table-column
                prop="occupiedSlots"
                label="占用课次"
              /><el-table-column
                prop="availableSlots"
                label="可开放课次"
              /><el-table-column label="利用率">
                <template #default="scope">
                  {{ scope.row.utilizationRate }}%
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section><section class="panel">
          <h2>高峰课次</h2><div
            ref="peakElement"
            class="chart"
            role="img"
            aria-label="课次预约量柱状图"
          /><div class="compact-list">
            <div
              v-for="item in peaks"
              :key="item.periodNo"
              class="compact-row"
            >
              <span>{{ item.periodName }}<small>{{ item.startTime }}–{{ item.endTime }}</small></span><strong>{{ item.effective }} / {{ item.total }}</strong>
            </div>
          </div>
        </section>
      </div><section class="panel">
        <h2>设备使用排行</h2><div class="responsive-table">
          <el-table :data="equipment">
            <el-table-column
              prop="assetCode"
              label="资产编号"
            /><el-table-column
              prop="name"
              label="设备"
            /><el-table-column
              prop="labCode"
              label="实验室"
            /><el-table-column
              prop="quantity"
              label="申请数量"
            />
          </el-table>
        </div><el-empty
          v-if="equipment.length === 0"
          description="当前范围没有有效设备申请"
        />
      </section>
    </PageState>
  </section>
</template>
