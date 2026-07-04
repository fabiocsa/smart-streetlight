<template>
  <div class="control-view">
    <!-- Device Selection -->
    <el-card shadow="hover" class="device-select-card">
      <template #header>
        <span>选择设备</span>
      </template>
      <el-row :gutter="12">
        <el-col
          v-for="device in deviceStore.devices"
          :key="device.id"
          :xs="12" :sm="8" :md="6" :lg="4"
          style="margin-bottom: 12px"
        >
          <el-card
            :class="['device-option', {
              'selected': selectedDevice?.deviceId === device.deviceId,
              'disabled': device.status === 'offline'
            }]"
            shadow="hover"
            @click="selectDevice(device)"
          >
            <div class="device-option-content">
              <div class="device-option-name">{{ device.name }}</div>
              <div class="device-option-status">
                <el-tag
                  :type="device.status === 'online' ? 'success' : 'info'"
                  size="small"
                >
                  {{ device.status === 'online' ? '在线' : '离线' }}
                </el-tag>
                <el-tag
                  :type="device.controlMode === 'auto' ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ device.controlMode === 'auto' ? '自动' : '手动' }}
                </el-tag>
              </div>
              <div class="device-option-light">
                {{ device.lightStatus === 'on' ? '💡 已开灯' : '🌑 已关灯' }}
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- No Device Selected -->
    <template v-if="!selectedDevice">
      <el-empty description="请选择一个设备">
        <template #image>
          <el-icon :size="64" color="#c0c4cc"><Pointer /></el-icon>
        </template>
      </el-empty>
    </template>

    <!-- Device Control Panel -->
    <template v-else>
      <el-row :gutter="20">
        <!-- Left: Manual Control (F3.2) -->
        <el-col :xs="24" :md="12">
          <el-card shadow="hover" class="control-card">
            <template #header>
              <div class="card-header">
                <span>手动控制</span>
                <el-tag
                  v-if="selectedDevice.controlMode === 'auto'"
                  type="info"
                  size="small"
                >
                  当前为自动模式，手动控制不可用
                </el-tag>
              </div>
            </template>

            <div class="control-panel">
              <!-- Light Icon -->
              <div class="light-icon-wrapper" :class="{ 'light-on': selectedDevice.lightStatus === 'on' }">
                <span class="light-bulb">💡</span>
              </div>

              <!-- Control Buttons -->
              <div class="control-buttons">
                <el-button
                  type="warning"
                  size="large"
                  :icon="View"
                  :disabled="selectedDevice.lightStatus === 'on' || selectedDevice.controlMode !== 'manual' || selectedDevice.status === 'offline'"
                  :loading="cmdLoading && lastCmd === 'on'"
                  @click="sendCommand('on')"
                  round
                >
                  开灯
                </el-button>
                <el-button
                  type="info"
                  size="large"
                  :icon="Hide"
                  :disabled="selectedDevice.lightStatus === 'off' || selectedDevice.controlMode !== 'manual' || selectedDevice.status === 'offline'"
                  :loading="cmdLoading && lastCmd === 'off'"
                  @click="sendCommand('off')"
                  round
                >
                  关灯
                </el-button>
              </div>

              <!-- Extra Info -->
              <div class="control-info">
                <span>当前光照: <strong>{{ currentIntensity ?? '--' }} Lux</strong></span>
                <span>控制模式: <strong>{{ selectedDevice.controlMode === 'auto' ? '自动' : '手动' }}</strong></span>
                <span>最后操作: <strong>{{ lastOperationTime }}</strong></span>
              </div>

              <!-- Offline hint -->
              <div v-if="selectedDevice.status === 'offline'" class="offline-hint">
                <el-alert title="设备离线，无法控制" type="warning" :closable="false" show-icon />
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- Right: Auto Mode Status (F3.1) -->
        <el-col :xs="24" :md="12">
          <el-card shadow="hover" class="auto-card">
            <template #header>
              <div class="card-header">
                <span>自动联动</span>
                <el-switch
                  :model-value="selectedDevice.controlMode === 'auto'"
                  active-text="自动"
                  inactive-text="手动"
                  @change="handleModeChange"
                />
              </div>
            </template>

            <!-- Mode indicator -->
            <div class="mode-indicator">
              <div
                class="mode-badge"
                :class="selectedDevice.controlMode === 'auto' ? 'mode-auto' : 'mode-manual'"
              >
                <el-icon :size="20">
                  <component :is="selectedDevice.controlMode === 'auto' ? 'CircleCheck' : 'Remove' " />
                </el-icon>
                <span>{{ selectedDevice.controlMode === 'auto' ? '自动控制中' : '手动控制中' }}</span>
              </div>
              <div v-if="selectedDevice.controlMode === 'manual'" class="mode-hint">
                自动联动已暂停
              </div>
            </div>

            <!-- Visual Threshold Scale -->
            <div class="threshold-scale">
              <div class="scale-label">光照值 vs 阈值</div>
              <div class="scale-bar">
                <div class="scale-track">
                  <div
                    class="scale-fill"
                    :style="{ width: intensityPercent + '%' }"
                    :class="intensityPercent < 10 ? 'low' : 'normal'"
                  />
                  <div
                    class="threshold-marker threshold-on"
                    :style="{ left: thresholdOnPercent + '%' }"
                    title="开灯阈值"
                  >
                    <span class="marker-label">开灯 {{ selectedDevice.thresholdOn ?? 50 }} Lux</span>
                  </div>
                  <div
                    class="threshold-marker threshold-off"
                    :style="{ left: thresholdOffPercent + '%' }"
                    title="关灯阈值"
                  >
                    <span class="marker-label">关灯 {{ selectedDevice.thresholdOff ?? 100 }} Lux</span>
                  </div>
                </div>
                <div class="scale-value">{{ currentIntensity ?? '--' }} Lux</div>
              </div>
            </div>

            <!-- Auto Control Log -->
            <div class="auto-log">
              <div class="log-title">最近联动记录</div>
              <div v-if="autoLogs.length === 0" class="log-empty">暂无联动记录</div>
              <div v-for="(log, i) in autoLogs" :key="i" class="log-item">
                <span class="log-time">{{ formatTime(log.createdAt) }}</span>
                <el-tag
                  :type="log.command === 'on' ? 'warning' : 'info'"
                  size="small"
                >
                  {{ log.command === 'on' ? '开灯' : '关灯' }}
                </el-tag>
                <span class="log-reason">{{ log.reason || log.content || '自动触发' }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Control Log Table -->
      <el-card shadow="hover" class="log-card">
        <template #header>
          <span>控制日志（最近20条）</span>
        </template>
        <el-table :data="controlLogs" stripe size="small" max-height="300" v-loading="logLoading">
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="指令" width="80">
            <template #default="{ row }">
              <el-tag :type="row.command === 'on' ? 'warning' : 'info'" size="small">
                {{ row.command === 'on' ? '开灯' : '关灯' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">
              {{ row.source === 'auto' ? '自动' : '手动' }}
            </template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.result === 'success' ? 'success' : 'danger'" size="small">
                {{ row.result === 'success' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="备注" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { View, Hide, Pointer } from '@element-plus/icons-vue'
import { useDeviceStore } from '@/stores/device'
import { useWebSocketStore } from '@/stores/websocket'
import { getLatestSensorData } from '@/api/sensor'
import { sendCommand as apiSendCommand, getControlLogs } from '@/api/control'
import { setControlMode } from '@/api/device'

const deviceStore = useDeviceStore()
const wsStore = useWebSocketStore()

const selectedDevice = ref(null)
const currentIntensity = ref(null)
const controlLogs = ref([])
const autoLogs = ref([])
const cmdLoading = ref(false)
const lastCmd = ref(null)
const logLoading = ref(false)
const lastOperationTime = ref('--')
let cmdTimeout = null

onMounted(() => {
  wsStore.on('SENSOR_DATA', handleSensorData)
  wsStore.on('CONTROL_RESULT', handleControlResult)
  wsStore.on('DEVICE_STATUS', handleDeviceStatus)
})

onUnmounted(() => {
  wsStore.off('SENSOR_DATA', handleSensorData)
  wsStore.off('CONTROL_RESULT', handleControlResult)
  wsStore.off('DEVICE_STATUS', handleDeviceStatus)
  if (cmdTimeout) clearTimeout(cmdTimeout)
})

const intensityPercent = computed(() => {
  if (currentIntensity.value === null) return 0
  return Math.min((currentIntensity.value / 2000) * 100, 100)
})

const thresholdOnPercent = computed(() => {
  const t = selectedDevice.value?.thresholdOn ?? 50
  return (t / 2000) * 100
})

const thresholdOffPercent = computed(() => {
  const t = selectedDevice.value?.thresholdOff ?? 100
  return (t / 2000) * 100
})

function selectDevice(device) {
  selectedDevice.value = device
  loadDeviceData()
}

async function loadDeviceData() {
  if (!selectedDevice.value) return
  // Load latest sensor
  try {
    const data = await getLatestSensorData(selectedDevice.value.deviceId)
    if (data) currentIntensity.value = data.lightIntensity
  } catch (e) { /* no data yet */ }

  // Load control logs
  loadLogs()
}

async function loadLogs() {
  if (!selectedDevice.value) return
  logLoading.value = true
  try {
    const logs = await getControlLogs(selectedDevice.value.deviceId)
    controlLogs.value = Array.isArray(logs) ? logs : []
    // Separate auto logs
    autoLogs.value = controlLogs.value
      .filter(l => l.source === 'auto')
      .slice(0, 5)
  } catch (e) {
    controlLogs.value = []
  } finally {
    logLoading.value = false
  }
}

function sendCommand(cmd) {
  if (!selectedDevice.value || cmdLoading.value) return

  cmdLoading.value = true
  lastCmd.value = cmd

  apiSendCommand(selectedDevice.value.deviceId, cmd)
    .then(() => {
      ElMessage.success(`已发送${cmd === 'on' ? '开灯' : '关灯'}指令`)
      // Timeout fallback
      cmdTimeout = setTimeout(() => {
        cmdLoading.value = false
        ElMessage.info('指令已下发，等待设备响应…')
      }, 5000)
    })
    .catch(() => {
      cmdLoading.value = false
    })
}

function handleSensorData(data, deviceId) {
  if (!data || deviceId !== selectedDevice.value?.deviceId) return
  if (data.lightIntensity !== undefined) {
    currentIntensity.value = data.lightIntensity
  }
}

function handleControlResult(data) {
  if (!data) return
  if (cmdTimeout) clearTimeout(cmdTimeout)
  cmdLoading.value = false

  if (data.result === 'success') {
    ElMessage.success('操作成功')
    // Update device light status
    if (selectedDevice.value && data.lightStatus) {
      selectedDevice.value.lightStatus = data.lightStatus
    }
    lastOperationTime.value = new Date().toLocaleString('zh-CN')
  } else {
    ElMessage.error(`操作失败${data.message ? ': ' + data.message : ''}`)
  }
  loadLogs()
}

function handleDeviceStatus(data, deviceId) {
  if (!data || deviceId !== selectedDevice.value?.deviceId) return
  deviceStore.updateDeviceStatus(deviceId, data.status, data.lightStatus)
  if (selectedDevice.value) {
    if (data.status) selectedDevice.value.status = data.status
    if (data.lightStatus) selectedDevice.value.lightStatus = data.lightStatus
  }
}

async function handleModeChange(isAuto) {
  if (!selectedDevice.value) return

  // Confirm when switching from auto to manual
  if (!isAuto) {
    try {
      await ElMessageBox.confirm(
        '切换手动模式后自动联动将暂停，是否继续？',
        '确认切换',
        { confirmButtonText: '继续', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }

  const mode = isAuto ? 'auto' : 'manual'
  try {
    await setControlMode(selectedDevice.value.id, mode)
    selectedDevice.value.controlMode = mode
    ElMessage.success(`已切换为${isAuto ? '自动' : '手动'}模式`)
  } catch (e) {
    // handled by interceptor
  }
}

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}
</script>

<style scoped>
.control-view {
  max-width: 1400px;
  margin: 0 auto;
}

.device-select-card {
  margin-bottom: 16px;
}

.device-option {
  cursor: pointer;
  transition: all 0.2s;
}

.device-option:hover {
  border-color: #409EFF;
}

.device-option.selected {
  border-color: #409EFF;
  background-color: #ecf5ff;
}

.device-option.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.device-option-content {
  text-align: center;
}

.device-option-name {
  font-weight: 600;
  margin-bottom: 4px;
}

.device-option-status {
  display: flex;
  gap: 4px;
  justify-content: center;
  margin-bottom: 4px;
}

.device-option-light {
  font-size: 12px;
  color: #909399;
}

.control-card,
.auto-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.control-panel {
  text-align: center;
  padding: 20px 0;
}

.light-icon-wrapper {
  font-size: 64px;
  margin-bottom: 20px;
  transition: all 0.3s;
}

.light-icon-wrapper.light-on .light-bulb {
  animation: glow 1.5s ease-in-out infinite alternate;
}

@keyframes glow {
  from { filter: drop-shadow(0 0 4px #fbbf24); }
  to { filter: drop-shadow(0 0 12px #f59e0b); }
}

.light-bulb {
  display: inline-block;
}

.control-buttons {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-bottom: 16px;
}

.control-buttons .el-button {
  width: 140px;
}

.control-info {
  display: flex;
  justify-content: center;
  gap: 20px;
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
}

.offline-hint {
  max-width: 400px;
  margin: 0 auto;
}

/* Auto mode styles */
.mode-indicator {
  text-align: center;
  padding: 16px 0;
}

.mode-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
}

.mode-auto {
  background-color: #e1f3d8;
  color: #67C23A;
}

.mode-manual {
  background-color: #f4f4f5;
  color: #909399;
}

.mode-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

/* Threshold scale */
.threshold-scale {
  padding: 16px;
  margin: 0 16px 16px;
  background: #fafafa;
  border-radius: 8px;
}

.scale-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.scale-bar {
  position: relative;
  height: 40px;
}

.scale-track {
  position: relative;
  height: 16px;
  background: #e8e8e8;
  border-radius: 8px;
  overflow: visible;
}

.scale-fill {
  height: 100%;
  border-radius: 8px;
  transition: width 0.3s;
  background: linear-gradient(90deg, #1a1a2e, #4361ee, #409EFF, #fbbf24, #f97316);
}

.scale-fill.low {
  background: #1a1a2e;
}

.threshold-marker {
  position: absolute;
  top: -4px;
  width: 2px;
  height: 24px;
  background: #F56C6C;
  transform: translateX(-50%);
  z-index: 2;
}

.threshold-marker.threshold-off {
  background: #67C23A;
}

.marker-label {
  position: absolute;
  top: -18px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  white-space: nowrap;
  color: #909399;
}

.scale-value {
  text-align: center;
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}

/* Auto log */
.auto-log {
  padding: 0 16px 16px;
}

.log-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.log-empty {
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
  padding: 12px;
}

.log-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
}

.log-item:last-child {
  border-bottom: none;
}

.log-time {
  color: #909399;
  min-width: 80px;
}

.log-reason {
  color: #606266;
}

/* Log card */
.log-card {
  margin-bottom: 16px;
}
</style>
