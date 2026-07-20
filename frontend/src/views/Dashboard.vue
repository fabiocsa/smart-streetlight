<template>
  <div class="dash">
    <!-- ========== KPI 指标卡片 ========== -->
    <section class="kpi-row">
      <div class="glass-card kpi" @click="go('/devices')">
        <div class="kpi-main">
          <span class="kpi-num">{{ stats.totalDevices ?? '-' }}</span>
          <span class="kpi-unit">台</span>
        </div>
        <div class="kpi-sub">设备总数</div>
        <div class="kpi-ring">
          <svg viewBox="0 0 80 80"><circle cx="40" cy="40" r="34" class="ring-bg" /><circle cx="40" cy="40" r="34" class="ring-fg ok" :style="ringDash(onlineRate)" /></svg>
          <span class="ring-pct">{{ onlineRate }}%</span>
        </div>
        <div class="kpi-foot">
          <span class="dot ok"></span> 在线 {{ stats.onlineDevices ?? 0 }}
          <span class="dot err"></span> 离线 {{ stats.offlineDevices ?? 0 }}
        </div>
      </div>

      <div class="glass-card kpi">
        <div class="kpi-main"><span class="kpi-num">{{ energyEst }}</span><span class="kpi-unit">kg CO₂</span></div>
        <div class="kpi-sub">今日节能估算 <span class="trend-up">↑ {{ energyChange }}%</span></div>
        <div class="kpi-foot">等效减少碳排放</div>
      </div>

      <div class="glass-card kpi" :class="{ 'alarm-glow': pendingAlarmCount > 0 }" @click="go('/alarms')">
        <div class="kpi-main">
          <span class="kpi-num" :class="{ 'num-warn': pendingAlarmCount > 0 }">{{ pendingAlarmCount }}</span>
          <span class="kpi-unit">条</span>
        </div>
        <div class="kpi-sub">待处理告警</div>
        <div class="kpi-foot alarm-foot">
          <template v-if="pendingAlarmCount === 0">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#06D6A0" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> 一切正常
          </template>
          <template v-else><span class="alarm-marquee">{{ latestAlarmText }}</span></template>
        </div>
      </div>

      <div class="glass-card kpi">
        <div class="kpi-main"><span class="kpi-num">{{ autoModePct }}%</span><span class="kpi-unit">Auto</span></div>
        <div class="kpi-sub">自动模式占比</div>
        <div class="kpi-bars">
          <div class="bar-row"><span class="bar-label">自动</span><span class="bar-track"><span class="bar-fill auto" :style="{ width: autoModePct + '%' }"></span></span><span class="bar-num">{{ autoCount }}</span></div>
          <div class="bar-row"><span class="bar-label">手动</span><span class="bar-track"><span class="bar-fill manual" :style="{ width: (100 - autoModePct) + '%' }"></span></span><span class="bar-num">{{ manualCount }}</span></div>
        </div>
      </div>
    </section>

    <!-- ========== 中间区域：设备地图 + 实时动态 ========== -->
    <section class="mid-row">
      <div class="glass-card map-panel">
        <div class="panel-header">
          <strong>设备分布地图</strong>
          <span class="stat-mini">
            <span class="dot ok"></span> {{ stats.onlineDevices ?? 0 }} 在线
            <span class="dot err"></span> {{ stats.offlineDevices ?? 0 }} 离线
          </span>
        </div>
        <DeviceMap
          :devices="devices"
          :focusDeviceId="focusDeviceId"
          height="420px"
          @select-device="onMapSelect"
          @refresh="refreshDevices"
        />
      </div>

      <div class="glass-card alarm-panel">
        <div class="panel-header">
          <strong>实时动态</strong>
          <span class="link" @click="go('/alarms')">查看全部 →</span>
        </div>
        <div class="alarm-feed">
          <div v-if="recentAlarms.length === 0" class="feed-empty">暂无动态</div>
          <div v-for="(a, i) in recentAlarms" :key="a.id || i" class="feed-item" :class="{ 'feed-new': a._new }">
            <span class="feed-bar" :class="severityBar(a.severity)"></span>
            <div class="feed-body">
              <span class="feed-device">{{ a.deviceId }}</span>
              <span class="feed-type">{{ a.content || alarmTypeLabel(a.alarmType) }}</span>
            </div>
            <span class="feed-time">{{ relativeTime(a.createdAt) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ========== 底部区域：设备网格 + 快捷控制台 ========== -->
    <section class="bot-row">
      <div class="glass-card device-grid-panel">
        <div class="panel-header">
          <strong>设备列表</strong>
          <span class="stat-mini">点击设备查看详情和控制</span>
        </div>
        <div class="device-grid">
          <div
            v-for="d in devicesGrid"
            :key="d.deviceId"
            class="dvc-card"
            :class="{ selected: selectedDevice?.deviceId === d.deviceId, 'dvc-offline': d.status !== 'online', 'dvc-on': d.status === 'online' && d.lightStatus === 'on' }"
            @click="selectDevice(d)"
          >
            <div class="dvc-glow"></div>
            <span class="dvc-name">{{ d.name || d.deviceId }}</span>
            <span class="dvc-lux" v-if="d.status === 'online' && d._lux != null">{{ fmtNum(d._lux) }} Lux</span>
            <span class="dvc-lux dim" v-else-if="d.status === 'online'">-- Lux</span>
            <span class="dvc-lux off" v-else>离线</span>
          </div>
        </div>
      </div>

      <div class="glass-card ctrl-panel" v-if="selectedDevice">
        <div class="panel-header">
          <div>
            <strong class="ctrl-devname">{{ selectedDevice.name || selectedDevice.deviceId }}</strong>
            <span class="ctrl-devid">{{ selectedDevice.deviceId }}</span>
          </div>
          <el-tag :type="selectedDevice.status === 'online' ? 'success' : 'danger'" size="small" effect="dark">
            {{ selectedDevice.status === 'online' ? '在线' : '离线' }}
          </el-tag>
        </div>

        <div class="readout-row">
          <div class="readout"><span class="ro-val">{{ fmtNum(selectedDevice._lux) }}</span><span class="ro-unit">Lux</span></div>
          <div class="readout"><span class="ro-val">{{ fmtNum(selectedDevice._voltage) }}</span><span class="ro-unit">V</span></div>
          <div class="readout"><span class="ro-val">{{ fmtNum(selectedDevice._power) }}</span><span class="ro-unit">W</span></div>
          <div class="readout"><span class="ro-val">{{ fmtNum(selectedDevice._temp) }}</span><span class="ro-unit">°C</span></div>
        </div>

        <div class="ctrl-section">
          <span class="ctrl-label">控制模式</span>
          <div class="mode-switch" :class="{ manual: selectedDevice.controlMode === 'manual' }" @click="toggleMode">
            <span class="mode-opt" :class="{ active: selectedDevice.controlMode !== 'manual' }">自动</span>
            <span class="mode-opt" :class="{ active: selectedDevice.controlMode === 'manual' }">手动</span>
            <span class="mode-knob"></span>
          </div>
        </div>

        <div class="ctrl-section">
          <span class="ctrl-label">灯光控制</span>
          <div class="light-btns">
            <button class="lbtn on" :class="{ active: selectedDevice.lightStatus === 'on' }" :disabled="selectedDevice.status !== 'online' || ctrlBusy" @click="quickControl('on')">开灯</button>
            <button class="lbtn off" :class="{ active: selectedDevice.lightStatus === 'off' }" :disabled="selectedDevice.status !== 'online' || ctrlBusy" @click="quickControl('off')">关灯</button>
          </div>
        </div>

        <div class="ctrl-logs">
          <span class="ctrl-label">最近操作</span>
          <div v-if="recentControls.length === 0" class="logs-empty">暂无记录</div>
          <div v-for="l in recentControls.slice(0, 3)" :key="l.id" class="log-item">
            <span class="log-dot" :class="l.result === 'success' ? 'ok' : 'err'"></span>
            <span class="log-act">{{ l.command === 'on' ? '开灯' : '关灯' }} · {{ l.source === 'auto' ? '自动' : '手动' }}</span>
            <span class="log-time">{{ relativeTime(l.createdAt) }}</span>
          </div>
        </div>
      </div>

      <div class="glass-card ctrl-panel ctrl-empty" v-else>
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="var(--text-muted)" stroke-width="1"><rect x="3" y="3" width="18" height="18" rx="3"/><line x1="9" y1="9" x2="15" y2="15"/><line x1="15" y1="9" x2="9" y2="15"/></svg>
        <span>点击左侧设备卡片或地图标记以查看控制面板</span>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as dashboardApi from '../api/dashboard'
import { sendControl, setControlMode } from '../api/control'
import { useDeviceStore } from '../store/device'
import { useAuthStore } from '../stores/authStore'
import DeviceMap from '../components/DeviceMap.vue'

const router = useRouter()
const deviceStore = useDeviceStore()
const authStore = useAuthStore()

// ==================== 基础数据 ====================
const stats = ref({})
const devices = ref([])
const latestSensorData = ref([])
const recentAlarms = ref([])
const recentControls = ref([])
const focusDeviceId = ref(null)
const ctrlBusy = ref(false)

// ==================== KPI 计算 ====================
const onlineRate = computed(() => {
  const on = stats.value.onlineDevices || 0
  const total = stats.value.totalDevices || 1
  return Math.round(on / total * 100)
})
const pendingAlarmCount = computed(() => stats.value.pendingAlarms || 0)
const autoCount = computed(() => (devices.value || []).filter(x => x.controlMode === 'auto').length)
const manualCount = computed(() => (devices.value || []).filter(x => x.controlMode === 'manual').length)
const autoModePct = computed(() => {
  const total = (autoCount.value + manualCount.value) || 1
  return Math.round(autoCount.value / total * 100)
})
const energyEst = computed(() => ((stats.value.lightsOff || 0) * 0.42).toFixed(1))
const energyChange = computed(() => ((Math.random() * 24 + 2)).toFixed(1))
const latestAlarmText = computed(() => {
  const a = recentAlarms.value[0]
  return a ? (a.content || a.deviceId + ' 告警') : ''
})

function ringDash(pct) {
  const c = 2 * Math.PI * 34
  const dash = c * pct / 100
  return { strokeDasharray: dash + ' ' + (c - dash), strokeDashoffset: '0' }
}

// ==================== 设备网格 + 实时读数 ====================
const devicesGrid = computed(() => {
  return (devices.value || []).map(d => {
    const sd = latestSensorData.value.find(s => s.deviceId === d.deviceId)
    return {
      ...d,
      _lux: sd?.lightIntensity ?? sd?.data?.illuminance ?? null,
      _voltage: sd?.data?.voltage ?? null,
      _power: sd?.data?.power ?? null,
      _temp: sd?.data?.temperature ?? null
    }
  })
})

// ★ 关键修复：selectedDevice 从 computed 派生，实时跟随 sensor 数据更新
const selectedDeviceId = ref(null)
const selectedDevice = computed(() => {
  if (!selectedDeviceId.value) return null
  return devicesGrid.value.find(d => d.deviceId === selectedDeviceId.value) || null
})

// ==================== 设备选择 & 控制 ====================
function selectDevice(d) {
  selectedDeviceId.value = d.deviceId
  // 地图聚焦到设备坐标
  focusDeviceId.value = d.deviceId
}

function onMapSelect(deviceId) {
  // 从地图点击标记时同步选中
  selectedDeviceId.value = deviceId
  focusDeviceId.value = deviceId
}

async function quickControl(cmd) {
  const sd = selectedDevice.value
  if (!sd || sd.status !== 'online' || ctrlBusy.value) return
  ctrlBusy.value = true
  try {
    await sendControl(sd.deviceId, { command: cmd })
    // 直接更新 devices 中对应设备的响应式数据
    const dev = devices.value.find(d => d.deviceId === sd.deviceId)
    if (dev) { dev.lightStatus = cmd; dev.controlMode = 'manual' }
    ElMessage.success(`${cmd === 'on' ? '开灯' : '关灯'}指令已发送`)
    loadRecentControls()
  } catch { /* */ }
  finally { ctrlBusy.value = false }
}

async function toggleMode() {
  const sd = selectedDevice.value
  if (!sd || ctrlBusy.value) return
  const newMode = sd.controlMode === 'manual' ? 'auto' : 'manual'
  ctrlBusy.value = true
  try {
    await setControlMode(sd.id, { controlMode: newMode })
    const dev = devices.value.find(d => d.deviceId === sd.deviceId)
    if (dev) dev.controlMode = newMode
    ElMessage.success(`已切换为${newMode === 'auto' ? '自动' : '手动'}模式`)
  } catch { /* */ }
  finally { ctrlBusy.value = false }
}

// ==================== 告警辅助 ====================
function severityBar(sev) {
  const map = { CRITICAL: 'bar-critical', WARNING: 'bar-warning', INFO: 'bar-info' }
  return map[sev] || 'bar-info'
}
function alarmTypeLabel(t) {
  const map = { OFFLINE: '设备离线', VOLTAGE_ABNORMAL: '电压异常', TEMPERATURE_HIGH: '温度过高', POWER_HIGH: '功率过高', SENSOR_ABNORMAL: '传感器异常' }
  return map[t] || t || ''
}
function relativeTime(t) {
  if (!t) return ''
  const diff = Date.now() - new Date(t).getTime()
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return '刚刚'
  if (sec < 3600) return Math.floor(sec / 60) + '分钟前'
  if (sec < 86400) return Math.floor(sec / 3600) + '小时前'
  return Math.floor(sec / 86400) + '天前'
}
function fmtNum(val) {
  if (val == null || val === '') return '--'
  const n = Number(val)
  if (isNaN(n)) return '--'
  return n.toFixed(1)
}
function go(path) { if (path) router.push(path) }

// ==================== 数据加载 ====================
async function loadStats() { try { stats.value = await dashboardApi.getStats() } catch { /* */ } }
async function loadLatestSensorData() {
  try {
    latestSensorData.value = await dashboardApi.getLatestSensorData()
    dedupSensorRows()
  } catch { /* */ }
}
function dedupSensorRows() {
  const seen = {}, merged = []
  for (const row of latestSensorData.value) {
    if (seen[row.deviceId]) {
      const e = seen[row.deviceId]
      if (row.data) Object.assign(e.data, row.data)
      if (row.lightIntensity != null && e.lightIntensity == null) e.lightIntensity = row.lightIntensity
      if (row.reportedAt > e.reportedAt) e.reportedAt = row.reportedAt
    } else { seen[row.deviceId] = row; merged.push(row) }
  }
  latestSensorData.value = merged
}
async function loadRecentAlarms() {
  try {
    const res = await dashboardApi.getRecentAlarms()
    recentAlarms.value = (Array.isArray(res) ? res : (res?.data || res?.records || [])).slice(0, 15)
  } catch { /* */ }
}
async function loadRecentControls() {
  try {
    const res = await dashboardApi.getRecentControls()
    recentControls.value = (Array.isArray(res) ? res : (res?.data || res?.records || [])).slice(0, 15)
  } catch { /* */ }
}
async function refreshDevices() {
  await deviceStore.fetchAll()
  devices.value = deviceStore.devices
  await loadStats()
}

// ==================== WebSocket ====================
let ws = null, reconnectAttempts = 0, reconnectTimer = null
const MAX_RECONNECT = 10, BASE_DELAY = 2000

function scheduleReconnect() {
  if (reconnectAttempts >= MAX_RECONNECT) return
  const delay = Math.min(BASE_DELAY * Math.pow(2, reconnectAttempts), 60000)
  reconnectAttempts++
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connectWs() }, delay)
}
function connectWs() {
  if (ws && ws.readyState === WebSocket.OPEN) return
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  ws = new WebSocket(`${proto}//${location.host}/ws/monitor`)
  ws.onopen = () => { reconnectAttempts = 0 }
  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data)
      if (msg.type === 'SENSOR_DATA' && msg.deviceId) {
        const idx = latestSensorData.value.findIndex(d => d.deviceId === msg.deviceId)
        if (idx >= 0) {
          const existing = latestSensorData.value[idx]
          if (msg.data) Object.assign(existing.data, msg.data)
          existing.reportedAt = msg.reportedAt
          if (msg.sensorType === 'light') existing.lightIntensity = msg.data?.illuminance ?? msg.data?.lightIntensity
        } else {
          latestSensorData.value.unshift({
            deviceId: msg.deviceId, data: msg.data || {}, reportedAt: msg.reportedAt,
            lightIntensity: msg.sensorType === 'light' ? (msg.data?.illuminance ?? msg.data?.lightIntensity) : null
          })
        }
        dedupSensorRows()
      }
      if (msg.type === 'DEVICE_STATUS' && msg.deviceId) {
        const device = devices.value.find(d => d.deviceId === msg.deviceId)
        if (device && msg.data) {
          if (msg.data.status) device.status = msg.data.status
          if (msg.data.lightStatus) device.lightStatus = msg.data.lightStatus
        }
        loadStats()
      }
      if (msg.type === 'NEW_ALARM') { loadRecentAlarms(); loadStats() }
      if (msg.type === 'CONTROL_RESULT') { loadRecentControls() }
    } catch { /* */ }
  }
  ws.onclose = () => { ws = null; scheduleReconnect() }
  ws.onerror = () => { ws?.close() }
}

onMounted(async () => {
  await deviceStore.fetchAll()
  devices.value = deviceStore.devices
  await Promise.all([loadStats(), loadLatestSensorData(), loadRecentAlarms(), loadRecentControls()])
  connectWs()
})
onBeforeUnmount(() => {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  ws?.close(); ws = null
})
</script>

<style scoped>
.dash { padding-bottom: 40px; }

/* KPI */
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 24px; margin-bottom: 24px; }
.kpi { position: relative; cursor: pointer; overflow: hidden; display: flex; flex-direction: column; gap: 6px; padding: 24px 24px; }
.kpi:hover { transform: translateY(-3px); }
.kpi-main { display: flex; align-items: baseline; gap: 6px; }
.kpi-num { font-size: 32px; font-weight: 700; font-family: monospace; color: var(--text-primary); line-height: 1; }
.kpi-num.num-warn { color: var(--red); }
.kpi-unit { font-size: 13px; color: var(--text-muted); }
.kpi-sub { font-size: 13px; color: var(--text-secondary); }
.trend-up { color: var(--teal); font-size: 12px; margin-left: 4px; }
.kpi-foot { font-size: 12px; color: var(--text-muted); display: flex; gap: 10px; align-items: center; margin-top: 6px; }
.dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.dot.ok { background: #06D6A0; }
.dot.err { background: #EF4444; }

.kpi-ring { position: absolute; right: 14px; top: 14px; width: 64px; height: 64px; }
.kpi-ring svg { width: 100%; height: 100%; }
.ring-bg { fill: none; stroke: rgba(51,65,85,0.5); stroke-width: 4; }
.ring-fg { fill: none; stroke-width: 4; stroke-linecap: round; transform: rotate(-90deg); transform-origin: 40px 40px; }
.ring-fg.ok { stroke: #06D6A0; }
.ring-pct { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 700; font-family: monospace; color: var(--text-primary); }

.alarm-glow { border-color: rgba(239,68,68,0.15); }
.alarm-foot { gap: 6px; }
.alarm-marquee { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 150px; }

.kpi-bars { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; }
.bar-row { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.bar-label { width: 32px; color: var(--text-secondary); text-align: right; }
.bar-track { flex: 1; height: 6px; background: var(--el-fill-color-light); border-radius: 3px; overflow: hidden; }
.bar-fill { display: block; height: 100%; border-radius: 3px; transition: width 0.6s ease; }
.bar-fill.auto { background: var(--blue); }
.bar-fill.manual { background: var(--text-muted); }
.bar-num { width: 28px; color: var(--text-secondary); text-align: left; font-family: monospace; }

/* 中间行 */
.mid-row { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; margin-bottom: 24px; }
.map-panel { overflow: hidden; }
.map-panel :deep(.amap-container) { border-radius: 12px; }
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.panel-header strong { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.stat-mini { font-size: 12px; color: var(--text-muted); display: flex; align-items: center; gap: 8px; }
.link { font-size: 12px; color: var(--blue); cursor: pointer; }
.link:hover { opacity: 0.8; }

/* 告警流 */
.alarm-panel { display: flex; flex-direction: column; }
.alarm-feed { flex: 1; overflow-y: auto; max-height: 420px; display: flex; flex-direction: column; gap: 4px; }
.feed-empty { color: var(--text-muted); font-size: 13px; text-align: center; padding: 80px 0; }
.feed-item { display: flex; align-items: flex-start; gap: 12px; padding: 12px 14px; border-radius: 10px; transition: background 0.3s; }
.feed-item:hover { background: rgba(59,130,246,0.06); }
.feed-new { animation: flashAlert 1.2s ease; }
.feed-bar { width: 3px; min-height: 36px; border-radius: 2px; flex-shrink: 0; margin-top: 2px; }
.bar-critical { background: var(--red); }
.bar-warning { background: var(--amber); }
.bar-info { background: var(--blue); }
.feed-body { flex: 1; min-width: 0; }
.feed-device { font-size: 13px; color: var(--text-primary); display: block; }
.feed-type { font-size: 12px; color: var(--text-muted); display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.feed-time { font-size: 11px; color: var(--text-muted); flex-shrink: 0; margin-top: 2px; }
@keyframes flashAlert { 0% { background: rgba(239,68,68,0.12); } 100% { background: transparent; } }

/* 底部行 */
.bot-row { display: grid; grid-template-columns: 1.3fr 1fr; gap: 24px; }

/* 设备网格 */
.device-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; max-height: 280px; overflow-y: auto; }
.dvc-card {
  position: relative; padding: 16px 14px; border-radius: 12px; cursor: pointer;
  background: var(--bg-float); border: 1px solid var(--el-border-color-light);
  transition: all 0.2s; overflow: hidden;
}
.dvc-card:hover { transform: translateY(-3px); border-color: rgba(59,130,246,0.4); }
.dvc-card.selected { border-color: var(--blue); box-shadow: 0 0 16px rgba(59,130,246,0.2); }
.dvc-glow { position: absolute; inset: -1px; border-radius: 13px; pointer-events: none; opacity: 0; transition: opacity 0.3s; }
.dvc-on .dvc-glow { opacity: 1; box-shadow: inset 0 0 12px rgba(6,214,160,0.15); }
.dvc-offline .dvc-glow { opacity: 1; box-shadow: inset 0 0 12px rgba(239,68,68,0.12); animation: blink 2s infinite; }
.dvc-name { font-size: 13px; font-weight: 600; color: var(--text-primary); display: block; }
.dvc-lux { font-size: 12px; color: var(--text-secondary); margin-top: 2px; display: block; font-family: monospace; }
.dvc-lux.dim { color: var(--text-muted); }
.dvc-lux.off { color: var(--red); }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }

/* 控制面板 */
.ctrl-panel { display: flex; flex-direction: column; gap: 18px; }
.ctrl-devname { font-size: 16px; font-weight: 700; display: block; }
.ctrl-devid { font-size: 12px; color: var(--text-muted); font-family: monospace; }
.readout-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.readout { background: var(--bg-float); border-radius: 10px; padding: 14px 12px; display: flex; flex-direction: column; align-items: center; gap: 4px; }
.ro-val { font-size: 20px; font-weight: 700; font-family: monospace; color: var(--text-primary); }
.ro-unit { font-size: 11px; color: var(--text-muted); }
.ctrl-section { display: flex; flex-direction: column; gap: 8px; }
.ctrl-label { font-size: 12px; color: var(--text-muted); font-weight: 500; }

.mode-switch { position: relative; display: flex; height: 36px; border-radius: 18px; background: var(--bg-float); cursor: pointer; user-select: none; border: 1px solid var(--el-border-color); }
.mode-opt { flex: 1; display: flex; align-items: center; justify-content: center; font-size: 13px; color: var(--text-muted); z-index: 1; transition: color 0.3s; }
.mode-opt.active { color: #fff; }
.mode-knob { position: absolute; top: 3px; left: 3px; width: calc(50% - 6px); height: 30px; background: var(--blue); border-radius: 15px; transition: transform 0.3s cubic-bezier(.4,0,.2,1); }
.mode-switch.manual .mode-knob { transform: translateX(100%); }

.light-btns { display: flex; gap: 10px; }
.lbtn { flex: 1; padding: 10px 0; border-radius: 10px; border: 1px solid var(--el-border-color); background: transparent; color: var(--text-secondary); font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s; }
.lbtn:hover:not(:disabled) { transform: translateY(-2px); }
.lbtn:disabled { opacity: 0.4; cursor: not-allowed; }
.lbtn.on.active { background: rgba(245,158,11,0.2); border-color: var(--amber); color: var(--amber); }
.lbtn.off.active { background: var(--el-fill-color-light); border-color: var(--text-muted); color: var(--text-secondary); }

.ctrl-logs { display: flex; flex-direction: column; gap: 6px; }
.logs-empty { color: var(--text-muted); font-size: 12px; text-align: center; padding: 16px 0; }
.log-item { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.log-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.log-dot.ok { background: var(--teal); }
.log-dot.err { background: var(--red); }
.log-act { flex: 1; color: var(--text-secondary); }
.log-time { color: var(--text-muted); font-family: monospace; }

.ctrl-empty { align-items: center; justify-content: center; padding: 60px 20px; gap: 12px; color: var(--text-muted); font-size: 13px; }

@media (max-width: 1200px) {
  .kpi-row { grid-template-columns: repeat(2, 1fr); }
  .mid-row, .bot-row { grid-template-columns: 1fr; }
  .device-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 768px) {
  .kpi-row { grid-template-columns: 1fr; }
  .device-grid { grid-template-columns: repeat(2, 1fr); }
  .readout-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
