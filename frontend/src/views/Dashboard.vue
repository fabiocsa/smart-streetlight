<template>
  <div class="dashboard">
    <div class="page-header">
      <h2>仪表盘</h2>
      <el-button type="primary" @click="refreshAll" :loading="loading">
        <el-icon><Refresh /></el-icon> 刷新数据
      </el-button>
    </div>

    <!-- 统计卡片（可点击跳转） -->
    <el-row :gutter="16" class="stat-cards">
      <el-col :xs="12" :sm="8" :md="4" v-for="card in statCards" :key="card.label">
        <el-card
          shadow="hover"
          :body-style="{ padding: '20px' }"
          :class="{ clickable: !!card.to }"
          @click="navigateTo(card.to)"
        >
          <div class="stat-card">
            <div class="stat-icon" :style="{ background: card.color }">
              <el-icon :size="24"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ card.value }}</div>
              <div class="stat-label">{{ card.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 设备状态分布 -->
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><strong>设备状态分布</strong></template>
          <v-chart :option="deviceStatusOption" autoresize style="height: 280px" />
        </el-card>
      </el-col>

      <!-- 传感器趋势 -->
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="chart-header">
              <strong>传感器趋势</strong>
              <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                <!-- 时间范围切换 -->
                <el-radio-group v-model="trendRange" size="small" @change="loadSensorTrend">
                  <el-radio-button value="24h">24小时</el-radio-button>
                  <el-radio-button value="7d">近7天</el-radio-button>
                  <el-radio-button value="30d">近30天</el-radio-button>
                </el-radio-group>
                <el-select v-model="trendMetric" size="small" style="width: 130px" @change="loadSensorTrend">
                  <el-option
                    v-for="m in metricOptions"
                    :key="m.value"
                    :label="m.label"
                    :value="m.value"
                  />
                </el-select>
                <el-select v-model="lightTrendDevice" placeholder="全部设备" clearable size="small" style="width: 160px" @change="loadSensorTrend">
                  <el-option label="全部设备" value="" />
                  <el-option v-for="d in devices" :key="d.deviceId" :label="d.name" :value="d.deviceId" />
                </el-select>
              </div>
            </div>
          </template>
          <v-chart :option="lightTrendOption" autoresize style="height: 280px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 第二行 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 近7日告警趋势 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近7日告警趋势</strong></template>
          <v-chart :option="alarmTrendOption" autoresize style="height: 260px" />
        </el-card>
      </el-col>

      <!-- 告警级别分布 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>本月告警级别分布</strong></template>
          <v-chart :option="alarmSeverityOption" autoresize style="height: 260px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 第三行 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 近期告警 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近期告警</strong></template>
          <el-table :data="recentAlarms" size="small" max-height="300" style="width: 100%">
            <el-table-column prop="deviceId" label="设备" width="90" />
            <el-table-column prop="content" label="告警内容" min-width="180" show-overflow-tooltip />
            <el-table-column label="级别" width="70">
              <template #default="{ row }">
                <el-tag :type="severityTag(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.status === 'PENDING' ? 'danger' : 'info'" size="small">
                  {{ row.status === 'PENDING' ? '待处理' : '已处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 近期控制日志 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近期控制日志</strong></template>
          <el-table :data="recentControls" size="small" max-height="300" style="width: 100%">
            <el-table-column prop="deviceId" label="设备" width="90" />
            <el-table-column label="指令" width="70">
              <template #default="{ row }">
                <el-tag :type="row.command === 'on' ? 'warning' : 'info'" size="small">
                  {{ row.command === 'on' ? '开灯' : '关灯' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="70">
              <template #default="{ row }">
                {{ row.source === 'auto' ? '自动' : '手动' }}
              </template>
            </el-table-column>
            <el-table-column label="结果" width="70">
              <template #default="{ row }">
                <el-tag :type="row.result === 'success' ? 'success' : 'danger'" size="small">
                  {{ row.result === 'success' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 各设备最新传感器数据 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <strong>设备最新传感器数据</strong>
      </template>
      <el-table :data="latestSensorData" size="small" max-height="280" style="width: 100%"
        :row-class-name="sensorRowClass">
        <el-table-column prop="deviceId" label="设备ID" width="100" />
        <el-table-column label="设备名称" width="120">
          <template #default="{ row }">
            <span>{{ deviceNameMap[row.deviceId] || row.deviceId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="光照强度" width="120">
          <template #default="{ row }">
            <span v-if="row.lightIntensity != null" style="font-weight: 600">{{ fmtNum(row.lightIntensity) }} Lux</span>
            <span v-else style="color: #909399; font-size: 12px">-</span>
          </template>
        </el-table-column>
        <el-table-column label="其他指标" min-width="260">
          <template #default="{ row }">
            <div style="display: flex; gap: 8px; flex-wrap: wrap">
              <el-tag v-if="row.data?.temperature != null" size="small" effect="plain">
                🌡 {{ fmtNum(row.data.temperature) }}°C
              </el-tag>
              <el-tag v-if="row.data?.humidity != null" size="small" effect="plain" type="info">
                💧 {{ fmtNum(row.data.humidity) }}%
              </el-tag>
              <el-tag v-if="row.data?.voltage != null" size="small" effect="plain" type="warning">
                ⚡ {{ fmtNum(row.data.voltage) }}V
              </el-tag>
              <el-tag v-if="row.data?.power != null" size="small" effect="plain">
                🔌 {{ fmtNum(row.data.power) }}W
              </el-tag>
              <span v-if="!row.data?.temperature && !row.data?.humidity && !row.data?.voltage && !row.data?.power" style="color: #909399; font-size: 12px">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="上报时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.reportedAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import * as dashboardApi from '../api/dashboard'
import { useDeviceStore } from '../store/device'
import { useAuthStore } from '../stores/authStore'
import { formatTime } from '../utils/common'

use([PieChart, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, CanvasRenderer])

const router = useRouter()
const deviceStore = useDeviceStore()
const authStore = useAuthStore()
const loading = ref(false)

// 指标选项
const metricOptions = [
  { label: '光照 (Lux)', value: 'lightIntensity' },
  { label: '温度 (°C)', value: 'temperature' },
  { label: '功率 (W)', value: 'power' }
]

const metricUnits = {
  lightIntensity: 'Lux',
  temperature: '°C',
  power: 'W'
}

// 数据
const stats = ref({})
const devices = ref([])
const latestSensorData = ref([])
const sensorTrend = ref({ labels: [], values: [] })
const alarmStats = ref({ days: [], dailyCounts: [], criticalCount: 0, warningCount: 0, infoCount: 0 })
const recentAlarms = ref([])
const recentControls = ref([])
const lightTrendDevice = ref('')
const trendMetric = ref('lightIntensity')
const trendRange = ref('24h')
const latestSensorType = ref('')

/** 设备名称映射（用于最新传感器表格） */
const deviceNameMap = computed(() => {
  const map = {}
  devices.value.forEach(d => { map[d.deviceId] = d.name })
  return map
})

// 统计卡片（带路由跳转）
const statCards = computed(() => [
  { label: '设备总数', value: stats.value.totalDevices ?? '-', icon: 'Cpu', color: '#409EFF', to: '/devices' },
  { label: '在线设备', value: stats.value.onlineDevices ?? '-', icon: 'CircleCheck', color: '#67C23A', to: '/devices' },
  { label: '离线设备', value: stats.value.offlineDevices ?? '-', icon: 'CircleClose', color: '#F56C6C', to: '/devices' },
  { label: '已开灯', value: stats.value.lightsOn ?? '-', icon: 'Sunny', color: '#E6A23C', to: '/devices' },
  { label: '待处理告警', value: stats.value.pendingAlarms ?? '-', icon: 'Bell', color: '#F56C6C', to: authStore.isAdmin ? '/alarms' : null },
  { label: '今日数据量', value: stats.value.todayDataPoints ?? '-', icon: 'DataLine', color: '#909399', to: null }
])

function navigateTo(to) {
  if (to) router.push(to)
}

// 设备状态饼图
const deviceStatusOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['45%', '75%'],
    center: ['50%', '45%'],
    avoidLabelOverlap: false,
    itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
    label: { show: true, formatter: '{b}: {c}' },
    data: [
      { name: '在线', value: stats.value.onlineDevices || 0, itemStyle: { color: '#67C23A' } },
      { name: '离线', value: stats.value.offlineDevices || 0, itemStyle: { color: '#F56C6C' } },
      { name: '已开灯', value: stats.value.lightsOn || 0, itemStyle: { color: '#E6A23C' } },
      { name: '已关灯', value: stats.value.lightsOff || 0, itemStyle: { color: '#909399' } }
    ]
  }]
}))

// 传感器趋势图表
const unit = computed(() => metricUnits[trendMetric.value] || '')
const lightTrendOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: (params) => {
      const p = params[0]
      if (p.value == null) return `${p.name}<br/>${trendMetric.value}: <b>无数据</b>`
      return `${p.name}<br/>${trendMetric.value}: <b>${p.value} ${unit.value}</b>`
    }
  },
  grid: { left: 55, right: 20, top: 10, bottom: 30 },
  xAxis: {
    type: 'category', data: sensorTrend.value.labels || [],
    axisLabel: { rotate: trendRange.value === '24h' ? 45 : 0, fontSize: 11 }
  },
  yAxis: { type: 'value', name: unit.value },
  series: [{
    type: 'line',
    data: sensorTrend.value.values || [],
    smooth: true,
    connectNulls: false,
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(64,158,255,0.35)' },
          { offset: 1, color: 'rgba(64,158,255,0.02)' }
        ]
      }
    },
    lineStyle: { color: '#409EFF', width: 2 },
    itemStyle: { color: '#409EFF' }
  }]
}))

// 告警趋势
const alarmTrendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, top: 10, bottom: 30 },
  xAxis: { type: 'category', data: alarmStats.value.days || [] },
  yAxis: { type: 'value', name: '告警数', minInterval: 1 },
  series: [{
    type: 'bar',
    data: alarmStats.value.dailyCounts || [],
    itemStyle: { color: '#F56C6C', borderRadius: [4, 4, 0, 0] }
  }]
}))

// 告警级别饼图
const alarmSeverityOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: '70%',
    center: ['50%', '45%'],
    itemStyle: { borderRadius: 3, borderColor: '#fff', borderWidth: 2 },
    label: { formatter: '{b}: {c} 条' },
    data: [
      { name: '严重', value: alarmStats.value.criticalCount || 0, itemStyle: { color: '#F56C6C' } },
      { name: '警告', value: alarmStats.value.warningCount || 0, itemStyle: { color: '#E6A23C' } },
      { name: '提示', value: alarmStats.value.infoCount || 0, itemStyle: { color: '#409EFF' } }
    ]
  }]
}))

// 辅助方法
function severityTag(sev) {
  const map = { CRITICAL: 'danger', WARNING: 'warning', INFO: 'info' }
  return map[sev] || 'info'
}
function severityLabel(sev) {
  const map = { CRITICAL: '严重', WARNING: '警告', INFO: '提示' }
  return map[sev] || sev
}

/** 数值格式化：保留1位小数，空值显示 - */
function fmtNum(val) {
  if (val == null || val === '') return '-'
  const n = Number(val)
  if (isNaN(n)) return '-'
  return n.toFixed(1)
}

/** 传感器表格行样式：离线设备标灰 */
function sensorRowClass({ row }) {
  return row.deviceId && !devices.value.find(d => d.deviceId === row.deviceId && d.status === 'online')
    ? 'offline-row'
    : ''
}

function sensorTypeTag(type) {
  const map = { light: '', temperature: 'danger', humidity: 'info', power: 'warning' }
  return map[type] || ''
}
function sensorTypeLabel(type) {
  const map = { light: '光照', temperature: '温度', humidity: '湿度', power: '功率' }
  return map[type] || type || '未知'
}

// 数据加载
async function loadStats() {
  try { stats.value = await dashboardApi.getStats() } catch { /* */ }
}
async function loadLatestSensorData() {
  try {
    latestSensorData.value = await dashboardApi.getLatestSensorData()
    dedupSensorRows()
  } catch { /* */ }
}

/** 去重：同 deviceId 只保留最新一行，合并其他行数据 */
function dedupSensorRows() {
  const seen = {}
  const merged = []
  for (const row of latestSensorData.value) {
    if (seen[row.deviceId]) {
      // 合并到已有行
      const existing = seen[row.deviceId]
      if (row.data) Object.assign(existing.data, row.data)
      if (row.lightIntensity != null && existing.lightIntensity == null) {
        existing.lightIntensity = row.lightIntensity
      }
      if (row.reportedAt > existing.reportedAt) {
        existing.reportedAt = row.reportedAt
      }
    } else {
      seen[row.deviceId] = row
      merged.push(row)
    }
  }
  latestSensorData.value = merged
}
async function loadLatestByType() {
  try {
    if (latestSensorType.value) {
      latestSensorData.value = await dashboardApi.getLatestSensorDataByType(latestSensorType.value)
    } else {
      latestSensorData.value = await dashboardApi.getLatestSensorData()
    }
  } catch { /* */ }
}
async function loadSensorTrend() {
  try {
    sensorTrend.value = await dashboardApi.getSensorTrend(
      lightTrendDevice.value || undefined,
      trendMetric.value,
      trendRange.value
    )
  } catch { /* */ }
}
async function loadAlarmStats() {
  try { alarmStats.value = await dashboardApi.getAlarmStats() } catch { /* */ }
}
async function loadRecentAlarms() {
  try { recentAlarms.value = await dashboardApi.getRecentAlarms() } catch { /* */ }
}
async function loadRecentControls() {
  try { recentControls.value = await dashboardApi.getRecentControls() } catch { /* */ }
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([
      loadStats(), loadLatestSensorData(),
      loadSensorTrend(), loadAlarmStats(),
      loadRecentAlarms(), loadRecentControls(),
      deviceStore.fetchAll()
    ])
    devices.value = deviceStore.devices
    ElMessage.success('数据已刷新')
  } finally {
    loading.value = false
  }
}

// ==================== WebSocket 实时数据更新 ====================
let ws = null

function connectWs() {
  if (ws && ws.readyState === WebSocket.OPEN) return
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const url = `${proto}//${location.host}/ws/monitor`
  try {
    ws = new WebSocket(url)
    ws.onopen = () => { reconnectAttempts = 0 }
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data)

        if (msg.type === 'SENSOR_DATA' && msg.deviceId) {
          // 实时更新"设备最新传感器数据"表：按 deviceId 合并，同一设备只保留一行
          const idx = latestSensorData.value.findIndex(
            d => d.deviceId === msg.deviceId
          )
          if (idx >= 0) {
            // 合并新数据到已有行
            const existing = latestSensorData.value[idx]
            if (msg.data) Object.assign(existing.data, msg.data)
            existing.reportedAt = msg.reportedAt
            if (msg.sensorType === 'light') {
              existing.lightIntensity = msg.data?.illuminance ?? msg.data?.lightIntensity
            }
          } else {
            // 新设备：创建行
            latestSensorData.value.unshift({
              deviceId: msg.deviceId,
              data: msg.data || {},
              reportedAt: msg.reportedAt,
              lightIntensity: msg.sensorType === 'light'
                ? (msg.data?.illuminance ?? msg.data?.lightIntensity)
                : null
            })
          }
          dedupSensorRows()  // 清理可能的重复行
        }

        if (msg.type === 'DEVICE_STATUS' && msg.deviceId) {
          // 更新设备状态统计
          const device = devices.value.find(d => d.deviceId === msg.deviceId)
          if (device && msg.data) {
            if (msg.data.status) device.status = msg.data.status
            if (msg.data.lightStatus) device.lightStatus = msg.data.lightStatus
          }
          // 重新计算统计卡片
          loadStats()
        }

        if (msg.type === 'NEW_ALARM') {
          loadRecentAlarms()
          loadAlarmStats()
        }

        if (msg.type === 'CONTROL_RESULT') {
          loadRecentControls()
        }
      } catch { /* ignore */ }
    }
    ws.onclose = () => { ws = null; scheduleReconnect() }
    ws.onerror = () => { ws?.close() }
  } catch { /* ignore */ }
}

// WebSocket 重连（指数退避，最大重试 10 次）
let reconnectAttempts = 0
let reconnectTimer = null
const MAX_RECONNECT = 10
const BASE_DELAY = 2000

function scheduleReconnect() {
  if (reconnectAttempts >= MAX_RECONNECT) {
    console.warn('[WS] 已达最大重连次数，停止重连')
    return
  }
  const delay = Math.min(BASE_DELAY * Math.pow(2, reconnectAttempts), 60000)
  reconnectAttempts++
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectWs()
  }, delay)
}

onMounted(async () => {
  await deviceStore.fetchAll()
  devices.value = deviceStore.devices
  await Promise.all([
    loadStats(), loadLatestSensorData(),
    loadSensorTrend(), loadAlarmStats(),
    loadRecentAlarms(), loadRecentControls()
  ])
  connectWs()
})

onBeforeUnmount(() => {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  ws?.close()
  ws = null
})
</script>

<style scoped>
.dashboard { padding-bottom: 24px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }

.stat-cards { margin-bottom: 0; }
.stat-card { display: flex; align-items: center; gap: 16px; }
.clickable { cursor: pointer; transition: transform 0.15s, box-shadow 0.15s; }
.clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1) !important;
}
.stat-icon {
  width: 52px; height: 52px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center; color: #fff;
  flex-shrink: 0;
}
.stat-info { flex: 1; min-width: 0; }
.stat-value { font-size: 26px; font-weight: 700; line-height: 1.2; }
.stat-label { font-size: 13px; color: #909399; margin-top: 2px; }

.chart-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }

/* 离线设备行样式 */
:deep(.offline-row) {
  opacity: 0.55;
}
:deep(.offline-row td) {
  background-color: #fafafa;
}
</style>
