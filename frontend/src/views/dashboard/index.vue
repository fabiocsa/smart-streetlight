<template>
  <div class="dashboard">
    <!-- Stats Cards -->
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="12" :sm="8" :md="4" v-for="card in statCards" :key="card.label">
        <el-card shadow="hover" class="stat-card" :style="{ borderTop: `3px solid ${card.color}` }">
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
          <el-icon class="stat-icon" :style="{ color: card.color }" :size="32">
            <component :is="card.icon" />
          </el-icon>
        </el-card>
      </el-col>
    </el-row>

    <!-- Charts Row -->
    <el-row :gutter="20" class="charts-row">
      <el-col :xs="24" :md="12">
        <el-card shadow="hover">
          <template #header>
            <span>设备在线状态</span>
          </template>
          <div class="chart-container">
            <v-chart :option="deviceStatusChartOption" autoresize />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="hover">
          <template #header>
            <span>灯光状态</span>
          </template>
          <div class="chart-container">
            <v-chart :option="lightStatusChartOption" autoresize />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Real-time Sensor Data Chart -->
    <el-row :gutter="20" class="charts-row">
      <el-col :span="24">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>实时光照强度</span>
              <el-tag :type="wsStore.connected ? 'success' : 'danger'" size="small">
                {{ wsStore.connected ? '实时' : '离线' }}
              </el-tag>
            </div>
          </template>
          <div class="chart-container" style="height: 350px">
            <v-chart :option="realtimeChartOption" autoresize />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, reactive } from 'vue'
import { Monitor, CircleCheck, WarningFilled, Aim } from '@element-plus/icons-vue'
import { getDashboardStats } from '@/api/dashboard'
import { useDeviceStore } from '@/stores/device'
import { useAlarmStore } from '@/stores/alarm'
import { useWebSocketStore } from '@/stores/websocket'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { PieChart, LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([PieChart, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const deviceStore = useDeviceStore()
const alarmStore = useAlarmStore()
const wsStore = useWebSocketStore()

const stats = ref({
  totalDevices: 0,
  onlineDevices: 0,
  offlineDevices: 0,
  lightsOn: 0,
  lightsOff: 0,
  pendingAlarms: 0,
  todayAlarms: 0
})

const statCards = computed(() => [
  { label: '总设备数', value: stats.value.totalDevices, color: '#409EFF', icon: Monitor },
  { label: '在线设备', value: stats.value.onlineDevices, color: '#67C23A', icon: CircleCheck },
  { label: '离线设备', value: stats.value.offlineDevices, color: '#F56C6C', icon: WarningFilled },
  { label: '灯光开启', value: stats.value.lightsOn, color: '#E6A23C', icon: Aim },
  { label: '灯光关闭', value: stats.value.lightsOff, color: '#909399', icon: Monitor },
  { label: '待处理告警', value: stats.value.pendingAlarms, color: '#F56C6C', icon: WarningFilled }
])

const deviceStatusChartOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['40%', '65%'],
    avoidLabelOverlap: true,
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: true, formatter: '{b}\n{c}' },
    data: [
      { value: stats.value.onlineDevices, name: '在线', itemStyle: { color: '#67C23A' } },
      { value: stats.value.offlineDevices, name: '离线', itemStyle: { color: '#F56C6C' } }
    ]
  }]
}))

const lightStatusChartOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['40%', '65%'],
    avoidLabelOverlap: true,
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: true, formatter: '{b}\n{c}' },
    data: [
      { value: stats.value.lightsOn, name: '开启', itemStyle: { color: '#E6A23C' } },
      { value: stats.value.lightsOff, name: '关闭', itemStyle: { color: '#909399' } }
    ]
  }]
}))

// Real-time chart data
const timeLabels = ref([])
const intensityValues = ref([])
const MAX_POINTS = 30

const realtimeChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, bottom: 30, top: 10 },
  xAxis: {
    type: 'category',
    data: timeLabels.value,
    boundaryGap: false,
    axisLabel: { fontSize: 11 }
  },
  yAxis: {
    type: 'value',
    name: 'Lux',
    min: 0
  },
  series: [{
    type: 'line',
    data: intensityValues.value,
    smooth: true,
    showSymbol: false,
    lineStyle: { width: 2, color: '#409EFF' },
    areaStyle: {
      color: {
        type: 'linear',
        x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(64,158,255,0.3)' },
          { offset: 1, color: 'rgba(64,158,255,0.05)' }
        ]
      }
    }
  }]
}))

function handleSensorData(data, deviceId) {
  if (data && data.lightIntensity !== undefined) {
    const time = new Date(data.reportedAt || Date.now()).toLocaleTimeString()
    timeLabels.value.push(time)
    intensityValues.value.push(data.lightIntensity)

    if (timeLabels.value.length > MAX_POINTS) {
      timeLabels.value.shift()
      intensityValues.value.shift()
    }
  }
}

async function loadStats() {
  try {
    const data = await getDashboardStats()
    stats.value = { ...stats.value, ...data }
  } catch (e) {
    // Use store data as fallback
    stats.value.totalDevices = deviceStore.devices.length
    stats.value.onlineDevices = deviceStore.onlineCount
    stats.value.offlineDevices = deviceStore.offlineCount
    stats.value.lightsOn = deviceStore.lightsOnCount
    stats.value.lightsOff = deviceStore.lightsOffCount
  }
}

onMounted(() => {
  loadStats()
  wsStore.on('SENSOR_DATA', handleSensorData)
})

onUnmounted(() => {
  wsStore.off('SENSOR_DATA', handleSensorData)
})
</script>

<style scoped>
.dashboard {
  max-width: 1400px;
  margin: 0 auto;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  position: relative;
  overflow: hidden;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.stat-icon {
  position: absolute;
  right: 16px;
  top: 16px;
  opacity: 0.3;
}

.charts-row {
  margin-bottom: 20px;
}

.chart-container {
  height: 280px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
