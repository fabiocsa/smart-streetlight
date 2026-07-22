<template>
  <div class="light-trend-page">
    <div class="page-header">
      <h2>传感器历史趋势</h2>
      <div class="header-controls">
        <el-radio-group v-model="compareMode" @change="onModeChange">
          <el-radio-button :value="false">普通模式</el-radio-button>
          <el-radio-button :value="true">对比模式</el-radio-button>
        </el-radio-group>
        <el-select
          v-model="selectedMetric"
          style="width: 140px"
          @change="loadData"
        >
          <el-option
            v-for="m in metricOptions"
            :key="m.value"
            :label="m.label"
            :value="m.value"
          />
        </el-select>

        <!-- 普通模式：单选设备 -->
        <el-select
          v-if="!compareMode"
          v-model="selectedDevice"
          placeholder="全部设备"
          clearable
          style="width: 200px"
          @change="loadData"
        >
          <el-option label="全部设备" value="" />
          <el-option
            v-for="d in devices"
            :key="d.deviceId"
            :label="`${d.name} (${d.deviceId})`"
            :value="d.deviceId"
          />
        </el-select>

        <!-- 对比模式：多选设备 -->
        <el-select
          v-else
          v-model="selectedDevices"
          multiple
          placeholder="选择要对比的设备（最多6个）"
          style="width: 320px"
          collapse-tags
          collapse-tags-tooltip
          :max="6"
          @change="loadData"
        >
          <el-option
            v-for="d in devices"
            :key="d.deviceId"
            :label="`${d.name} (${d.deviceId})`"
            :value="d.deviceId"
          />
        </el-select>

        <el-radio-group v-model="selectedRange" @change="loadData">
          <el-radio-button value="24h">最近24小时</el-radio-button>
          <el-radio-button value="7d">最近7天</el-radio-button>
          <el-radio-button value="30d">最近30天</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 数据提示条 -->
    <el-alert
      v-if="demoMode"
      title="数据覆盖提示"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #default>
        当前时间范围内有效数据仅覆盖 {{ nonNullSlots }} 个时段（共 {{ totalSlots }} 个），
        无数据的时段在图表中显示为断点而非 0 值。
        {{ totalPoints > 0 ? `共有 ${totalPoints} 条原始数据点。` : '' }}
      </template>
    </el-alert>

    <!-- 普通模式：统计卡片 -->
    <template v-if="!compareMode">
      <el-row :gutter="16" class="stat-row">
        <el-col :span="6">
          <el-card shadow="hover" :body-style="{ padding: '16px 20px' }">
            <div class="mini-stat">
              <span class="mini-stat-label">平均值</span>
              <span class="mini-stat-value">{{ trendData.avg ?? '-' }} <small>{{ unit }}</small></span>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" :body-style="{ padding: '16px 20px' }">
            <div class="mini-stat">
              <span class="mini-stat-label">最大值</span>
              <span class="mini-stat-value" style="color: #F56C6C">{{ trendData.max ?? '-' }} <small>{{ unit }}</small></span>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" :body-style="{ padding: '16px 20px' }">
            <div class="mini-stat">
              <span class="mini-stat-label">最小值（非零）</span>
              <span class="mini-stat-value" style="color: #67C23A">{{ trendData.min ?? '-' }} <small>{{ unit }}</small></span>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" :body-style="{ padding: '16px 20px' }">
            <div class="mini-stat">
              <span class="mini-stat-label">数据点数</span>
              <span class="mini-stat-value">{{ totalPoints }} <small>条</small></span>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <!-- 对比模式：各设备统计对比 -->
    <template v-else>
      <el-row :gutter="16" class="stat-row" v-if="compareTrends.length > 0">
        <el-col :span="8" v-for="t in compareTrends" :key="t.deviceId">
          <el-card shadow="hover" :body-style="{ padding: '12px 16px' }">
            <div class="compare-stat">
              <div class="compare-stat-header">
                <span class="compare-stat-device" :style="{ color: getDeviceColor(t.deviceId) }">
                  {{ t.deviceName || t.deviceId }}
                </span>
                <span class="compare-stat-label">{{ metricLabel }}</span>
              </div>
              <div class="compare-stat-values">
                <div class="compare-stat-item">
                  <span class="label">均值</span>
                  <span class="value">{{ t.avg ?? '-' }}</span>
                </div>
                <div class="compare-stat-item">
                  <span class="label">最大</span>
                  <span class="value" style="color: #F56C6C">{{ t.max ?? '-' }}</span>
                </div>
                <div class="compare-stat-item">
                  <span class="label">最小</span>
                  <span class="value" style="color: #67C23A">{{ t.min ?? '-' }}</span>
                </div>
                <div class="compare-stat-item">
                  <span class="label">数据</span>
                  <span class="value">{{ t.totalPoints ?? 0 }}条</span>
                </div>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <!-- 主图表 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="chart-header">
          <strong>{{ compareMode ? '设备趋势对比' : metricLabel + '趋势图' }}</strong>
          <span class="chart-subtitle">
            {{ granularityLabel }}
            <template v-if="!compareMode && selectedDevice">
              · {{ getDeviceName(selectedDevice) }}
            </template>
            <template v-if="!compareMode && trendData.lastDataTime">
              · 最新数据: {{ formatTime(trendData.lastDataTime) }}
            </template>
            <template v-if="compareMode && compareTrends.length > 0">
              · {{ compareTrends.length }} 个设备对比
            </template>
          </span>
        </div>
      </template>
      <v-chart :option="chartOption" autoresize style="height: 420px" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import * as dashboardApi from '../api/dashboard'
import { useDeviceStore } from '../store/device'
import { formatTime } from '../utils/common'

use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, MarkLineComponent, CanvasRenderer])

const deviceStore = useDeviceStore()
const devices = ref([])
const compareMode = ref(false)
const selectedDevice = ref('')
const selectedDevices = ref([])
const selectedRange = ref('24h')
const selectedMetric = ref('illuminance')
const loading = ref(false)
const trendData = ref({})
const compareTrends = ref([])
const demoMode = ref(false)
const totalPoints = ref(0)
const nonNullSlots = ref(0)
const totalSlots = computed(() => selectedRange.value === '24h' ? 24 : selectedRange.value === '7d' ? 7 : 30)

// 对比颜色表（最多6种颜色循环）
const COMPARE_COLORS = ['#409EFF', '#F56C6C', '#67C23A', '#E6A23C', '#9B59B6', '#1ABC9C']

const metricOptions = [
  { label: '光照强度 (Lux)', value: 'illuminance' },
  { label: '温度 (°C)', value: 'temperature' },
  { label: '功率 (W)', value: 'power' }
]

const metricUnitsMap = {
  illuminance: 'Lux',
  lightIntensity: 'Lux',
  temperature: '°C',
  power: 'W'
}

const metricLabels = {
  illuminance: '光照强度',
  lightIntensity: '光照强度',
  temperature: '温度',
  power: '功率'
}

const unit = computed(() => metricUnitsMap[selectedMetric.value] || '')
const metricLabel = computed(() => metricLabels[selectedMetric.value] || selectedMetric.value)

const granularityLabel = computed(() => {
  return selectedRange.value === '24h' ? '按小时聚合' : '按天聚合'
})

/** 获取设备颜色（按索引分配） */
function getDeviceColor(deviceId) {
  const idx = compareTrends.value.findIndex(t => t.deviceId === deviceId)
  return COMPARE_COLORS[idx % COMPARE_COLORS.length]
}

/** 获取设备显示名称 */
function getDeviceName(deviceId) {
  const d = devices.value.find(d => d.deviceId === deviceId)
  return d ? `${d.name} (${deviceId})` : deviceId
}

// ECharts 图表配置
const chartOption = computed(() => {
  if (compareMode.value) {
    return buildCompareOption()
  }
  return buildSingleOption()
})

/** 普通模式：单线图 */
function buildSingleOption() {
  const labels = trendData.value.labels || []
  const values = trendData.value.values || []
  const avg = trendData.value.avg

  const markLineData = []
  if (avg != null) {
    markLineData.push({ yAxis: avg, label: { formatter: `均值 ${avg}`, fontSize: 11 } })
  }

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        if (p.value == null) return `${p.name}<br/>${metricLabel.value}: <b>无数据</b>`
        return `${p.name}<br/>${metricLabel.value}: <b>${p.value} ${unit.value}</b>`
      }
    },
    grid: { left: 60, right: 30, top: 20, bottom: selectedRange.value === '24h' ? 50 : 40 },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        rotate: selectedRange.value === '24h' ? 45 : selectedRange.value === '30d' ? 45 : 0,
        fontSize: 11
      },
      boundaryGap: false
    },
    yAxis: {
      type: 'value',
      name: unit.value
    },
    dataZoom: selectedRange.value === '30d'
      ? [{ type: 'slider', start: 0, end: 100, height: 20, bottom: 0 }]
      : [],
    series: [
      {
        name: metricLabel.value,
        type: 'line',
        data: values,
        smooth: true,
        connectNulls: false,
        symbol: selectedRange.value === '7d' || selectedRange.value === '30d' ? 'circle' : 'none',
        symbolSize: 4,
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(64,158,255,0.3)' },
              { offset: 1, color: 'rgba(64,158,255,0.02)' }
            ]
          }
        },
        lineStyle: { color: '#409EFF', width: 2 },
        itemStyle: { color: '#409EFF' },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#909399' },
          data: markLineData,
          precision: 1
        }
      }
    ]
  }
}

/** 对比模式：多线图 */
function buildCompareOption() {
  const series = compareTrends.value.map((t, i) => {
    const color = COMPARE_COLORS[i % COMPARE_COLORS.length]
    return {
      name: t.deviceName || t.deviceId,
      type: 'line',
      data: t.values || [],
      smooth: true,
      connectNulls: false,
      symbol: selectedRange.value === '7d' || selectedRange.value === '30d' ? 'circle' : 'none',
      symbolSize: 4,
      lineStyle: { color, width: 2 },
      itemStyle: { color },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: color + '33' },
            { offset: 1, color: color + '05' }
          ]
        }
      }
    }
  })

  // 取第一个设备的 labels 作为 x 轴
  const labels = compareTrends.value[0]?.labels || []

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        if (!params || params.length === 0) return ''
        let html = `<b>${params[0].name}</b><br/>`
        params.forEach(p => {
          html += `${p.marker} ${p.seriesName}: <b>${p.value} ${unit.value}</b><br/>`
        })
        return html
      }
    },
    legend: {
      data: compareTrends.value.map(t => t.deviceName || t.deviceId),
      bottom: 0,
      type: 'scroll'
    },
    grid: { left: 60, right: 30, top: 20, bottom: 50 },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        rotate: selectedRange.value === '24h' ? 45 : selectedRange.value === '30d' ? 45 : 0,
        fontSize: 11
      },
      boundaryGap: false
    },
    yAxis: {
      type: 'value',
      name: unit.value
    },
    dataZoom: selectedRange.value === '30d'
      ? [{ type: 'slider', start: 0, end: 100, height: 20, bottom: 30 }]
      : [],
    series
  }
}

/** 切换模式时清空数据 */
function onModeChange() {
  if (compareMode.value) {
    selectedDevice.value = ''
    selectedDevices.value = []
    compareTrends.value = []
  } else {
    selectedDevices.value = []
    trendData.value = {}
  }
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    if (compareMode.value) {
      if (selectedDevices.value.length < 2) {
        compareTrends.value = []
        demoMode.value = false
        totalPoints.value = 0
        return
      }
      const result = await dashboardApi.getSensorTrendCompare(
        selectedDevices.value,
        selectedMetric.value,
        selectedRange.value
      )
      compareTrends.value = result.trends || []
      demoMode.value = result.trends?.some(t => t.demoMode) || false
      totalPoints.value = result.trends?.reduce((sum, t) => sum + (t.totalPoints || 0), 0) || 0
    } else {
      const data = await dashboardApi.getSensorTrend(
        selectedDevice.value || undefined,
        selectedMetric.value,
        selectedRange.value
      )
      trendData.value = data
      demoMode.value = data.demoMode || false
      totalPoints.value = data.totalPoints || 0
      nonNullSlots.value = (data.values || []).filter(v => v != null).length
    }
  } catch {
    // 错误已在拦截器统一提示
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await deviceStore.fetchAll()
  devices.value = deviceStore.devices
  await loadData()
})
</script>

<style scoped>
.light-trend-page { padding-bottom: 24px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px; flex-wrap: wrap; gap: 12px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
.header-controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }

.stat-row { margin-top: 0; }
.mini-stat { display: flex; flex-direction: column; gap: 4px; }
.mini-stat-label { font-size: 13px; color: var(--text-muted); }
.mini-stat-value { font-size: 22px; font-weight: 700; }
.mini-stat-value small { font-size: 13px; font-weight: 400; color: var(--text-muted); }
.compare-stat { display: flex; flex-direction: column; gap: 8px; }
.compare-stat-header { display: flex; justify-content: space-between; align-items: center; }
.compare-stat-device { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.compare-stat-label { font-size: 12px; color: var(--text-muted); }
.compare-stat-values { display: flex; gap: 16px; flex-wrap: wrap; }
.compare-stat-item { display: flex; flex-direction: column; gap: 2px; min-width: 50px; }
.compare-stat-item .label { font-size: 11px; color: var(--text-muted); }
.compare-stat-item .value { font-size: 16px; font-weight: 700; color: var(--text-primary); }
.chart-header { display: flex; justify-content: space-between; align-items: center; }
.chart-subtitle { font-size: 13px; color: var(--text-muted); font-weight: 400; }
</style>
