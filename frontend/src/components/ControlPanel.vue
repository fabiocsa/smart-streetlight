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
          <el-tooltip content="点击切换灯光开关" placement="top">
            <div
              class="switch-knob"
              :class="{ active: device.lightStatus === 'on', disabled: sending }"
              @click="toggleLight"
            >
              <el-icon :size="36">
                <Sunny v-if="device.lightStatus === 'on'" />
                <Moon v-else />
              </el-icon>
              <span class="switch-label">{{ device.lightStatus === 'on' ? '已开灯' : '已关灯' }}</span>
              <span class="switch-hint">点击{{ device.lightStatus === 'on' ? '关灯' : '开灯' }}</span>
            </div>
          </el-tooltip>

          <div class="switch-status">
            <el-tag :type="device.lightStatus === 'on' ? 'warning' : 'info'" size="large">
              {{ device.lightStatus === 'on' ? '● 运行中' : '○ 已关闭' }}
            </el-tag>
          </div>
        </div>

        <!-- 亮度调节 -->
        <div class="section-title" style="margin-top: 20px">亮度调节</div>
        <div class="brightness-area">
          <el-slider
            v-model="brightness"
            :min="0"
            :max="100"
            :step="5"
            :disabled="device.lightStatus !== 'on' || sending"
            show-input
            :format-tooltip="(v) => v + '%'"
            style="padding: 0 12px"
          />
          <el-button
            type="primary"
            :disabled="device.lightStatus !== 'on' || sending"
            :loading="sending && pendingCmd === 'brightness'"
            @click="applyBrightness"
            style="margin-top: 8px"
          >
            应用亮度
          </el-button>
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
import { ref, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Sunny, Moon, Setting, User, Refresh } from '@element-plus/icons-vue'
import { sendControl, setControlMode, setThreshold, getControlLogs } from '../api/control'
import { formatTime, debounce } from '../utils/common'

const props = defineProps({
  device: { type: Object, required: true }
})

const emit = defineEmits(['updated'])

// 控制状态
const sending = ref(false)
const pendingCmd = ref('')       // 当前等待的指令类型
const lastResult = ref(null)      // 最近操作结果 { type, text }
const brightness = ref(50)
const controlMode = ref('auto')
const thresholdOn = ref(50)
const thresholdOff = ref(100)

// 日志
const controlLogs = ref([])
const logsLoading = ref(false)

// 订阅 WebSocket 控制结果反馈（实时更新 UI）
let ws = null
const WS_TIMEOUT = 8000
let timeoutHandle = null

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
  if (timeoutHandle) { clearTimeout(timeoutHandle); timeoutHandle = null }
  sending.value = false
  pendingCmd.value = ''
  const success = data.result === 'success'
  lastResult.value = {
    type: success ? 'success' : 'error',
    text: success
      ? `指令「${data.command === 'on' ? '开灯' : '关灯'}」执行成功`
      : `指令执行失败: ${data.result || '未知错误'}`
  }
  // 后端手动指令会自动切为手动模式，前端同步
  if (success) {
    controlMode.value = 'manual'
    props.device.controlMode = 'manual'
  }
  // 刷新日志
  loadLogs()
  // 通知父组件刷新
  if (success) emit('updated')
}

// 同步 device prop → 本地状态
// ★ 修复：正在下发手动指令时不覆盖本地状态，防止 loadDevice() 返回的
//    旧值（DB 尚未提交 lightStatus）导致状态回退。
watch(() => props.device, (d) => {
  if (!d) return
  // 手动操作进行中时跳过同步，保持乐观更新的值
  if (!sending.value) {
    controlMode.value = d.controlMode || 'auto'
    brightness.value = d.brightness ?? 50
  }
  thresholdOn.value = d.thresholdOn ?? 50
  thresholdOff.value = d.thresholdOff ?? 100
}, { immediate: true, deep: true })

// =============== 操作函数 ===============

async function toggleLight() {
  if (sending.value || props.device.status !== 'online') return

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
    await sendControl(props.device.deviceId, {
      command: newCmd,
      brightness: newCmd === 'on' ? brightness.value : null
    })
    // ★ 修复：后端收到手动指令后会自动将设备切换为手动模式，
    // 前端同步更新 controlMode 以保持一致
    controlMode.value = 'manual'
    props.device.controlMode = 'manual'
    // 如果 3 秒内未收到 WebSocket 结果，认为指令已下发等待反馈
    setTimeout(() => {
      if (sending.value && pendingCmd.value === newCmd) {
        // WebSocket 未及时返回，以 HTTP 成功为准（不触发 emit('updated')，避免 stale reload）
        sending.value = false
        pendingCmd.value = ''
        if (timeoutHandle) { clearTimeout(timeoutHandle); timeoutHandle = null }
        props.device.lightStatus = newCmd
        props.device.controlMode = 'manual'
        lastResult.value = { type: 'success', text: `指令「${actionText}」已下发，已切换为手动模式` }
        // ★ 修复: 不再触发 emit('updated') → loadDevice()，避免从 DB 读到旧 lightStatus 导致回退
        loadLogs()
      }
    }, 3000)
  } catch (e) {
    sending.value = false
    pendingCmd.value = ''
    if (timeoutHandle) { clearTimeout(timeoutHandle); timeoutHandle = null }
    lastResult.value = { type: 'error', text: `指令下发失败: ${e.message || '网络错误'}` }
  }
}

async function applyBrightness() {
  if (sending.value || props.device.lightStatus !== 'on') return

  sending.value = true
  pendingCmd.value = 'brightness'

  try {
    await sendControl(props.device.deviceId, { command: 'on', brightness: brightness.value })
    // ★ 修复：亮度调节也是手动指令，后端会切换为手动模式
    controlMode.value = 'manual'
    props.device.controlMode = 'manual'
    props.device.brightness = brightness.value
    ElMessage.success(`亮度已设置为 ${brightness.value}%，已切换为手动模式`)
    emit('updated')
  } catch {
    // 错误已拦截
  } finally {
    sending.value = false
    pendingCmd.value = ''
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
    // 恢复原值
    controlMode.value = props.device.controlMode || 'auto'
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

onMounted(() => {
  connectWs()
  loadLogs()
})
</script>

<style scoped>
.control-panel { margin-bottom: 16px; }

.panel-header {
  display: flex; justify-content: space-between; align-items: center;
}

.section-title {
  font-size: 14px; font-weight: 600; color: #606266; margin-bottom: 12px;
}

.switch-area {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 16px 0;
}

.switch-knob {
  width: 120px; height: 120px;
  border-radius: 50%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  cursor: pointer; user-select: none;
  transition: all 0.3s ease;
  background: #f0f2f5; color: #909399;
  border: 3px solid #dcdfe6;
}

.switch-knob.active {
  background: #fdf6ec; color: #e6a23c;
  border-color: #e6a23c;
  box-shadow: 0 0 20px rgba(230, 162, 60, 0.3);
}

.switch-knob.disabled {
  cursor: not-allowed; opacity: 0.6;
}

.switch-label {
  font-size: 13px; font-weight: 600; margin-top: 4px;
}

.switch-hint {
  font-size: 11px; color: #c0c4cc; margin-top: 2px;
}

.brightness-area {
  padding: 8px 0;
}

.mode-group {
  margin-bottom: 8px;
}

.mode-desc {
  font-size: 12px; color: #909399; margin: 8px 0;
}

.threshold-area {
  padding: 8px 0;
}

.form-hint {
  font-size: 12px; color: #c0c4cc; margin-left: 8px;
}
</style>
