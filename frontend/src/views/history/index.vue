<template>
  <div class="history-view">
    <!-- Filter Bar -->
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="filters" size="small">
        <el-form-item label="设备">
          <el-select
            v-model="filters.deviceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择设备（可多选）"
            style="width: 240px"
            @change="handleDeviceChange"
          >
            <el-option
              v-for="d in deviceStore.devices"
              :key="d.id"
              :label="d.name"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对比模式" v-if="filters.deviceIds.length > 1">
          <el-switch
            v-model="comparisonMode"
            active-text="叠加对比"
            inactive-text="分图对比"
          />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-radio-group v-model="filters.timePreset" @change="handlePresetChange">
            <el-radio-button value="1h">1小时</el-radio-button>
            <el-radio-button value="6h">6小时</el-radio-button>
            <el-radio-button value="24h">24小时</el-radio-button>
            <el-radio-button value="7d">7天</el-radio-button>
            <el-radio-button value="custom">自定义</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="filters.timePreset === 'custom'" label="起止时间">
          <el-date-picker
            v-model="customRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            @change="handleCustomRangeChange"
          />
        </el-form-item>
        <el-form-item label="粒度">
          <el-select v-model="filters.granularity" style="width: 120px">
            <el-option label="原始数据" value="raw" />
            <el-option label="5分钟聚合" value="5m" />
            <el-option label="1小时聚合" value="1h" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button v-if="filters.deviceIds.length > 0" @click="clearDevices">清空设备</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Comparison Stats -->
    <el-row :gutter="16" class="stats-row" v-if="chartSeriesData.length > 0">
      <el-col :xs="12" :sm="6" v-for="(stat, idx) in comparisonStats" :key="idx">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label" :style="{ color: stat.color }">{{ stat.label }}</div>
          <div class="stat-value" :style="{ color: stat.color }">{{ stat.value ?? '--' }}</div>
          <div class="stat-unit">{{ stat.unit }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Chart -->
    <el-card shadow="hover" class="chart-card">
      <template #header>
        <div class="chart-header">
          <span>
            光照强度趋势
            <el-tag v-if="filters.deviceIds.length > 1" type="warning" size="small" style="margin-left: 8px">
              对比模式 ({{ filters.deviceIds.length }} 台设备)
            </el-tag>
          </span>
          <div class="chart-header-right">
            <el-tag v-if="downsampled" type="info" size="small">
              已降采样 ({{ originalCount }}→{{ displayCount }})
            </el-tag>
            <el-button size="small" text @click="exportChart">
              <template #default>
                <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7h-2zm-6 .67l2.59-2.58L17 11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2v9.67z"/></svg>
                导出图片
              </template>
            </el-button>
          </div>
        </div>
      </template>
      <div class="chart-wrapper" style="height: 420px">
        <template v-if="chartLoading">
          <v-chart :option="loadingOption" autoresize style="height: 100%" />
        </template>
        <template v-else-if="noData">
          <div class="no-chart-data">
            <el-empty description="暂无数据">
              <span class="no-data-hint">请选择其他设备或时间段</span>
            </el-empty>
          </div>
        </template>
        <template v-else-if="chartError">
          <div class="chart-error">
            <el-result icon="error" title="数据加载失败">
              <template #extra>
                <el-button type="primary" size="small" @click="loadData">重试</el-button>
              </template>
            </el-result>
          </div>
        </template>
        <template v-else>
          <v-chart
            ref="chartRef"
            :option="chartOption"
            autoresize
            style="height: 100%"
            @click="handleChartClick"
          />
        </template>
      </div>
    </el-card>

    <!-- Data Table (collapsible) -->
    <el-card shadow="hover" class="table-card">
      <template #header>
        <div class="table-header">
          <span>原始数据</span>
          <el-button
            :icon="tableVisible ? 'ArrowUp' : 'ArrowDown'"
            text
            size="small"
            @click="tableVisible = !tableVisible"
          >
            {{ tableVisible ? '收起' : '展开' }}
          </el-button>
        </div>
      </template>
      <template v-if="tableVisible">
        <el-table
          :data="tableData"
          stripe
          size="small"
          v-loading="chartLoading"
          max-height="300"
        >
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">
              {{ formatTime(row.reportedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="设备" width="120">
            <template #default="{ row }">
              {{ getDeviceName(row.deviceId) }}
            </template>
          </el-table-column>
          <el-table-column prop="lightIntensity" label="光照强度(Lux)" width="140" />
        </el-table>
        <div class="pagination-wrapper" v-if="totalElements > pageSize">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="pageSize"
            :total="totalElements"
            layout="total, prev, pager, next"
            @current-change="handlePageChange"
            small
          />
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useDeviceStore } from '@/stores/device'
import { getSensorHistory, getSensorStats } from '@/api/sensor'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, CanvasRenderer])

const deviceStore = useDeviceStore()

const filters = reactive({
  deviceIds: [],
  timePreset: '1h',
  granularity: 'raw',
  start: '',
  end: ''
})

const comparisonMode = ref(true) // true=叠加, false=分图(not implemented yet, default to overlay)
const customRange = ref(null)
const chartLoading = ref(false)
const chartError = ref(false)
const noData = ref(false)
const downsampled = ref(false)
const originalCount = ref(0)
const displayCount = ref(0)
const tableVisible = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)

// Chart data per device
const chartSeriesData = ref([]) // [{deviceId, name, time[], values[]}]
const comparisonStats = ref([])
const tableData = ref([])
const chartRef = ref(null)

// Chart colors for multiple devices
const DEVICE_COLORS = [
  '#409EFF', '#F56C6C', '#67C23A', '#E6A23C', '#9B59B6',
  '#1ABC9C', '#E74C3C', '#3498DB', '#F39C12', '#2ECC71',
  '#8E44AD', '#16A085', '#D35400', '#2980B9', '#27AE60'
]

// Select first device on mount or when devices load
onMounted(() => {
  tryAutoSelectDevice()
})

// Auto load when device selection changes
watch(() => filters.deviceIds.length, () => {
  if (filters.deviceIds.length > 0) loadData()
})

// Watch for devices to finish loading, then auto-select first
watch(() => deviceStore.devices.length, (count) => {
  if (count > 0 && filters.deviceIds.length === 0) {
    tryAutoSelectDevice()
  }
})

function tryAutoSelectDevice() {
  if (deviceStore.devices.length > 0 && filters.deviceIds.length === 0) {
    filters.deviceIds = [deviceStore.devices[0].deviceId]
  }
}

const loadingOption = computed(() => ({
  title: {
    text: '加载中…',
    left: 'center',
    top: 'center',
    textStyle: { fontSize: 14, color: '#909399' }
  }
}))

const chartOption = computed(() => {
  if (chartSeriesData.value.length === 0) return {}

  const thresholdOn = 50
  const thresholdOff = 100

  // Build series from chartSeriesData
  const series = []
  const legendData = []

  chartSeriesData.value.forEach((sd, idx) => {
    const color = DEVICE_COLORS[idx % DEVICE_COLORS.length]
    const name = sd.name || sd.deviceId
    legendData.push({ name, icon: 'line' })

    // Light intensity line
    series.push({
      name,
      type: 'line',
      data: sd.values,
      smooth: true,
      showSymbol: sd.values.length < 50,
      symbolSize: 3,
      lineStyle: { width: 2, color },
      itemStyle: { color },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: color + '4D' },
            { offset: 1, color: color + '0D' }
          ]
        }
      }
    })

    // Only show threshold lines for the first device (or when single device)
    if (idx === 0 || chartSeriesData.value.length === 1) {
      series.push({
        name: '开灯阈值',
        type: 'line',
        data: sd.time.map(() => thresholdOn),
        lineStyle: { type: 'dashed', color: '#F56C6C', width: 2 },
        symbol: 'none',
        z: 2,
        markLine: {
          silent: true,
          label: { show: true, formatter: '开灯 {c} Lux', color: '#F56C6C', fontSize: 11, position: 'insideEndTop' }
        }
      })

      series.push({
        name: '关灯阈值',
        type: 'line',
        data: sd.time.map(() => thresholdOff),
        lineStyle: { type: 'dashed', color: '#67C23A', width: 2 },
        symbol: 'none',
        z: 2,
        markLine: {
          silent: true,
          label: { show: true, formatter: '关灯 {c} Lux', color: '#67C23A', fontSize: 11, position: 'insideEndTop' }
        }
      })
    }
  })

  // Add markArea for "should turn on" only for first device
  if (chartSeriesData.value.length > 0) {
    const first = chartSeriesData.value[0]
    series.unshift({
      name: '光照强度',
      type: 'line',
      data: first.values,
      smooth: true,
      showSymbol: first.values.length < 50,
      symbolSize: 3,
      lineStyle: { width: 2, color: DEVICE_COLORS[0] },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(64,158,255,0.3)' },
            { offset: 1, color: 'rgba(64,158,255,0.05)' }
          ]
        }
      },
      markArea: {
        silent: true,
        data: [
          [{
            yAxis: 50,
            itemStyle: { color: 'rgba(245,108,108,0.06)' }
          }, {
            yAxis: 0
          }]
        ],
        label: {
          show: true,
          position: 'insideBottomRight',
          color: '#F56C6C',
          fontSize: 11,
          formatter: '应开灯时段'
        }
      }
    })

    // Remove the duplicate first device series if it was already added
    if (series.length > 3) {
      // Remove the first duplicate (the one without markArea)
      series.splice(1, 1)
    }
  }

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        if (!params || params.length === 0) return ''
        let html = `<div style="font-weight:600;margin-bottom:4px">${params[0].axisValue}</div>`
        params.forEach(p => {
          const color = p.color || p.seriesColor || '#333'
          html += `<div style="display:flex;align-items:center;gap:4px;margin:2px 0">
            <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${color}"></span>
            <span style="flex:1">${p.seriesName}</span>
            <span style="font-weight:${p.seriesName.includes('阈值') ? '400' : '600'}">${p.value} Lux</span>
          </div>`
        })
        return html
      }
    },
    legend: {
      data: legendData,
      bottom: 0,
      type: 'scroll'
    },
    grid: { left: 50, right: 20, bottom: 50, top: 10 },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, bottom: 35, height: 20 }
    ],
    xAxis: {
      type: 'category',
      data: chartSeriesData.value.length > 0 ? chartSeriesData.value[0].time : [],
      boundaryGap: false,
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: 'Lux',
      min: 0,
      max: 2000,
      splitLine: { lineStyle: { type: 'dashed', color: '#e8e8e8' } }
    },
    series
  }
})

function getDeviceName(deviceId) {
  const d = deviceStore.devices.find(d => d.deviceId === deviceId)
  return d ? d.name : deviceId
}

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

function handleDeviceChange() {
  // loadData is triggered by watch on length
}

function clearDevices() {
  filters.deviceIds = []
  chartSeriesData.value = []
  comparisonStats.value = []
  noData.value = true
}

function handlePresetChange(preset) {
  if (preset !== 'custom') {
    loadData()
  }
}

function handleCustomRangeChange(range) {
  if (range) {
    filters.start = range[0]
    filters.end = range[1]
    loadData()
  }
}

// Simple LTTB downsampling
function lttb(data, threshold) {
  if (data.length <= threshold) return data
  const result = [data[0]]
  const bucketSize = (data.length - 2) / (threshold - 2)
  let a = 0
  for (let i = 0; i < threshold - 2; i++) {
    const center = Math.floor((i + 1) * bucketSize) + 1
    const points = []
    for (let j = Math.floor(i * bucketSize) + 1; j <= Math.floor((i + 1) * bucketSize) + 1; j++) {
      if (j < data.length) points.push(data[j])
    }
    if (points.length === 0) continue
    const avgNextX = points.reduce((s, p) => s + (new Date(p.reportedAt).getTime()), 0) / points.length
    const avgNextY = points.reduce((s, p) => s + p.lightIntensity, 0) / points.length
    let maxArea = -1
    let maxIdx = center
    for (let j = Math.floor(i * bucketSize) + 1; j <= Math.floor((i + 1) * bucketSize) + 1; j++) {
      if (j >= data.length) break
      const area = Math.abs(
        (new Date(data[a].reportedAt).getTime() - avgNextX) * (data[j].lightIntensity - avgNextY) -
        (new Date(data[a].reportedAt).getTime() - new Date(data[j].reportedAt).getTime()) * (data[a].lightIntensity - avgNextY)
      )
      if (area > maxArea) {
        maxArea = area
        maxIdx = j
      }
    }
    result.push(data[maxIdx])
    a = maxIdx
  }
  result.push(data[data.length - 1])
  return result
}

async function loadData() {
  if (filters.deviceIds.length === 0) {
    ElMessage.warning('请选择至少一个设备')
    return
  }

  chartLoading.value = true
  chartError.value = false
  noData.value = false
  downsampled.value = false
  chartSeriesData.value = []
  comparisonStats.value = []

  const now = new Date()
  let start, end

  if (filters.timePreset === 'custom' && filters.start && filters.end) {
    start = new Date(filters.start)
    end = new Date(filters.end)
  } else {
    end = now
    start = new Date(now)
    const hours = parseInt(filters.timePreset)
    start.setHours(start.getHours() - hours)
  }

  try {
    // Load data for each device in parallel
    const promises = filters.deviceIds.map(async (deviceId) => {
      try {
        const [historyData, statsData] = await Promise.all([
          getSensorHistory(deviceId, start.toISOString(), end.toISOString()),
          getSensorStats(deviceId, start.toISOString(), end.toISOString())
        ])
        return { deviceId, history: Array.isArray(historyData) ? historyData : [], stats: statsData }
      } catch (e) {
        return { deviceId, history: [], stats: null }
      }
    })

    const results = await Promise.all(promises)
    const allData = []
    let hasAnyData = false
    let totalOriginal = 0
    let totalDisplay = 0
    let needsDownsample = false

    results.forEach((r, idx) => {
      const deviceName = getDeviceName(r.deviceId)
      const data = r.history

      if (data.length > 0) {
        hasAnyData = true
        totalOriginal += data.length

        // Downsample if > 1000 points
        let displayData = data
        if (data.length > 1000) {
          displayData = lttb(data, 1000)
          needsDownsample = true
          totalDisplay += displayData.length
        } else {
          totalDisplay += data.length
        }

        chartSeriesData.value.push({
          deviceId: r.deviceId,
          name: deviceName,
          time: displayData.map(d => new Date(d.reportedAt).toLocaleString('zh-CN')),
          values: displayData.map(d => d.lightIntensity)
        })

        allData.push(...data)

        // Per-device stats
        if (r.stats) {
          const color = DEVICE_COLORS[idx % DEVICE_COLORS.length]
          comparisonStats.value.push({
            label: `${deviceName} 平均值`,
            value: r.stats.avg?.toFixed(1) ?? '--',
            color,
            unit: 'Lux'
          })
          comparisonStats.value.push({
            label: `${deviceName} 最大值`,
            value: r.stats.max?.toFixed(1) ?? '--',
            color,
            unit: 'Lux'
          })
        } else {
          const vals = data.map(d => d.lightIntensity)
          if (vals.length > 0) {
            const color = DEVICE_COLORS[idx % DEVICE_COLORS.length]
            comparisonStats.value.push({
              label: `${deviceName} 平均值`,
              value: (vals.reduce((s, v) => s + v, 0) / vals.length).toFixed(1),
              color,
              unit: 'Lux'
            })
            comparisonStats.value.push({
              label: `${deviceName} 最大值`,
              value: Math.max(...vals).toFixed(1),
              color,
              unit: 'Lux'
            })
          }
        }
      }
    })

    if (!hasAnyData) {
      noData.value = true
      return
    }

    originalCount.value = totalOriginal
    displayCount.value = totalDisplay
    downsampled.value = needsDownsample

    // Table data: mix all devices' data, sorted by time
    tableData.value = allData
      .sort((a, b) => new Date(b.reportedAt).getTime() - new Date(a.reportedAt).getTime())
      .slice(0, 100)
    totalElements.value = allData.length

  } catch (e) {
    console.error('Failed to load history:', e)
    chartError.value = true
  } finally {
    chartLoading.value = false
  }
}

function handlePageChange(page) {
  currentPage.value = page
}

function handleChartClick(params) {
  if (!params || params.dataIndex === undefined) return
  const idx = params.dataIndex
  const seriesName = params.seriesName
  let valueText = ''
  chartSeriesData.value.forEach(sd => {
    if (sd.name === seriesName || !seriesName) {
      if (idx >= 0 && idx < sd.values.length) {
        valueText += `${sd.name}: ${sd.values[idx]} Lux, `
      }
    }
  })
  if (valueText) {
    ElMessage.info({
      message: `时刻: ${chartSeriesData.value[0]?.time[idx] || ''}\n${valueText.slice(0, -2)}`,
      duration: 3000
    })
  }
}

function exportChart() {
  const chart = chartRef.value
  if (!chart) {
    ElMessage.warning('图表尚未加载')
    return
  }
  try {
    const deviceLabel = filters.deviceIds.length > 1
      ? `${filters.deviceIds.length}台设备对比`
      : filters.deviceIds[0] || 'unknown'
    const url = chart.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#fff'
    })
    const link = document.createElement('a')
    link.href = url
    link.download = `光照趋势_${deviceLabel}_${new Date().toISOString().slice(0, 10)}.png`
    link.click()
  } catch (e) {
    ElMessage.error('导出失败')
  }
}
</script>

<style scoped>
.history-view {
  max-width: 1400px;
  margin: 0 auto;
}

.filter-card {
  margin-bottom: 16px;
}

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
  margin-bottom: 12px;
}

.stat-label {
  font-size: 11px;
  margin-bottom: 4px;
  font-weight: 500;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-unit {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 2px;
}

.chart-card {
  margin-bottom: 16px;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chart-wrapper {
  position: relative;
}

.no-chart-data {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  min-height: 300px;
}

.no-data-hint {
  font-size: 12px;
  color: #c0c4cc;
}

.chart-error {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 300px;
}

.table-card {
  margin-bottom: 16px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pagination-wrapper {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
