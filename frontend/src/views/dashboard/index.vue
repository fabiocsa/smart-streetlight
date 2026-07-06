<template>
  <div class="dashboard">
    <!-- Stats Cards -->
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="12" :sm="8" :md="4" v-for="card in statCards" :key="card.label">
        <el-card
          shadow="hover"
          class="stat-card clickable"
          :style="{ borderTop: `3px solid ${card.color}` }"
          @click="handleStatClick(card.key)"
        >
          <div
            class="stat-value"
            :class="{ 'offline-flash': card.key === 'offlineDevices' && card.value > 0 }"
            :style="{ color: card.color }"
          >
            {{ card.value }}
          </div>
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
            <div class="card-header">
              <span>设备在线状态</span>
              <el-tag v-if="chartLoading.status" type="info" size="small" effect="plain">加载中</el-tag>
            </div>
          </template>
          <div class="chart-container" v-loading="chartLoading.status">
            <v-chart :option="deviceStatusChartOption" autoresize />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>灯光状态</span>
              <el-tag v-if="chartLoading.light" type="info" size="small" effect="plain">加载中</el-tag>
            </div>
          </template>
          <div class="chart-container" v-loading="chartLoading.light">
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
              <div class="card-header-right">
                <el-tag :type="wsStore.connected ? 'success' : 'danger'" size="small">
                  {{ wsStore.connected ? '实时' : '离线' }}
                </el-tag>
              </div>
            </div>
          </template>
          <div class="chart-container" style="height: 350px" v-loading="chartLoading.realtime">
            <v-chart :option="realtimeChartOption" autoresize />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Recent Alarms -->
    <el-row :gutter="20" class="charts-row">
      <el-col :span="24">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>最近告警</span>
              <el-button text size="small" @click="$router.push('/alarms')">
                查看全部 &gt;
              </el-button>
            </div>
          </template>
          <div v-if="alarmStore.loading" class="alarm-loading">
            <el-skeleton :rows="3" animated />
          </div>
          <div v-else-if="recentAlarms.length === 0" class="alarm-empty">
            <span style="font-size: 32px">✅</span>
            <span style="color: #909399; font-size: 13px; margin-top: 4px">当前无未处理告警，一切正常</span>
          </div>
          <el-table v-else :data="recentAlarms" stripe size="small" @row-click="handleAlarmRowClick">
            <el-table-column label="设备" prop="deviceId" width="100" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag :type="row.alarmType === 'offline' ? 'danger' : 'warning'" size="small">
                  {{ row.alarmType === 'offline' ? '离线' : '传感器异常' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="200" show-overflow-tooltip prop="content" />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
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

const router = useRouter()
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

const chartLoading = reactive({
  status: false,
  light: false,
  realtime: false
})

const statCards = computed(() => [
  { key: 'totalDevices', label: '总设备数', value: stats.value.totalDevices, color: '#409EFF', icon: Monitor },
  { key: 'onlineDevices', label: '在线设备', value: stats.value.onlineDevices, color: '#67C23A', icon: CircleCheck },
  { key: 'offlineDevices', label: '离线设备', value: stats.value.offlineDevices, color: '#F56C6C', icon: WarningFilled },
  { key: 'lightsOn', label: '灯光开启', value: stats.value.lightsOn, color: '#E6A23C', icon: Aim },
  { key: 'lightsOff', label: '灯光关闭', value: stats.value.lightsOff, color: '#909399', icon: Monitor },
  { key: 'pendingAlarms', label: '待处理告警', value: stats.value.pendingAlarms, color: '#F56C6C', icon: WarningFilled }
])

const recentAlarms = computed(() => {
  return alarmStore.alarms.filter(a => a.status === 'pending').slice(0, 5)
})

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
  chartLoading.status = true
  chartLoading.light = true
  chartLoading.realtime = true
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
  } finally {
    chartLoading.status = false
    chartLoading.light = false
    chartLoading.realtime = false
  }
}

function handleStatClick(key) {
  switch (key) {
    case 'totalDevices':
    case 'onlineDevices':
    case 'offlineDevices':
    case 'lightsOn':
    case 'lightsOff':
      router.push('/devices')
      break
    case 'pendingAlarms':
      router.push('/alarms')
      break
  }
}

function handleAlarmRowClick(row) {
  router.push('/alarms')
}

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

// Handle page visibility change for auto-refresh
function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    loadStats()
  }
}

onMounted(() => {
  loadStats()
  wsStore.on('SENSOR_DATA', handleSensorData)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onUnmounted(() => {
  wsStore.off('SENSOR_DATA', handleSensorData)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
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

.stat-card.clickable {
  cursor: pointer;
  transition: transform 0.2s;
}

.stat-card.clickable:hover {
  transform: translateY(-4px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
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

.offline-flash {
  animation: offlinePulse 2s ease-in-out infinite;
}

@keyframes offlinePulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
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

.card-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.alarm-loading {
  padding: 16px;
}

.alarm-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  gap: 4px;
}
</style>
