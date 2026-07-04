<template>
  <div class="monitor-view">
    <!-- Device Selector -->
    <div class="device-selector">
      <el-tabs
        v-model="activeTab"
        type="card"
        @tab-change="handleTabChange"
      >
        <el-tab-pane label="光照监测" name="monitor" />
        <el-tab-pane label="设备状态" name="status" />
      </el-tabs>
    </div>

    <!-- Tab: Light Monitoring (F1.1) -->
    <template v-if="activeTab === 'monitor'">
      <!-- Device Switcher -->
      <el-row :gutter="12" class="device-switcher">
        <el-col
          v-for="device in deviceStore.devices"
          :key="device.id"
          :xs="12" :sm="8" :md="6" :lg="4"
        >
          <el-card
            :class="['device-chip', {
              'active': monitorStore.currentDeviceId === device.deviceId,
              'offline': device.status === 'offline'
            }]"
            shadow="hover"
            @click="selectDevice(device.deviceId)"
          >
            <div class="chip-content">
              <span class="chip-name">{{ device.name }}</span>
              <el-tag
                :type="device.status === 'online' ? 'success' : 'info'"
                size="small"
                effect="plain"
              >
                {{ device.status === 'online' ? '在线' : '离线' }}
              </el-tag>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Main Sensor Display -->
      <el-row :gutter="20" class="sensor-row">
        <el-col :xs="24" :md="14">
          <el-card shadow="hover" class="sensor-card">
            <template #header>
              <div class="sensor-header">
                <span>实时光照强度</span>
                <div class="sensor-header-right">
                  <el-tag
                    v-if="monitorStore.dataAbnormal"
                    type="danger"
                    size="small"
                    effect="dark"
                  >
                    数据异常
                  </el-tag>
                  <el-tag
                    v-if="wsFallback"
                    type="warning"
                    size="small"
                  >
                    轮询模式
                  </el-tag>
                  <el-tag
                    v-if="monitorStore.isCurrentDeviceOffline"
                    type="info"
                    size="small"
                  >
                    设备离线
                  </el-tag>
                </div>
              </div>
            </template>
            <div
              class="sensor-display"
              :class="{ 'offline-overlay': monitorStore.isCurrentDeviceOffline }"
            >
              <!-- Loading skeleton -->
              <template v-if="loading">
                <el-skeleton :rows="2" animated />
              </template>
              <!-- No data -->
              <template v-else-if="!monitorStore.currentSensorData?.lightIntensity && !monitorStore.isCurrentDeviceOffline">
                <div class="no-data">
                  <div class="no-data-value">-- Lux</div>
                  <div class="no-data-hint">等待设备上报…</div>
                </div>
              </template>
              <!-- Normal display -->
              <template v-else>
                <div class="sensor-value-wrapper">
                  <transition name="value-change" mode="out-in">
                    <div
                      class="sensor-value"
                      :class="{ 'value-flash': intensityFlash }"
                      :key="displayIntensity"
                    >
                      {{ displayIntensity }}
                      <span class="sensor-unit">Lux</span>
                    </div>
                  </transition>
                  <!-- Ring progress bar -->
                  <el-progress
                    type="dashboard"
                    :percentage="intensityPercentage"
                    :stroke-width="8"
                    :color="intensityColor"
                    width="120"
                  >
                    <template #default>
                      <span class="ring-label">{{ displayIntensity }}</span>
                    </template>
                  </el-progress>
                </div>
                <div class="sensor-meta">
                  <span>最后上报: {{ lastReportTime }}</span>
                  <span>灯光状态:
                    <el-tag :type="deviceLightStatus === 'on' ? 'warning' : 'info'" size="small">
                      {{ deviceLightStatus === 'on' ? '开启' : '关闭' }}
                    </el-tag>
                  </span>
                </div>
              </template>

              <!-- Offline overlay -->
              <div v-if="monitorStore.isCurrentDeviceOffline" class="offline-mask">
                <el-icon :size="40" color="#909399"><WarningFilled /></el-icon>
                <span>设备已离线</span>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- Mini Trend -->
        <el-col :xs="24" :md="10">
          <el-card shadow="hover" class="trend-card">
            <template #header>
              <span>最近趋势（最近10条）</span>
            </template>
            <div class="mini-chart">
              <v-chart :option="miniTrendOption" autoresize style="height: 200px" />
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Connection fallback banner -->
      <div v-if="!wsStore.connected && !wsFallback" class="connection-banner">
        <el-alert
          title="连接中…"
          type="info"
          :closable="false"
          show-icon
        />
      </div>
    </template>

    <!-- Tab: Device Status (F1.2) -->
    <template v-if="activeTab === 'status'">
      <!-- Summary Bar -->
      <el-card shadow="hover" class="summary-card">
        <div class="summary-bar">
          <div class="summary-item">
            <span class="summary-label">总设备</span>
            <span class="summary-value">{{ deviceStore.devices.length }}</span>
          </div>
          <div class="summary-item online">
            <span class="summary-label">在线</span>
            <span class="summary-value">{{ deviceStore.onlineCount }}</span>
          </div>
          <div class="summary-item offline">
            <span class="summary-label">离线</span>
            <span class="summary-value">{{ deviceStore.offlineCount }}</span>
          </div>
          <div class="summary-item lights-on">
            <span class="summary-label">亮灯</span>
            <span class="summary-value">{{ deviceStore.lightsOnCount }}</span>
          </div>
        </div>
      </el-card>

      <!-- Search & Filter -->
      <div class="status-toolbar">
        <el-input
          v-model="searchQuery"
          placeholder="搜索设备名称/位置"
          clearable
          prefix-icon="Search"
          style="width: 260px"
        />
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="online">在线</el-radio-button>
          <el-radio-button value="offline">离线</el-radio-button>
        </el-radio-group>
      </div>

      <!-- Loading skeleton -->
      <template v-if="deviceStore.loading">
        <el-row :gutter="16">
          <el-col
            v-for="i in 6"
            :key="i"
            :xs="24" :sm="12" :md="8" :lg="6"
          >
            <el-card shadow="hover">
              <el-skeleton :rows="4" animated />
            </el-card>
          </el-col>
        </el-row>
      </template>

      <!-- Error state -->
      <template v-else-if="loadError">
        <div class="error-state">
          <el-result icon="error" title="加载失败" sub-title="设备数据加载失败">
            <template #extra>
              <el-button type="primary" @click="retryLoad">点击重试</el-button>
            </template>
          </el-result>
        </div>
      </template>

      <!-- Empty state -->
      <template v-else-if="filteredDevices.length === 0">
        <el-empty description="暂无设备，请先添加设备">
          <el-button type="primary" @click="$router.push('/devices')">去添加</el-button>
        </el-empty>
      </template>

      <!-- Device Cards Grid -->
      <template v-else>
        <el-row :gutter="16">
          <el-col
            v-for="device in filteredDevices"
            :key="device.id"
            :xs="24" :sm="12" :md="8" :lg="6"
            style="margin-bottom: 16px"
          >
            <el-card
              :class="['device-status-card', {
                'card-offline': device.status === 'offline',
                'card-alert-transition': statusChanged[device.id]
              }]"
              shadow="hover"
              @click="$router.push(`/devices/${device.id}`)"
            >
              <div class="status-card-header">
                <span class="device-name">{{ device.name }}</span>
                <span
                  class="status-indicator"
                  :class="device.status === 'online' ? 'dot-online' : 'dot-offline'"
                />
              </div>
              <div class="device-location">{{ device.location || '未知位置' }}</div>
              <div class="device-meta-row">
                <span class="light-icon">{{ device.lightStatus === 'on' ? '💡' : '🌑' }}</span>
                <el-tag
                  :type="device.controlMode === 'auto' ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ device.controlMode === 'auto' ? '自动' : '手动' }}
                </el-tag>
              </div>
              <div class="device-heartbeat">
                {{ device.lastHeartbeat ? relativeTime(device.lastHeartbeat) : '无心跳' }}
              </div>
            </el-card>
          </el-col>
        </el-row>
      </template>

      <!-- WS Reconnect toast -->
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import { useDeviceStore } from '@/stores/device'
import { useMonitorStore } from '@/stores/monitor'
import { useWebSocketStore } from '@/stores/websocket'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, TooltipComponent, GridComponent, CanvasRenderer])

const deviceStore = useDeviceStore()
const monitorStore = useMonitorStore()
const wsStore = useWebSocketStore()

const activeTab = ref('monitor')
const loading = ref(false)
const loadError = ref(false)
const wsFallback = ref(false)
const intensityFlash = ref(false)
let lastIntensity = null
let pollTimer = null

// Status page
const searchQuery = ref('')
const statusFilter = ref('')
const statusChanged = ref({})

// Select first device on mount
onMounted(() => {
  if (deviceStore.devices.length > 0 && !monitorStore.currentDeviceId) {
    monitorStore.setCurrentDevice(deviceStore.devices[0].deviceId)
  }
  loadLatestForSelected()

  wsStore.on('SENSOR_DATA', handleSensorData)
  wsStore.on('DEVICE_STATUS', handleDeviceStatusMsg)

  // Fallback polling if WS not connected
  if (!wsStore.connected) {
    startFallbackPolling()
  }
})

onUnmounted(() => {
  wsStore.off('SENSOR_DATA', handleSensorData)
  wsStore.off('DEVICE_STATUS', handleDeviceStatusMsg)
  stopFallbackPolling()
})

// Watch WS connection
watch(() => wsStore.connected, (connected) => {
  if (connected) {
    wsFallback.value = false
    stopFallbackPolling()
  } else {
    startFallbackPolling()
  }
})

// Computed
const displayIntensity = computed(() => {
  const data = monitorStore.currentSensorData
  return data?.lightIntensity !== undefined ? data.lightIntensity.toFixed(1) : '--'
})

const intensityPercentage = computed(() => {
  const val = monitorStore.currentSensorData?.lightIntensity
  if (val === undefined || val === null) return 0
  return Math.min((val / 2000) * 100, 100)
})

const intensityColor = computed(() => {
  const val = monitorStore.currentSensorData?.lightIntensity
  if (val === undefined) return '#909399'
  if (val < 50) return '#1a1a2e'
  if (val < 200) return '#4361ee'
  if (val < 500) return '#409EFF'
  if (val < 1000) return '#fbbf24'
  return '#f97316'
})

const lastReportTime = computed(() => {
  const data = monitorStore.currentSensorData
  if (!data?.reportedAt) return '--'
  return new Date(data.reportedAt).toLocaleString('zh-CN')
})

const deviceLightStatus = computed(() => {
  const device = deviceStore.devices.find(d => d.deviceId === monitorStore.currentDeviceId)
  return device?.lightStatus || 'off'
})

const filteredDevices = computed(() => {
  let list = deviceStore.devices
  if (statusFilter.value) {
    list = list.filter(d => d.status === statusFilter.value)
  }
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(d =>
      d.name?.toLowerCase().includes(q) ||
      d.location?.toLowerCase().includes(q)
    )
  }
  // Sort: online first
  return [...list].sort((a, b) => {
    if (a.status === 'online' && b.status !== 'online') return -1
    if (a.status !== 'online' && b.status === 'online') return 1
    return 0
  })
})

const miniTrendOption = computed(() => {
  const readings = monitorStore.currentRecentReadings
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        return `${p.value} Lux`
      }
    },
    grid: { left: 30, right: 10, bottom: 20, top: 10 },
    xAxis: {
      type: 'category',
      data: readings.map((_, i) => `${i + 1}`),
      show: false
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitLine: { lineStyle: { type: 'dashed', color: '#e8e8e8' } },
      axisLabel: { fontSize: 10 }
    },
    series: [{
      type: 'line',
      data: readings.map(r => r.lightIntensity),
      smooth: true,
      showSymbol: true,
      symbolSize: 4,
      lineStyle: { width: 2, color: '#409EFF' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(64,158,255,0.3)' },
            { offset: 1, color: 'rgba(64,158,255,0.05)' }
          ]
        }
      }
    }]
  }
})

// Methods
function selectDevice(deviceId) {
  monitorStore.setCurrentDevice(deviceId)
  loadLatestForSelected()
}

function handleTabChange() {
  // nothing special
}

function handleSensorData(data, deviceId) {
  if (!data) return
  monitorStore.updateSensorData(deviceId, data)

  // Flash effect on large change
  const intensity = data.lightIntensity
  if (intensity !== undefined && lastIntensity !== null) {
    if (Math.abs(intensity - lastIntensity) > 100) {
      intensityFlash.value = true
      setTimeout(() => { intensityFlash.value = false }, 500)
    }
  }
  lastIntensity = intensity
}

function handleDeviceStatusMsg(data, deviceId) {
  if (!data) return
  const prevStatus = deviceStore.devices.find(d => d.deviceId === deviceId)?.status
  deviceStore.updateDeviceStatus(deviceId, data.status, data.lightStatus)
  monitorStore.updateDeviceStatus(deviceId, data.status)

  // Status change animation
  if (prevStatus && prevStatus !== data.status) {
    statusChanged.value[deviceId] = true
    setTimeout(() => { statusChanged.value[deviceId] = false }, 1000)
  }
}

async function loadLatestForSelected() {
  if (!monitorStore.currentDeviceId) return
  loading.value = true
  loadError.value = false
  try {
    await monitorStore.fetchLatestData(monitorStore.currentDeviceId)
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function startFallbackPolling() {
  wsFallback.value = true
  pollTimer = setInterval(() => {
    if (monitorStore.currentDeviceId) {
      monitorStore.fetchLatestData(monitorStore.currentDeviceId)
    }
  }, 5000)
}

function stopFallbackPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  wsFallback.value = false
}

function retryLoad() {
  loadLatestForSelected()
}

function relativeTime(t) {
  if (!t) return '--'
  const diff = Date.now() - new Date(t).getTime()
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return `${seconds}秒前`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  return new Date(t).toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.monitor-view {
  max-width: 1400px;
  margin: 0 auto;
}

.device-selector {
  margin-bottom: 16px;
}

.device-switcher {
  margin-bottom: 16px;
}

.device-chip {
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: 12px;
}

.device-chip:hover {
  border-color: #409EFF;
}

.device-chip.active {
  border-color: #409EFF;
  background-color: #ecf5ff;
}

.device-chip.offline {
  opacity: 0.7;
}

.chip-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chip-name {
  font-size: 13px;
  font-weight: 500;
}

.sensor-row {
  margin-bottom: 20px;
}

.sensor-card .sensor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sensor-header-right {
  display: flex;
  gap: 8px;
}

.sensor-display {
  position: relative;
  min-height: 200px;
}

.sensor-value-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 40px;
  padding: 20px 0;
}

.sensor-value {
  font-size: 56px;
  font-weight: 700;
  color: #303133;
  transition: color 0.3s;
}

.sensor-unit {
  font-size: 20px;
  color: #909399;
  margin-left: 8px;
}

.value-flash {
  animation: flashPulse 0.5s ease;
}

@keyframes flashPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; transform: scale(1.05); }
}

.value-change-enter-active,
.value-change-leave-active {
  transition: all 0.3s ease;
}

.value-change-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.value-change-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

.ring-label {
  font-size: 16px;
  font-weight: 600;
}

.sensor-meta {
  display: flex;
  justify-content: center;
  gap: 24px;
  font-size: 12px;
  color: #909399;
  padding-bottom: 8px;
}

.no-data {
  text-align: center;
  padding: 40px 0;
}

.no-data-value {
  font-size: 48px;
  font-weight: 300;
  color: #c0c4cc;
}

.no-data-hint {
  font-size: 14px;
  color: #909399;
  margin-top: 8px;
}

.offline-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.8);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #909399;
  font-size: 16px;
  z-index: 2;
}

.connection-banner {
  margin-bottom: 12px;
}

/* Device Status styles */
.summary-card {
  margin-bottom: 16px;
}

.summary-bar {
  display: flex;
  gap: 24px;
  padding: 8px 0;
}

.summary-item {
  flex: 1;
  text-align: center;
}

.summary-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.summary-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
}

.summary-item.online .summary-value { color: #67C23A; }
.summary-item.offline .summary-value { color: #F56C6C; }
.summary-item.lights-on .summary-value { color: #E6A23C; }

.status-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.device-status-card {
  cursor: pointer;
  transition: all 0.2s;
}

.device-status-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.card-offline {
  opacity: 0.6;
}

.card-alert-transition {
  animation: borderFlash 1s ease;
}

@keyframes borderFlash {
  0% { border-color: #F56C6C; box-shadow: 0 0 8px rgba(245,108,108,0.5); }
  100% { border-color: transparent; box-shadow: none; }
}

.status-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.device-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.status-indicator {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-online {
  background-color: #67C23A;
  box-shadow: 0 0 4px #67C23A;
}

.dot-offline {
  background-color: #c0c4cc;
}

.device-location {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.device-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.light-icon {
  font-size: 18px;
}

.device-heartbeat {
  font-size: 11px;
  color: #c0c4cc;
}

.error-state {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}
</style>
