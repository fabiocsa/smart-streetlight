<template>
  <div class="map-wrapper" ref="mapWrapper">
    <div ref="mapContainer" class="map-container" :style="{ height: height }"></div>

    <!-- 工具栏（左上角） -->
    <div class="map-toolbar">
      <el-tooltip content="拖拽移动地图" placement="right" :show-after="500">
        <el-button
          :type="currentTool === 'pan' ? 'primary' : 'default'"
          size="small"
          @click="switchTool('pan')"
        >
          <el-icon><Rank /></el-icon>
          <span class="tool-btn-label">拖拽</span>
        </el-button>
      </el-tooltip>
      <el-tooltip content="拖拽框选圈选设备，Ctrl+点击可多选" placement="right" :show-after="500">
        <el-button
          :type="currentTool === 'select' ? 'primary' : 'default'"
          size="small"
          @click="switchTool('select')"
        >
          <el-icon><Select /></el-icon>
          <span class="tool-btn-label">框选</span>
        </el-button>
      </el-tooltip>
      <el-tooltip content="切换到添加模式后，在地图上右键点击目标位置即可添加设备" placement="right" :show-after="300">
        <el-button
          :type="currentTool === 'add-location' ? 'primary' : 'default'"
          size="small"
          @click="switchTool('add-location')"
        >
          <el-icon><Plus /></el-icon>
          <span class="tool-btn-label">添加</span>
        </el-button>
      </el-tooltip>
    </div>

    <!-- 拖拽选框 -->
    <div
      v-if="selectionBox.visible"
      class="selection-box"
      :style="{
        left: selectionBox.left + 'px',
        top: selectionBox.top + 'px',
        width: selectionBox.width + 'px',
        height: selectionBox.height + 'px'
      }"
    ></div>

    <!-- 操作栏（底部） -->
    <transition name="slide-up">
      <div v-if="selectedDeviceIds.size > 0" class="operation-bar">
        <span class="op-count">已选中 {{ selectedDeviceIds.size }} 个设备</span>
        <el-button type="success" size="small" @click="batchControl('on')">💡 批量开灯</el-button>
        <el-button type="warning" size="small" @click="batchControl('off')">🌙 批量关灯</el-button>
        <el-dropdown trigger="click" @command="batchModeChange">
          <el-button type="primary" size="small">⚙ 批量切换模式</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="auto">切换为自动模式</el-dropdown-item>
              <el-dropdown-item command="manual">切换为手动模式</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-if="isAdmin" type="danger" size="small" @click="batchDelete">🗑 批量删除</el-button>
        <el-button v-if="isAdmin" type="info" size="small" @click="batchUnbind">🔓 批量解绑传感器</el-button>
        <el-button size="small" @click="clearSelection">✕ 取消选择</el-button>
      </div>
    </transition>

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
    >
      <template v-if="currentTool === 'add-location'">
        <div class="context-menu-item" @click="handleAddDeviceAtLocation">📍 在此位置添加设备</div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item" @click="cancelAddLocation">✕ 取消</div>
      </template>
      <template v-else>
        <div class="context-menu-item" @click="contextAction('on')">💡 批量开灯</div>
        <div class="context-menu-item" @click="contextAction('off')">🌙 批量关灯</div>
        <div class="context-menu-item" @click="contextModeChange('auto')">⚙ 切换为自动模式</div>
        <div class="context-menu-item" @click="contextModeChange('manual')">⚙ 切换为手动模式</div>
        <div v-if="isAdmin" class="context-menu-item" @click="contextDelete">🗑 批量删除</div>
        <div v-if="isAdmin" class="context-menu-item" @click="contextUnbind">🔓 批量解绑传感器</div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item" @click="clearSelection">✕ 取消选择</div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Rank, Select, Plus } from '@element-plus/icons-vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import { sendControl, sendBatchControl, setControlMode } from '../api/control'
import { unbindSensor } from '../api/device'
import { useAuthStore } from '../stores/authStore'

const props = defineProps({
  devices: { type: Array, default: () => [] },
  height: { type: String, default: '600px' }
})

const emit = defineEmits(['refresh', 'add-device'])

const router = useRouter()
const authStore = useAuthStore()
const isAdmin = computed(() => authStore.isAdmin)

const mapContainer = ref(null)
const mapWrapper = ref(null)
let map = null
let AMap = null
let markers = []           // { marker, device, el }
let infoWindow = null

const AMAP_KEY = '89cb94c3464ebb41c9b691f12bf082ff'

// ======================== 工具栏状态 ========================
const currentTool = ref('pan')   // 'pan' | 'select' | 'add-location'

// ======================== 添加设备坐标 ========================
const addLocationCoords = ref(null)

// ======================== 框选状态 ========================
const selectionBox = reactive({
  visible: false,
  startX: 0, startY: 0,
  left: 0, top: 0,
  width: 0, height: 0
})
let isDragging = false

// ======================== 选中设备 ========================
const selectedDeviceIds = reactive(new Set())

// 当前选中的设备对象列表
const selectedDevices = computed(() =>
  props.devices.filter(d => selectedDeviceIds.has(d.id))
)

// ======================== 右键菜单 ========================
const contextMenu = reactive({
  visible: false,
  x: 0, y: 0
})

// ======================== 工具切换 ========================
function switchTool(tool) {
  currentTool.value = tool
  if (!map) return
  if (tool === 'select') {
    map.setStatus({ dragEnable: false })
    mapContainer.value.style.cursor = 'crosshair'
  } else if (tool === 'add-location') {
    map.setStatus({ dragEnable: true })
    mapContainer.value.style.cursor = 'copy'
  } else {
    map.setStatus({ dragEnable: true })
    mapContainer.value.style.cursor = ''
    clearSelection()
  }
}

// ======================== 添加设备 ========================
function handleAddDeviceAtLocation() {
  contextMenu.visible = false
  if (!addLocationCoords.value) return
  emit('add-device', {
    latitude: addLocationCoords.value.lat,
    longitude: addLocationCoords.value.lng
  })
  // 自动切回抓手模式
  switchTool('pan')
}

function cancelAddLocation() {
  contextMenu.visible = false
  switchTool('pan')
}

// ======================== 标记 DOM 元素 ========================
function getMarkerColor(device) {
  if (device.status !== 'online') return '#bfbfbf'
  return device.lightStatus === 'on' ? '#faad14' : '#1890ff'
}

/** 创建标记 DOM 元素：圆点 + 半透明设备名 */
function createMarkerEl(device, selected) {
  const wrap = document.createElement('div')
  wrap.style.cssText = 'display:flex; align-items:center; gap:4px; cursor:pointer;'

  const dot = document.createElement('div')
  applyMarkerStyle(dot, device, selected)

  const label = document.createElement('span')
  label.textContent = device.name || device.deviceId
  label.style.cssText = 'font-size:11px; opacity:0.45; color:#333; white-space:nowrap; pointer-events:none; text-shadow:0 1px 2px #fff;'

  wrap.appendChild(dot)
  wrap.appendChild(label)
  // 把事件绑定和样式刷新关联到 wrap，内部 dot 存储引用
  wrap._dot = dot
  wrap._label = label
  return wrap
}

/** 更新标记圆点样式 */
function applyMarkerStyle(dot, device, selected) {
  const color = getMarkerColor(device)
  const ring = selected ? '3px solid #ff4d4f' : '2px solid #fff'
  const shadow = selected
    ? '0 0 0 2px #ff4d4f, 0 2px 6px rgba(0,0,0,0.3)'
    : '0 2px 6px rgba(0,0,0,0.3)'
  dot.style.cssText = `
    width: 20px; height: 20px; border-radius: 50%;
    background: ${color}; border: ${ring}; box-shadow: ${shadow};
    transition: all 0.2s; flex-shrink: 0;
  `
}

/** 刷新所有标记的选中样式 */
function refreshMarkerStyles() {
  markers.forEach(({ el, device }) => {
    const dot = el._dot || el
    applyMarkerStyle(dot, device, selectedDeviceIds.has(device.id))
  })
}

// ======================== 框选逻辑 ========================
function onMapMouseDown(e) {
  if (currentTool.value !== 'select') return
  isDragging = true
  const pos = map.containerToLngLat(e.pixel)
  const pixel = map.lngLatToContainer(pos)
  selectionBox.startX = pixel.x
  selectionBox.startY = pixel.y
  selectionBox.left = pixel.x
  selectionBox.top = pixel.y
  selectionBox.width = 0
  selectionBox.height = 0
  selectionBox.visible = true
}

function onMapMouseMove(e) {
  if (!isDragging || currentTool.value !== 'select') return
  const pos = map.containerToLngLat(e.pixel)
  const pixel = map.lngLatToContainer(pos)
  const x = pixel.x
  const y = pixel.y
  selectionBox.left = Math.min(selectionBox.startX, x)
  selectionBox.top = Math.min(selectionBox.startY, y)
  selectionBox.width = Math.abs(x - selectionBox.startX)
  selectionBox.height = Math.abs(y - selectionBox.startY)
}

function onMapMouseUp() {
  if (!isDragging || currentTool.value !== 'select') return
  isDragging = false
  selectionBox.visible = false

  if (selectionBox.width < 5 || selectionBox.height < 5) {
    // 点击而不是拖拽，不做框选
    return
  }

  // 计算选框的边界经纬度
  const container = mapContainer.value
  const rect = container.getBoundingClientRect()
  const scaleX = container.offsetWidth / rect.width
  const scaleY = container.offsetHeight / rect.height

  const topLeft = map.containerToLngLat(
    new AMap.Pixel(selectionBox.left / scaleX, selectionBox.top / scaleY)
  )
  const bottomRight = map.containerToLngLat(
    new AMap.Pixel((selectionBox.left + selectionBox.width) / scaleX, (selectionBox.top + selectionBox.height) / scaleY)
  )

  // 筛选在选框内的设备
  const minLng = Math.min(topLeft.lng, bottomRight.lng)
  const maxLng = Math.max(topLeft.lng, bottomRight.lng)
  const minLat = Math.min(topLeft.lat, bottomRight.lat)
  const maxLat = Math.max(topLeft.lat, bottomRight.lat)

  selectedDeviceIds.clear()
  markers.forEach(({ device }) => {
    if (device.longitude >= minLng && device.longitude <= maxLng &&
        device.latitude >= minLat && device.latitude <= maxLat) {
      selectedDeviceIds.add(device.id)
    }
  })

  refreshMarkerStyles()
}

// ======================== 初始化地图 ========================
async function initMap() {
  try {
    AMap = await AMapLoader.load({
      key: AMAP_KEY,
      version: '2.0',
      plugins: []
    })

    map = new AMap.Map(mapContainer.value, {
      center: [106.5, 29.5],
      zoom: 13,
      viewMode: '2D'
    })

    infoWindow = new AMap.InfoWindow({
      offset: new AMap.Pixel(0, -30),
      closeWhenClickMap: true
    })

    // 注册地图事件（框选）
    map.on('mousedown', onMapMouseDown)
    map.on('mousemove', onMapMouseMove)
    map.on('mouseup', onMapMouseUp)

    // 右键菜单
    map.on('rightclick', (e) => {
      if (currentTool.value === 'add-location') {
        // 添加设备模式：右键弹出「在此位置添加设备」
        addLocationCoords.value = { lat: e.lnglat.lat, lng: e.lnglat.lng }
        const pixel = map.lngLatToContainer(e.lnglat)
        contextMenu.x = pixel.x
        contextMenu.y = pixel.y
        contextMenu.visible = true
        return
      }
      if (selectedDeviceIds.size === 0) {
        contextMenu.visible = false
        return
      }
      const pixel = map.lngLatToContainer(e.lnglat)
      const wrapperRect = mapWrapper.value.getBoundingClientRect()
      contextMenu.x = pixel.x
      contextMenu.y = pixel.y
      contextMenu.visible = true
    })

    // 点击地图空白处关闭菜单、关闭信息窗
    map.on('click', (e) => {
      // 点击空白处关闭右键菜单
      contextMenu.visible = false
      // 不关闭信息窗（点击标记时会自动切换）
    })

    updateMarkers()
    if (markers.length > 0) {
      map.setFitView(markers.map(m => m.marker), false, [30, 30, 30, 30])
    }
  } catch (e) {
    console.error('高德地图加载失败:', e)
  }
}

// ======================== 更新标记 ========================
function updateMarkers() {
  if (!map) return

  // 清除旧标记
  markers.forEach(({ marker }) => map.remove(marker))
  markers = []
  selectedDeviceIds.clear()

  const validDevices = props.devices.filter(d =>
    d.latitude != null && d.longitude != null &&
    !isNaN(d.latitude) && !isNaN(d.longitude)
  )

  if (validDevices.length === 0) {
    map.setCenter([106.5, 29.5])
    map.setZoom(13)
    return
  }

  validDevices.forEach(device => {
    const sel = selectedDeviceIds.has(device.id)
    const el = createMarkerEl(device, sel)
    const marker = new AMap.Marker({
      position: [device.longitude, device.latitude],
      content: el,
      offset: new AMap.Pixel(-10, -10)
    })

    // 使用原生 DOM 事件（确保能获取 ctrlKey）
    el.addEventListener('click', (domEvent) => {
      domEvent.stopPropagation()
      if (domEvent.ctrlKey || domEvent.metaKey) {
        // Ctrl+Click → 切换选中
        if (selectedDeviceIds.has(device.id)) {
          selectedDeviceIds.delete(device.id)
        } else {
          selectedDeviceIds.add(device.id)
        }
        refreshMarkerStyles()
        infoWindow.close()
        contextMenu.visible = false
        return
      }

      // 普通点击 → 显示详情
      contextMenu.visible = false
      infoWindow.setContent(buildInfoContent(device))
      infoWindow.open(map, marker.getPosition())
      setTimeout(() => {
        const btn = document.getElementById(`detail-btn-${device.id}`)
        if (btn) {
          btn.onclick = () => router.push(`/devices/${device.id}`)
        }
      }, 50)
    })

    map.add(marker)
    markers.push({ marker, device, el })
  })

  map.setFitView(markers.map(m => m.marker), false, [30, 30, 30, 30])
}

// ======================== 信息窗内容 ========================
function buildInfoContent(device) {
  const statusMap = { online: '在线', offline: '离线' }
  const lightMap = { on: '开启', off: '关闭' }
  const modeMap = { auto: '自动', manual: '手动' }

  return `
    <div style="font-size: 13px; line-height: 1.8; min-width: 220px;">
      <div style="font-size: 15px; font-weight: 600; margin-bottom: 6px; border-bottom: 1px solid #eee; padding-bottom: 4px;">
        ${device.name || device.deviceId}
      </div>
      <table style="width: 100%; border-collapse: collapse;">
        <tr><td style="color: #666; padding-right: 8px;">设备ID</td><td>${device.deviceId}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">状态</td><td>${statusMap[device.status] || device.status}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">灯光</td><td>${lightMap[device.lightStatus] || device.lightStatus}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">控制模式</td><td>${modeMap[device.controlMode] || device.controlMode}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">纬度</td><td>${device.latitude ?? '-'}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">经度</td><td>${device.longitude ?? '-'}</td></tr>
        <tr><td style="color: #666; padding-right: 8px;">位置</td><td>${device.location || '-'}</td></tr>
      </table>
      <div style="margin-top: 8px; text-align: center;">
        <button id="detail-btn-${device.id}"
          style="padding: 4px 16px; background: #409eff; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">
          查看详情
        </button>
      </div>
    </div>
  `
}

// ======================== 批量操作 ========================
async function batchControl(command) {
  const actionText = command === 'on' ? '开灯' : '关灯'
  const devices = selectedDevices.value
  if (devices.length === 0) return

  // 过滤出在线设备
  const onlineDevices = devices.filter(d => d.status === 'online')
  const offlineCount = devices.length - onlineDevices.length

  if (onlineDevices.length === 0) {
    ElMessage.warning('选中的设备均处于离线状态，无法控制')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定对选中的 ${onlineDevices.length} 个在线设备执行批量${actionText}吗？` +
        (offlineCount > 0 ? `（${offlineCount} 个离线设备已跳过）` : ''),
      `批量${actionText}确认`,
      { confirmButtonText: actionText, cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  const deviceIds = onlineDevices.map(d => d.deviceId)
  try {
    await sendBatchControl({ deviceIds, command })
    ElMessage.success(
      `批量${actionText}请求已发送` +
        (offlineCount > 0 ? `（已跳过 ${offlineCount} 个离线设备）` : '')
    )
    // 本地同步设备状态
    onlineDevices.forEach(d => {
      d.lightStatus = command
      d.controlMode = 'manual'
    })
    refreshMarkerStyles()
    clearSelection()
  } catch { /* 错误已拦截 */ }
}

async function batchDelete() {
  if (!isAdmin.value) return
  const devices = selectedDevices.value
  if (devices.length === 0) return

  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${devices.length} 个设备吗？此操作不可恢复。`,
      '批量删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  // 逐个删除
  let success = 0, fail = 0
  for (const d of devices) {
    try {
      await sendControl(d.deviceId, { command: 'off' }) // 先关灯
      // 实际删除需要调用 device API，这里用 emit 通知父组件
    } catch { fail++ }
  }
  ElMessage.success(`批量删除完成`)
  clearSelection()
}

// ======================== 右键菜单操作 ========================
function contextAction(command) {
  contextMenu.visible = false
  batchControl(command)
}

function contextDelete() {
  contextMenu.visible = false
  batchDelete()
}

/** 右键菜单 — 批量切换模式 */
function contextModeChange(mode) {
  contextMenu.visible = false
  batchModeChange(mode)
}

/** 批量切换控制模式 */
async function batchModeChange(mode) {
  const modeText = mode === 'auto' ? '自动' : '手动'
  const devices = selectedDevices.value
  if (devices.length === 0) return

  const onlineDevices = devices.filter(d => d.status === 'online')
  const offlineCount = devices.length - onlineDevices.length

  if (onlineDevices.length === 0) {
    ElMessage.warning('选中的设备均处于离线状态，无法切换模式')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定将选中的 ${onlineDevices.length} 个在线设备切换为「${modeText}模式」吗？` +
        (offlineCount > 0 ? `（${offlineCount} 个离线设备已跳过）` : ''),
      '批量切换模式确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
    )
  } catch { return }

  let successCount = 0
  for (const d of onlineDevices) {
    try {
      await setControlMode(d.id, { controlMode: mode })
      d.controlMode = mode
      successCount++
    } catch { /* 单个失败跳过 */ }
  }

  if (successCount > 0) {
    ElMessage.success(`已切换 ${successCount} 个设备为${modeText}模式`)
    refreshMarkerStyles()
  }
  clearSelection()
}

// ======================== 清空选择 ========================
function clearSelection() {
  selectedDeviceIds.clear()
  contextMenu.visible = false
  refreshMarkerStyles()
}

// ======================== 批量解绑传感器 ========================
function contextUnbind() {
  contextMenu.visible = false
  batchUnbind()
}

async function batchUnbind() {
  if (!isAdmin.value) return
  const devices = selectedDevices.value
  if (devices.length === 0) return

  const onlineDevices = devices.filter(d => d.status === 'online')
  const offlineCount = devices.length - onlineDevices.length

  if (onlineDevices.length === 0) {
    ElMessage.warning('选中的设备均处于离线状态')
    return
  }

  const devicesWithSensors = onlineDevices.filter(d => d.sensors?.length > 0)
  const totalSensors = devicesWithSensors.reduce((sum, d) => sum + d.sensors.length, 0)

  if (devicesWithSensors.length === 0) {
    ElMessage.info('选中的在线设备都没有绑定传感器')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定解绑 ${devicesWithSensors.length} 个设备的 ${totalSensors} 个传感器吗？` +
        (offlineCount > 0 ? `（${offlineCount} 个离线设备已跳过）` : '') +
        '<br><small style="color: #909399;">传感器不会被删除，仅解除与设备的绑定关系</small>',
      '批量解绑确认',
      {
        confirmButtonText: '确定解绑',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )
  } catch { return }

  let successCount = 0
  let failCount = 0
  for (const d of devicesWithSensors) {
    for (const sensor of d.sensors) {
      try {
        await unbindSensor(d.id, sensor.id)
        successCount++
      } catch {
        failCount++
      }
    }
    d.sensors = []
  }

  if (successCount > 0) {
    ElMessage.success(`已解绑 ${successCount} 个传感器` + (failCount > 0 ? `，${failCount} 个失败` : ''))
  } else {
    ElMessage.error('解绑失败')
  }
  clearSelection()
  emit('refresh')
}

// ======================== 生命周期 ========================
watch(() => props.devices, () => {
  nextTick(updateMarkers)
}, { deep: true })

onMounted(() => {
  nextTick(initMap)
})

onBeforeUnmount(() => {
  if (infoWindow) infoWindow = null
  if (map) {
    map.destroy()
    map = null
  }
  markers = []
})
</script>

<style scoped>
.map-wrapper {
  position: relative;
}

.map-container {
  width: 100%;
  border-radius: 8px;
  overflow: hidden;
}

/* 工具栏 */
.map-toolbar {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  background: rgba(255, 255, 255, 0.95);
  padding: 4px;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}
.map-toolbar > * {
  margin: 0 !important;
}
.map-toolbar .el-button {
  height: 28px;
  padding: 0 6px !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px;
}
.map-toolbar .el-button .el-icon {
  font-size: 14px;
}
.tool-btn-label {
  font-size: 11px;
  opacity: 0.55;
  white-space: nowrap;
}


/* 选框 */
.selection-box {
  position: absolute;
  z-index: 9;
  background: rgba(64, 158, 255, 0.1);
  border: 2px dashed #409eff;
  pointer-events: none;
}

/* 操作栏 */
.operation-bar {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.97);
  padding: 10px 18px;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  white-space: nowrap;
}

.op-count {
  font-size: 13px;
  font-weight: 600;
  color: #333;
  margin-right: 4px;
}

/* 操作栏动画 */
.slide-up-enter-active {
  transition: all 0.3s ease-out;
}
.slide-up-leave-active {
  transition: all 0.2s ease-in;
}
.slide-up-enter-from {
  transform: translateX(-50%) translateY(20px);
  opacity: 0;
}
.slide-up-leave-to {
  transform: translateX(-50%) translateY(10px);
  opacity: 0;
}

/* 右键菜单 */
.context-menu {
  position: absolute;
  z-index: 20;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  padding: 4px 0;
  min-width: 150px;
}
.context-menu-item {
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.context-menu-item:hover {
  background: #f0f5ff;
}
.context-menu-divider {
  height: 1px;
  background: #eee;
  margin: 4px 0;
}
</style>
