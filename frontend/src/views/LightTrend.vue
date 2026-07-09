<template>
  <div class="light-trend-page">
    <div class="page-header">
      <h2>传感器历史趋势</h2>
      <div class="header-controls">
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
        <el-select
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
        <el-radio-group v-model="selectedRange" @change="loadData">
          <el-radio-button value="24h">最近24小时</el-radio-button>
          <el-radio-button value="7d">最近7天</el-radio-button>
          <el-radio-button value="30d">最近30天</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- Demo 模式提示条 -->
    <el-alert
      v-if="demoMode"
      title="演示数据提示"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #default>
        当前时间范围内实际数据量较少（仅 {{ totalPoints }} 条），图表中大部分值为 0 属于正常现象。
        随着模拟器持续运行，数据将逐渐丰富。
      </template>
    </el-alert>

    <!-- 统计卡片 -->
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

    <!-- 主图表 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="chart-header">
          <strong>{{ metricLabel }}趋势图</strong>
          <span class="chart-subtitle">
            {{ granularityLabel }} · {{ selectedDevice ? '单设备' : '全部设备' }}
            <template v-if="trendData.lastDataTime">
              · 最新数据: {{ formatTime(trendData.lastDataTime) }}
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
const selectedDevice = ref('')
const selectedRange = ref('24h')
const selectedMetric = ref('lightIntensity')
const loading = ref(false)
const trendData = ref({})
const demoMode = ref(false)
const totalPoints = ref(0)

const metricOptions = [
  { label: '光照强度 (Lux)', value: 'lightIntensity' },
  { label: '温度 (°C)', value: 'temperature' },
  { label: '湿度 (%)', value: 'humidity' },
  { label: '功率 (W)', value: 'power' }
]

const metricUnitsMap = {
  lightIntensity: 'Lux',
  temperature: '°C',
  humidity: '%',
  power: 'W'
}

const metricLabels = {
  lightIntensity: '光照强度',
  temperature: '温度',
  humidity: '湿度',
  power: '功率'
}

const unit = computed(() => metricUnitsMap[selectedMetric.value] || '')
const metricLabel = computed(() => metricLabels[selectedMetric.value] || selectedMetric.value)

const granularityLabel = computed(() => {
  return selectedRange.value === '24h' ? '按小时聚合' : '按天聚合'
})

const chartOption = computed(() => {
  const labels = trendData.value.labels || []
  const values = trendData.value.values || []
  const avg = trendData.value.avg || 0

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
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
      name: unit.value,
      min: 0
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
          data: [{ yAxis: avg, label: { formatter: `均值 ${avg}`, fontSize: 11 } }],
          precision: 1
        }
      }
    ]
  }
})

async function loadData() {
  loading.value = true
  try {
    const data = await dashboardApi.getSensorTrend(
      selectedDevice.value || undefined,
      selectedMetric.value,
      selectedRange.value
    )
    trendData.value = data
    demoMode.value = data.demoMode || false
    totalPoints.value = data.totalPoints || 0
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
.mini-stat-label { font-size: 13px; color: #909399; }
.mini-stat-value { font-size: 22px; font-weight: 700; }
.mini-stat-value small { font-size: 13px; font-weight: 400; color: #909399; }

.chart-header { display: flex; justify-content: space-between; align-items: center; }
.chart-subtitle { font-size: 13px; color: #909399; font-weight: 400; }
</style>
