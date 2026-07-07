<template>
  <div class="device-detail" v-loading="loading">
    <el-button link class="back-btn" @click="$router.push('/devices')">
      <el-icon><ArrowLeft /></el-icon> 返回设备列表
    </el-button>

    <template v-if="device">
      <!-- Info Card -->
      <el-row :gutter="20">
        <el-col :span="8">
          <el-card shadow="hover" class="info-card">
            <template #header>
              <div class="info-header">
                <span>{{ device.name }}</span>
                <el-tag :type="device.status === 'online' ? 'success' : 'info'" size="small">
                  {{ device.status === 'online' ? '在线' : '离线' }}
                </el-tag>
              </div>
            </template>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="设备编号">{{ device.deviceId }}</el-descriptions-item>
              <el-descriptions-item label="安装位置">{{ device.location || '--' }}</el-descriptions-item>
              <el-descriptions-item label="控制模式">
                <el-switch
                  :model-value="device.controlMode === 'auto'"
                  active-text="自动"
                  inactive-text="手动"
                  @change="handleModeChange"
                  size="small"
                />
              </el-descriptions-item>
              <el-descriptions-item label="最后心跳">
                {{ device.lastHeartbeat ? formatTime(device.lastHeartbeat) : '--' }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>

        <!-- Sensor & Control -->
        <el-col :span="16">
          <el-row :gutter="20">
            <!-- Sensor Reading -->
            <el-col :span="12">
              <el-card shadow="hover">
                <template #header>实时光照强度</template>
                <div class="sensor-value">
                  <span class="value">{{ latestIntensity ?? '--' }}</span>
                  <span class="unit">Lux</span>
                </div>
              </el-card>
            </el-col>

            <!-- Control Panel -->
            <el-col :span="12">
              <el-card shadow="hover">
                <template #header>远程控制</template>
                <div class="control-panel">
                  <el-button
                    type="warning"
                    :icon="View"
                    :disabled="device.lightStatus === 'on' || device.controlMode !== 'manual'"
                    @click="handleSendCommand('on')"
                    round
                  >开灯</el-button>
                  <el-button
                    type="info"
                    :icon="Hide"
                    :disabled="device.lightStatus === 'off' || device.controlMode !== 'manual'"
                    @click="sendCommand('off')"
                    round
                  >关灯</el-button>
                  <div class="mode-hint" v-if="device.controlMode === 'auto'">
                    <el-tag type="info" size="small">当前为自动模式，请在设备列表切换为手动模式</el-tag>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <!-- Threshold Settings -->
          <el-row :gutter="20" style="margin-top: 20px;">
            <el-col :span="24">
              <el-card shadow="hover">
                <template #header>
                  <div class="card-header">
                    <span>光照阈值设置</span>
                    <el-button type="primary" size="small" @click="saveThreshold" :loading="thresholdLoading">保存</el-button>
                  </div>
                </template>
                <el-row :gutter="40">
                  <el-col :span="12">
                    <div class="threshold-item">
                      <label>开灯光照阈值</label>
                      <el-slider
                        v-model="thresholdOn"
                        :min="0"
                        :max="500"
                        show-input
                        :format-tooltip="v => `${v} Lux`"
                      />
                      <span class="threshold-desc">光照低于此值 → 自动开灯</span>
                    </div>
                  </el-col>
                  <el-col :span="12">
                    <div class="threshold-item">
                      <label>关灯光照阈值</label>
                      <el-slider
                        v-model="thresholdOff"
                        :min="0"
                        :max="500"
                        show-input
                        :format-tooltip="v => `${v} Lux`"
                      />
                      <span class="threshold-desc">光照高于此值 → 自动关灯</span>
                    </div>
                  </el-col>
                </el-row>
              </el-card>
            </el-col>
          </el-row>
        </el-col>
      </el-row>

      <!-- History Chart -->
      <el-row :gutter="20" style="margin-top: 20px;">
        <el-col :span="24">
          <el-card shadow="hover">
            <template #header>
              <div class="card-header">
                <span>光照强度历史趋势</span>
                <div class="time-range">
                  <el-radio-group v-model="timeRange" size="small" @change="loadHistory">
                    <el-radio-button value="1h">1小时</el-radio-button>
                    <el-radio-button value="6h">6小时</el-radio-button>
                    <el-radio-button value="24h">24小时</el-radio-button>
                  </el-radio-group>
                </div>
              </div>
            </template>
            <div class="chart-container" style="height: 300px">
              <v-chart :option="historyChartOption" autoresize />
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- Control Logs -->
      <el-row :gutter="20" style="margin-top: 20px;">
        <el-col :span="24">
          <el-card shadow="hover">
            <template #header>控制日志</template>
            <el-table :data="controlLogs" stripe size="small" max-height="300">
              <el-table-column prop="command" label="指令" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.command === 'on' ? 'warning' : 'info'" size="small">
                    {{ row.command === 'on' ? '开灯' : '关灯' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="source" label="来源" width="80" />
              <el-table-column prop="result" label="结果" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.result === 'success' ? 'success' : 'danger'" size="small">
                    {{ row.result === 'success' ? '成功' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" min-width="160">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, View, Hide } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getDeviceById } from '@/api/device'
import { getLatestSensorData, getSensorHistory } from '@/api/sensor'
import { sendCommand as apiSendCommand, getControlLogs } from '@/api/control'
import { setThreshold } from '@/api/device'
import { useWebSocketStore } from '@/stores/websocket'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, TitleComponent, TooltipComponent, GridComponent, CanvasRenderer])

const route = useRoute()
const wsStore = useWebSocketStore()

const device = ref(null)
const loading = ref(true)
const latestIntensity = ref(null)
const timeRange = ref('1h')
const controlLogs = ref([])
const thresholdOn = ref(50)
const thresholdOff = ref(100)
const thresholdLoading = ref(false)

// History chart
const historyTime = ref([])
const historyValues = ref([])

const historyChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, bottom: 30, top: 10 },
  xAxis: {
    type: 'category',
    data: historyTime.value,
    axisLabel: { fontSize: 11 }
  },
  yAxis: {
    type: 'value',
    name: 'Lux',
    min: 0
  },
  series: [{
    type: 'line',
    data: historyValues.value,
    smooth: true,
    showSymbol: false,
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
}))

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

function handleSensorData(data) {
  if (data && data.lightIntensity !== undefined) {
    latestIntensity.value = data.lightIntensity
  }
}

function handleControlResult(data) {
  if (data) {
    ElMessage.success(`指令执行${data.result === 'success' ? '成功' : '失败'}`)
    loadControlLogs()
  }
}

async function loadDevice() {
  loading.value = true
  try {
    const id = route.params.id
    device.value = await getDeviceById(id)
    thresholdOn.value = device.value.thresholdOn ?? 50
    thresholdOff.value = device.value.thresholdOff ?? 100
    loadLatestSensor()
    loadHistory()
    loadControlLogs()
  } catch (e) {
    ElMessage.error('获取设备信息失败')
  } finally {
    loading.value = false
  }
}

async function loadLatestSensor() {
  try {
    const data = await getLatestSensorData(device.value.deviceId)
    if (data) latestIntensity.value = data.lightIntensity
  } catch (e) {
    // no data yet
  }
}

async function loadHistory() {
  if (!device.value) return
  const now = new Date()
  const start = new Date(now)
  const hours = parseInt(timeRange.value)
  start.setHours(start.getHours() - hours)

  try {
    const data = await getSensorHistory(
      device.value.deviceId,
      start.toISOString(),
      now.toISOString()
    )
    historyTime.value = data.map(d => new Date(d.reportedAt).toLocaleTimeString())
    historyValues.value = data.map(d => d.lightIntensity)
  } catch (e) {
    // no data
  }
}

async function loadControlLogs() {
  if (!device.value) return
  try {
    controlLogs.value = await getControlLogs(device.value.deviceId)
  } catch (e) {
    // no data
  }
}

async function handleSendCommand(cmd) {
  try {
    await apiSendCommand(device.value.deviceId, cmd)
    ElMessage.success(`已发送${cmd === 'on' ? '开灯' : '关灯'}指令`)
  } catch (e) {
    // handled by interceptor
  }
}

async function saveThreshold() {
  thresholdLoading.value = true
  try {
    await setThreshold(device.value.id, thresholdOn.value, thresholdOff.value)
    ElMessage.success('阈值保存成功')
  } catch (e) {
    // handled by interceptor
  } finally {
    thresholdLoading.value = false
  }
}

async function handleModeChange(isAuto) {
  const mode = isAuto ? 'auto' : 'manual'
  try {
    const { setControlMode } = await import('@/api/device')
    await setControlMode(device.value.id, mode)
    device.value.controlMode = mode
    ElMessage.success(`已切换为${isAuto ? '自动' : '手动'}模式`)
  } catch (e) {
    // handled by interceptor
  }
}

onMounted(() => {
  loadDevice()
  wsStore.on('SENSOR_DATA', handleSensorData)
  wsStore.on('CONTROL_RESULT', handleControlResult)
})

onUnmounted(() => {
  wsStore.off('SENSOR_DATA', handleSensorData)
  wsStore.off('CONTROL_RESULT', handleControlResult)
})
</script>

<style scoped>
.device-detail {
  max-width: 1400px;
  margin: 0 auto;
}

.back-btn {
  margin-bottom: 16px;
  font-size: 14px;
}

.info-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sensor-value {
  text-align: center;
  padding: 20px 0;
}

.sensor-value .value {
  font-size: 48px;
  font-weight: 700;
  color: #409EFF;
}

.sensor-value .unit {
  font-size: 16px;
  color: #909399;
  margin-left: 8px;
}

.control-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
}

.control-panel .el-button {
  width: 120px;
}

.mode-hint {
  margin-top: 8px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.threshold-item {
  padding: 8px 0;
}

.threshold-item label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  color: #606266;
}

.threshold-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.time-range {
  display: flex;
  gap: 8px;
}

.chart-container {
  height: 300px;
}
</style>
