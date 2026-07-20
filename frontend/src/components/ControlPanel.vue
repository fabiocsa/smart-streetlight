<template>
  <el-card shadow="never" class="control-panel">
    <template #header>
      <div class="panel-header">
        <strong>设备控制</strong>
        <el-tag :type="device.status === 'online' ? 'success' : 'danger'" size="small">
          {{ device.status === 'online' ? '在线' : '离线' }}
        </el-tag>
      </div>
    </template>

    <!-- 操作反馈 -->
    <el-alert
      v-if="lastResult"
      :title="lastResult.text"
      :type="lastResult.type"
      :closable="true"
      show-icon
      @close="lastResult = null"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="20">
      <!-- 左侧：开关控制 + 状态 -->
      <el-col :span="12">
        <div class="section-title">灯光控制</div>
        <div class="switch-area">
          <el-tooltip :content="device.status === 'offline' ? '设备离线，无法控制' : (device.lightStatus === 'unknown' ? '灯光状态未知，请等待数据上报' : '点击切换灯光开关')" placement="top">
            <div
              class="switch-knob"
              :class="{ active: device.lightStatus === 'on', disabled: device.status !== 'online' || device.lightStatus === 'unknown' || sending }"
              @click="toggleLight"
            >
              <el-icon :size="36">
                <Sunny v-if="device.lightStatus === 'on'" />
                <Moon v-else-if="device.lightStatus === 'off'" />
                <QuestionFilled v-else />
              </el-icon>
              <span class="switch-label">{{ switchLabel }}</span>
              <span class="switch-hint">{{ switchHint }}</span>
            </div>
          </el-tooltip>

          <div class="switch-status">
            <el-tag :type="lightStatusTagType(device)" size="large">
              {{ lightStatusText(device) }}
            </el-tag>
          </div>
        </div>

      </el-col>

      <!-- 右侧：控制模式 + 阈值 -->
      <el-col :span="12">
        <div class="section-title">控制模式</div>
        <el-radio-group
          v-model="controlMode"
          :disabled="sending"
          @change="handleModeChange"
          class="mode-group"
        >
          <el-radio-button value="auto">
            <el-icon><Setting /></el-icon> 自动控制
          </el-radio-button>
          <el-radio-button value="manual">
            <el-icon><User /></el-icon> 手动控制
          </el-radio-button>
        </el-radio-group>
        <p class="mode-desc">
          {{ controlMode === 'auto'
            ? '根据光照传感器数据自动判断开关灯'
            : '仅响应人工下发的控制指令，自动联动暂停' }}
        </p>

        <!-- 阈值设置（仅自动模式） -->
        <div v-if="controlMode === 'auto'" class="section-title" style="margin-top: 20px">
          联动阈值设置
        </div>
        <div v-if="controlMode === 'auto'" class="threshold-area">
          <el-form label-width="100px" size="default">
            <el-form-item label="开灯阈值 (Lux)">
              <el-input-number
                v-model="thresholdOn"
                :min="0"
                :max="500"
                :step="5"
                :disabled="sending"
                controls-position="right"
              />
              <span class="form-hint">低于此值自动开灯</span>
            </el-form-item>
            <el-form-item label="关灯阈值 (Lux)">
              <el-input-number
                v-model="thresholdOff"
                :min="10"
                :max="1000"
                :step="5"
                :disabled="sending"
                controls-position="right"
              />
              <span class="form-hint">高于此值自动关灯</span>
            </el-form-item>
          </el-form>
          <el-button
            type="primary"
            :disabled="sending || thresholdOn >= thresholdOff"
            :loading="sending && pendingCmd === 'threshold'"
            @click="applyThreshold"
          >
            保存阈值
          </el-button>
          <el-text v-if="thresholdOn >= thresholdOff" type="danger" size="small" style="margin-left: 8px">
            开灯阈值必须小于关灯阈值
          </el-text>
        </div>

        <!-- 传感器决策策略（仅自动模式 + 多个 light 传感器时显示） -->
        <div v-if="controlMode === 'auto' && lightSensors.length > 1" class="section-title" style="margin-top: 20px">
          传感器决策策略
        </div>
        <div v-if="controlMode === 'auto' && lightSensors.length > 1" class="strategy-area">
          <el-radio-group
            v-model="sensorStrategy"
            :disabled="sending"
            class="strategy-group"
          >
            <el-radio value="single">以指定传感器为准</el-radio>
            <el-radio value="average">取所有传感器平均值</el-radio>
          </el-radio-group>

          <div v-if="sensorStrategy === 'single'" style="margin-top: 10px">
            <el-form label-width="100px" size="default">
              <el-form-item label="主传感器">
                <el-select
                  v-model="primarySensorId"
                  placeholder="选择作为决策依据的传感器"
                  :disabled="sending"
                  style="width: 220px"
                >
                  <el-option
                    v-for="s in lightSensors"
                    :key="s.id"
                    :label="s.displayName || `传感器 #${s.id}`"
                    :value="s.id"
                  />
                </el-select>
              </el-form-item>
            </el-form>
          </div>

          <p class="strategy-desc">
            {{ sensorStrategy === 'single'
              ? '仅使用指定传感器的光照数据判断开关灯'
              : '取所有已绑定光照传感器最新数据的平均值作为决策依据' }}
          </p>

          <el-button
            type="primary"
            :disabled="sending || (sensorStrategy === 'single' && !primarySensorId)"
            :loading="sending && pendingCmd === 'strategy'"
            @click="applySensorStrategy"
          >
            保存策略
          </el-button>
        </div>
      </el-col>
    </el-row>

    <!-- 底部：最近操作日志 -->
    <div class="section-title" style="margin-top: 20px">
      最近操作记录
      <el-button link type="primary" @click="loadLogs" :loading="logsLoading" style="margin-left: 12px">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>
    <el-table
      :data="controlLogs"
      v-loading="logsLoading"
      stripe
      size="small"
      style="width: 100%"
      empty-text="暂无操作记录"
    >
      <el-table-column prop="command" label="指令" width="80">
        <template #default="{ row }">
          <el-tag :type="row.command === 'on' ? 'warning' : 'info'" size="small">
            {{ row.command === 'on' ? '开灯' : '关灯' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="80">
        <template #default="{ row }">
          {{ row.source === 'auto' ? '自动' : '手动' }}
        </template>
      </el-table-column>
      <el-table-column prop="result" label="结果" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.result === 'success'" type="success" size="small">成功</el-tag>
          <el-tag v-else-if="row.result === 'fail'" type="danger" size="small">失败</el-tag>
          <el-tag v-else type="info" size="small">等待中</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" min-width="160">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Sunny, Moon, Setting, User, Refresh, QuestionFilled } from '@element-plus/icons-vue'
import { sendControl, setControlMode, setThreshold, getControlLogs, setSensorStrategy } from '../api/control'
import { formatTime, debounce, lightStatusTagType } from '../utils/common'

const props = defineProps({
  device: { type: Object, required: true },
  sensors: { type: Array, default: () => [] }
})

const emit = defineEmits(['updated'])

// 控制状态
const sending = ref(false)
const pendingCmd = ref('')       // 当前等待的指令类型
const lastResult = ref(null)      // 最近操作结果 { type, text }
// ★ controlMode 直接派生自 props，不再手动同步，消除闪烁
const controlMode = computed({
  get: () => props.device?.controlMode || 'auto',
  set: (v) => { if (props.device) props.device.controlMode = v }
})
const thresholdOn = ref(50)
const thresholdOff = ref(100)
const sensorStrategy = ref('single')
const primarySensorId = ref(null)

// 日志
const controlLogs = ref([])
const logsLoading = ref(false)

// 订阅 WebSocket 控制结果反馈（实时更新 UI）
let ws = null
const WS_TIMEOUT = 8000
let timeoutHandle = null
let fallbackHandle = null    // 3 秒 HTTP 兜底 timer

function cleanupTimers() {
  if (timeoutHandle) { clearTimeout(timeoutHandle); timeoutHandle = null }
  if (fallbackHandle) { clearTimeout(fallbackHandle); fallbackHandle = null }
}

function connectWs() {
  if (ws && ws.readyState === WebSocket.OPEN) return
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const url = `${proto}//${location.host}/ws/monitor`
  try {
    ws = new WebSocket(url)
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data)
        if (msg.type === 'CONTROL_RESULT' && msg.deviceId === props.device.deviceId) {
          handleControlResult(msg.data)
        }
        if (msg.type === 'DEVICE_STATUS' && msg.deviceId === props.device.deviceId) {
          if (msg.data.lightStatus) {
            props.device.lightStatus = msg.data.lightStatus
          }
        }
      } catch { /* ignore */ }
    }
    ws.onclose = () => { ws = null }
  } catch { /* ignore */ }
}

function handleControlResult(data) {
  cleanupTimers()
  sending.value = false
  pendingCmd.value = ''
  const success = data.result === 'success'
  lastResult.value = {
    type: success ? 'success' : 'error',
    text: success
      ? `指令「${data.command === 'on' ? '开灯' : '关灯'}」执行成功`
      : `指令执行失败: ${data.result || '未知错误'}`
  }
  // 仅手动指令才切换为手动模式、触发父组件刷新；自动联动不改变模式
  if (success && data.source !== 'auto') {
    props.device.controlMode = 'manual'
    emit('updated')
  }
  loadLogs()
}

// 同步 device prop → 本地状态
// ★ 修复：正在下发手动指令时不覆盖本地状态，防止 loadDevice() 返回的
//    旧值（DB 尚未提交 lightStatus）导致状态回退。
watch(() => props.device, (d) => {
  if (!d) return
  // 手动操作进行中时跳过同步，保持乐观更新的值
  if (!sending.value) { /* brightness removed */ }
  thresholdOn.value = d.thresholdOn ?? 50
  thresholdOff.value = d.thresholdOff ?? 100
  sensorStrategy.value = d.sensorStrategy || 'single'
  primarySensorId.value = d.primarySensorId ?? null
}, { immediate: true, deep: true })

// =============== 操作函数 ===============

async function toggleLight() {
  if (sending.value || props.device.status !== 'online' || props.device.lightStatus === 'unknown') return

  const newCmd = props.device.lightStatus === 'on' ? 'off' : 'on'
  const actionText = newCmd === 'on' ? '开灯' : '关灯'

  // 二次确认
  try {
    await ElMessageBox.confirm(
      `确定要${actionText}设备「${props.device.name || props.device.deviceId}」吗？`,
      `${actionText}确认`,
      { confirmButtonText: actionText, cancelButtonText: '取消', type: newCmd === 'on' ? 'warning' : 'info' }
    )
  } catch { return }

  sending.value = true
  pendingCmd.value = newCmd

  // 超时检测
  timeoutHandle = setTimeout(() => {
    if (sending.value) {
      sending.value = false
      pendingCmd.value = ''
      lastResult.value = { type: 'warning', text: `指令「${actionText}」超时，请检查设备在线状态` }
    }
  }, WS_TIMEOUT)

  try {
    await sendControl(props.device.deviceId, { command: newCmd })
    // 后端收到手动指令后会自动将设备切换为手动模式，前端同步
    props.device.controlMode = 'manual'
    // 如果 3 秒内未收到 WebSocket 结果，以 HTTP 成功为准
    fallbackHandle = setTimeout(() => {
      if (sending.value && pendingCmd.value === newCmd) {
        sending.value = false
        pendingCmd.value = ''
        cleanupTimers()
        props.device.lightStatus = newCmd
        props.device.controlMode = 'manual'
        lastResult.value = { type: 'success', text: `指令「${actionText}」已下发，已切换为手动模式` }
        loadLogs()
      }
    }, 3000)
  } catch (e) {
    sending.value = false
    pendingCmd.value = ''
    cleanupTimers()
    lastResult.value = { type: 'error', text: `指令下发失败: ${e.message || '网络错误'}` }
  }
}

async function handleModeChange(mode) {
  if (sending.value) return

  const modeText = mode === 'auto' ? '自动控制' : '手动控制'
  try {
    sending.value = true
    await setControlMode(props.device.id, { controlMode: mode })
    ElMessage.success(`已切换为${modeText}模式`)
    props.device.controlMode = mode
    emit('updated')
  } catch {
    // computed 自动从 props 同步，无需手动恢复
  } finally {
    sending.value = false
  }
}

async function applyThreshold() {
  if (sending.value || thresholdOn.value >= thresholdOff.value) return

  sending.value = true
  pendingCmd.value = 'threshold'

  try {
    await setThreshold(props.device.id, {
      thresholdOn: thresholdOn.value,
      thresholdOff: thresholdOff.value
    })
    ElMessage.success('阈值已保存')
    props.device.thresholdOn = thresholdOn.value
    props.device.thresholdOff = thresholdOff.value
    emit('updated')
  } catch {
    // 错误已拦截
  } finally {
    sending.value = false
    pendingCmd.value = ''
  }
}

// 已绑定的 light 类型传感器列表
const lightSensors = computed(() => {
  return (props.sensors || []).filter(s => s.sensorType === 'light')
})

// 开关旋钮标签
const switchLabel = computed(() => {
  if (props.device?.lightStatus === 'unknown' || props.device?.status === 'offline') return '状态未知'
  return props.device?.lightStatus === 'on' ? '已开灯' : '已关灯'
})

// 开关旋钮提示文字
const switchHint = computed(() => {
  if (props.device?.status === 'offline') return '设备已离线'
  if (props.device?.lightStatus === 'unknown') return '等待数据'
  return '点击' + (props.device?.lightStatus === 'on' ? '关灯' : '开灯')
})

// 灯光状态文字
function lightStatusText(device) {
  if (!device) return '○ 未知'
  if (device.status === 'offline' || device.lightStatus === 'unknown') return '○ 未知'
  return device.lightStatus === 'on' ? '● 运行中' : '○ 已关闭'
}

async function applySensorStrategy() {
  if (sending.value) return
  sending.value = true
  pendingCmd.value = 'strategy'
  try {
    await setSensorStrategy(props.device.id, {
      sensorStrategy: sensorStrategy.value,
      primarySensorId: sensorStrategy.value === 'single' ? primarySensorId.value : null
    })
    ElMessage.success('传感器决策策略已保存')
    props.device.sensorStrategy = sensorStrategy.value
    props.device.primarySensorId = sensorStrategy.value === 'single' ? primarySensorId.value : null
    emit('updated')
  } catch {
    // 错误已拦截
  } finally {
    sending.value = false
    pendingCmd.value = ''
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await getControlLogs(props.device.deviceId, { page: 0, size: 10 })
    controlLogs.value = res?.content || res?.data?.content || []
  } catch {
    controlLogs.value = []
  } finally {
    logsLoading.value = false
  }
}

// 当 deviceId 变为可用时自动加载操作记录（解决父组件异步加载导致的首次空白）
let logsLoaded = false
watch(() => props.device?.deviceId, (id) => {
  if (id && !logsLoaded) {
    logsLoaded = true
    loadLogs()
  }
}, { immediate: true })

onMounted(() => {
  connectWs()
  // 兜底：如果 deviceId 在 onMounted 时已有值（热更新场景）
  if (props.device?.deviceId && !logsLoaded) {
    logsLoaded = true
    loadLogs()
  }
})

onBeforeUnmount(() => {
  cleanupTimers()
  if (ws) {
    ws.onmessage = null
    ws.onclose = null
    ws.onerror = null
    ws.close()
    ws = null
  }
})
</script>

<style scoped>
.control-panel { margin-bottom: 16px; }

.panel-header {
  display: flex; justify-content: space-between; align-items: center;
}

.section-title {
  font-size: 14px; font-weight: 600; color: var(--text-primary); margin-bottom: 12px;
}
.switch-area { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 16px 0; }
.switch-knob {
  width: 120px; height: 120px; border-radius: 50%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  cursor: pointer; user-select: none; transition: all 0.3s ease;
  background: var(--el-fill-color-light); color: var(--text-muted);
  border: 3px solid var(--el-border-color);
}
.switch-knob.active {
  background: rgba(245,158,11,0.1); color: var(--amber);
  border-color: var(--amber);
  box-shadow: 0 0 20px rgba(245,158,11,0.25);
}
.switch-knob.disabled { cursor: not-allowed; opacity: 0.6; }
.switch-label { font-size: 13px; font-weight: 600; margin-top: 4px; }
.switch-hint { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.mode-group { margin-bottom: 8px; }
.mode-desc { font-size: 12px; color: var(--text-muted); margin: 8px 0; }
.threshold-area { padding: 12px 0; display: flex; flex-direction: column; gap: 12px; }
.form-hint { font-size: 12px; color: var(--text-muted); display: block; margin-top: 4px; }
.strategy-area { padding: 8px 0; }
.strategy-group { display: flex; flex-direction: column; gap: 8px; }
.strategy-desc { font-size: 12px; color: var(--text-muted); margin: 8px 0; }
</style>
