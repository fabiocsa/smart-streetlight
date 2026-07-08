<template>
  <div class="alarm-list">
    <div class="page-header">
      <h2>告警管理</h2>
      <!-- View Toggle -->
      <div class="view-toggle">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="table">
            <el-icon><List /></el-icon>
            表格视图
          </el-radio-button>
          <el-radio-button value="floorplan">
            <el-icon><MapLocation /></el-icon>
            平面图视图
          </el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- Alarm Banner (only when pending alarms exist) -->
    <div v-if="pendingAlarmCount > 0" class="alarm-banner" @click="scrollToTable">
      <el-alert
        :title="`${pendingAlarmCount} 条未处理告警 - ${latestPendingAlarm}`"
        type="warning"
        :closable="false"
        show-icon
      />
    </div>

    <!-- WS Disconnect Banner -->
    <div v-if="!wsStore.connected" class="ws-banner">
      <el-alert title="实时告警已暂停" type="info" :closable="false" show-icon />
    </div>

    <!-- Stats Cards -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">今日告警</div>
          <div class="stat-value" style="color:#F56C6C">{{ todayAlarmCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">未处理</div>
          <div class="stat-value" style="color:#E6A23C">{{ alarmStore.pendingCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">已处理</div>
          <div class="stat-value" style="color:#67C23A">{{ resolvedCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">平均处理时长</div>
          <div class="stat-value" style="color:#409EFF;font-size:16px">{{ avgResolveTime }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== TABLE VIEW ==================== -->
    <template v-if="viewMode === 'table'">
    <el-card shadow="hover">
      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-form :inline="true" :model="filters">
          <el-form-item label="状态">
            <el-select v-model="filters.status" placeholder="全部" clearable style="width: 130px" @change="handleSearch">
              <el-option label="待处理" value="pending" />
              <el-option label="已处理" value="resolved" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="filters.type" placeholder="全部" clearable style="width: 150px" @change="handleSearch">
              <el-option label="设备离线" value="offline" />
              <el-option label="传感器异常" value="sensor_abnormal" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-select v-model="sortOrder" style="width: 130px" @change="handleSearch">
              <el-option label="最新优先" value="desc" />
              <el-option label="最早优先" value="asc" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- Batch Actions -->
      <div v-if="selectedRows.length > 0" class="batch-bar">
        <span>已选择 {{ selectedRows.length }} 条告警</span>
        <el-button size="small" type="primary" @click="handleBatchResolve">批量处理</el-button>
      </div>

      <!-- Empty State -->
      <template v-if="!alarmStore.loading && alarmStore.alarms.length === 0 && !hasError">
        <div class="empty-state">
          <span style="font-size: 48px">✅</span>
          <span style="font-size: 14px; color: #909399; margin-top: 8px;">当前无告警，一切正常</span>
        </div>
      </template>
      <template v-else>
      <el-table
        ref="tableRef"
        :data="sortedAlarms"
        v-loading="alarmStore.loading"
        stripe
        style="width: 100%"
        @selection-change="handleSelectionChange"
        :row-class-name="tableRowClassName"
      >
        <el-table-column type="selection" width="40" />
        <el-table-column prop="deviceId" label="设备编号" width="120">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goToDevice(row.deviceId)">
              {{ row.deviceId }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="告警类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.alarmType === 'offline' ? 'danger' : 'warning'" size="small">
              {{ row.alarmType === 'offline' ? '设备离线' : '传感器异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="严重级别" width="100">
          <template #default="{ row }">
            <el-tag
              :type="row.severity === 'critical' ? 'danger' : row.severity === 'warning' ? 'warning' : 'info'"
              size="small"
            >
              {{ row.severity === 'critical' ? '严重' : row.severity === 'warning' ? '警告' : '信息' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="告警内容" min-width="280" show-overflow-tooltip />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'pending' ? 'danger' : 'success'" size="small">
              {{ row.status === 'pending' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              size="small"
              type="primary"
              @click="handleResolve(row)"
            >处理</el-button>
            <span v-else style="color: #909399; font-size: 12px;">
              处理人: {{ row.resolvedBy || '--' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      </template>

      <!-- Pagination -->
      <div class="pagination-wrapper" v-if="totalPages > 0">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="totalElements"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
    </template>

    <!-- ==================== FLOOR PLAN VIEW ==================== -->
    <template v-if="viewMode === 'floorplan'">
      <el-card shadow="hover" class="floorplan-card">
        <template #header>
          <div class="floorplan-header">
            <span>平面图 - 设备分布总览</span>
            <div class="floorplan-legend">
              <span class="legend-item"><span class="dot dot-normal"></span> 正常</span>
              <span class="legend-item"><span class="dot dot-warning"></span> 告警中</span>
              <span class="legend-item"><span class="dot dot-critical"></span> 严重告警</span>
              <span class="legend-item"><span class="dot dot-offline"></span> 离线</span>
            </div>
          </div>
        </template>

        <div class="floorplan-container" ref="floorplanRef">
          <svg class="floorplan-svg" :width="svgWidth" :height="svgHeight">
            <!-- Floor background -->
            <rect x="20" y="20" :width="svgWidth - 40" :height="svgHeight - 40" rx="12" fill="#f8f9fa" stroke="#e8e8e8" stroke-width="1" />

            <!-- Grid lines -->
            <line v-for="(gx, i) in gridX" :key="'gx'+i" :x1="gx" y1="40" :x2="gx" :y2="svgHeight - 40" stroke="#eee" stroke-width="0.5" stroke-dasharray="4,4" />
            <line v-for="(gy, i) in gridY" :key="'gy'+i" x1="40" :y1="gy" :x2="svgWidth - 40" :y2="gy" stroke="#eee" stroke-width="0.5" stroke-dasharray="4,4" />

            <!-- Room labels -->
            <text v-for="(room, i) in rooms" :key="'room'+i" :x="room.x" :y="room.y" text-anchor="middle" fill="#c0c4cc" font-size="12" font-style="italic">{{ room.label }}</text>

            <!-- Connection lines between nearby devices -->
            <line v-for="(conn, i) in connectionLines" :key="'conn'+i"
              :x1="conn.x1" :y1="conn.y1" :x2="conn.x2" :y2="conn.y2"
              stroke="#d9d9d9" stroke-width="1.5" stroke-dasharray="6,3" />

            <!-- Device nodes -->
            <g v-for="(dvc, i) in floorDevices" :key="dvc.deviceId"
              :transform="`translate(${dvc.x}, ${dvc.y})`"
              class="floor-device"
              :class="{ 'has-alarm': dvc.hasPendingAlarm, 'is-offline': dvc.status === 'offline' }"
              @click="handleDeviceClick(dvc)"
              @mouseenter="hoveredDevice = dvc"
              @mouseleave="hoveredDevice = null"
              style="cursor: pointer;"
            >
              <!-- Pulse ring for critical alarms -->
              <circle v-if="dvc.alarmSeverity === 'critical'" cx="0" cy="0" r="32" fill="none" :stroke="dvc.status === 'offline' ? '#909399' : '#F56C6C'" stroke-width="2" class="pulse-ring" />

              <!-- Device body -->
              <circle cx="0" cy="0" :r="deviceRadius" :fill="getDeviceColor(dvc)" :stroke="hoveredDevice?.deviceId === dvc.deviceId ? '#409EFF' : getDeviceStroke(dvc)" stroke-width="2.5" />

              <!-- Device icon -->
              <text x="0" y="-2" text-anchor="middle" fill="#fff" font-size="16" dominant-baseline="central">{{ dvc.lightStatus === 'on' ? '💡' : '🔦' }}</text>

              <!-- Device name -->
              <text x="0" :y="deviceRadius + 16" text-anchor="middle" fill="#303133" font-size="11" font-weight="500">{{ dvc.name }}</text>

              <!-- Status badge -->
              <circle :cx="deviceRadius - 4" :cy="-deviceRadius + 4" r="6" :fill="dvc.status === 'online' ? '#67C23A' : '#C0C4CC'" stroke="#fff" stroke-width="1.5" />

              <!-- Alarm count badge -->
              <g v-if="dvc.pendingAlarmCount > 0">
                <circle :cx="deviceRadius - 4" :cy="deviceRadius - 4" r="10" fill="#F56C6C" stroke="#fff" stroke-width="1.5" />
                <text x="0" y="0" transform="translate(0, 0)" :dx="deviceRadius - 4" :dy="deviceRadius - 4" text-anchor="middle" fill="#fff" font-size="9" font-weight="bold" dominant-baseline="central">{{ dvc.pendingAlarmCount > 9 ? '9+' : dvc.pendingAlarmCount }}</text>
              </g>
            </g>
          </svg>

          <!-- Device Tooltip -->
          <div v-if="hoveredDevice" class="device-tooltip" :style="tooltipStyle">
            <div class="tooltip-header">{{ hoveredDevice.name }}</div>
            <div class="tooltip-row"><span>编号:</span> {{ hoveredDevice.deviceId }}</div>
            <div class="tooltip-row"><span>状态:</span> {{ hoveredDevice.status === 'online' ? '在线' : '离线' }}</div>
            <div class="tooltip-row"><span>灯光:</span> {{ hoveredDevice.lightStatus === 'on' ? '已开灯' : '已关灯' }}</div>
            <div class="tooltip-row"><span>模式:</span> {{ hoveredDevice.controlMode === 'auto' ? '自动' : '手动' }}</div>
            <div class="tooltip-row" v-if="hoveredDevice.lastLightIntensity !== undefined"><span>光照:</span> {{ hoveredDevice.lastLightIntensity }} Lux</div>
            <div class="tooltip-row" v-if="hoveredDevice.pendingAlarmCount > 0" style="color:#F56C6C">
              <span>告警:</span> {{ hoveredDevice.pendingAlarmCount }} 条待处理
            </div>
            <div class="tooltip-hint">点击查看设备告警</div>
          </div>
        </div>
      </el-card>

      <!-- Device Alarm Detail Panel -->
      <el-card v-if="selectedFloorDevice" shadow="hover" class="device-detail-card">
        <template #header>
          <div class="detail-header">
            <span>
              <el-tag :type="selectedFloorDevice.status === 'online' ? 'success' : 'info'" size="small" round>
                {{ selectedFloorDevice.status === 'online' ? '🟢 在线' : '⚪ 离线' }}
              </el-tag>
              {{ selectedFloorDevice.name }} ({{ selectedFloorDevice.deviceId }}) - 告警列表
            </span>
            <div class="detail-actions">
              <el-button size="small" @click="goToDevice(selectedFloorDevice.deviceId)">设备详情</el-button>
              <el-button size="small" type="primary" @click="handleBatchResolveDeviceAlarms(selectedFloorDevice)">批量处理告警</el-button>
              <el-button size="small" @click="selectedFloorDevice = null">关闭</el-button>
            </div>
          </div>
        </template>
        <el-table :data="deviceAlarms" stripe size="small" max-height="250" v-loading="deviceAlarmLoading">
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag :type="row.alarmType === 'offline' ? 'danger' : 'warning'" size="small">
                {{ row.alarmType === 'offline' ? '设备离线' : '传感器异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="严重级别" width="90">
            <template #default="{ row }">
              <el-tag :type="row.severity === 'critical' ? 'danger' : row.severity === 'warning' ? 'warning' : 'info'" size="small">
                {{ row.severity === 'critical' ? '严重' : row.severity === 'warning' ? '警告' : '信息' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="告警内容" min-width="200" show-overflow-tooltip />
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 'pending' ? 'danger' : 'success'" size="small">
                {{ row.status === 'pending' ? '待处理' : '已处理' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="row.status === 'pending'" size="small" type="primary" @click="handleResolve(row)">处理</el-button>
              <span v-else style="color:#909399;font-size:12px">{{ row.resolvedBy || '--' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- Resolve Dialog -->
    <el-dialog v-model="showResolveDialog" title="处理告警" width="380px">
      <el-form :model="resolveForm" label-width="80px">
        <el-form-item label="处理人">
          <el-input v-model="resolveForm.resolvedBy" placeholder="输入姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showResolveDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmResolve" :loading="resolveLoading">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { List, MapLocation } from '@element-plus/icons-vue'
import { getAlarms, resolveAlarm } from '@/api/alarm'
import { useAlarmStore } from '@/stores/alarm'
import { useWebSocketStore } from '@/stores/websocket'
import { useDeviceStore } from '@/stores/device'

const router = useRouter()
const alarmStore = useAlarmStore()
const wsStore = useWebSocketStore()
const deviceStore = useDeviceStore()

// View mode
const viewMode = ref('table')

// Table filters
const filters = reactive({
  status: '',
  type: ''
})
const sortOrder = ref('desc')
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)
const hasError = ref(false)

const totalPages = computed(() => Math.ceil(totalElements.value / pageSize.value))

const showResolveDialog = ref(false)
const resolveLoading = ref(false)
const resolveForm = ref({ id: null, resolvedBy: '' })

// Batch selection
const selectedRows = ref([])
const tableRef = ref(null)

// Computed stats
const todayAlarmCount = computed(() =>
  alarmStore.alarms.filter(a => {
    const d = new Date(a.createdAt)
    const t = new Date()
    return d.toDateString() === t.toDateString()
  }).length
)

const resolvedCount = computed(() =>
  alarmStore.alarms.filter(a => a.status === 'resolved').length
)

// Banner
const pendingAlarmCount = computed(() => alarmStore.pendingCount)
const latestPendingAlarm = computed(() => {
  const pending = alarmStore.alarms.filter(a => a.status === 'pending')
  return pending.length > 0 ? (pending[0]?.content || '') : ''
})

// Average resolve time
const avgResolveTime = computed(() => {
  const resolved = alarmStore.alarms.filter(a => a.status === 'resolved' && a.createdAt && a.resolvedAt)
  if (resolved.length === 0) return '--'
  const totalMs = resolved.reduce((sum, a) => {
    return sum + (new Date(a.resolvedAt).getTime() - new Date(a.createdAt).getTime())
  }, 0)
  const avgMin = Math.round(totalMs / resolved.length / 60000)
  if (avgMin < 60) return `${avgMin}分钟`
  return `${Math.floor(avgMin / 60)}小时${avgMin % 60}分钟`
})

// Sorted alarms
const sortedAlarms = computed(() => {
  const list = [...alarmStore.alarms]
  list.sort((a, b) => {
    const ta = new Date(a.createdAt).getTime()
    const tb = new Date(b.createdAt).getTime()
    return sortOrder.value === 'desc' ? tb - ta : ta - tb
  })
  return list
})

// Highlight new alarms with row class
function tableRowClassName({ row }) {
  if (row.status === 'pending' && row._isNew) {
    return 'row-new-alarm'
  }
  return ''
}

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

function goToDevice(deviceId) {
  const device = deviceStore?.devices?.find(d => d.deviceId === deviceId)
  if (device) {
    router.push(`/devices/${device.id}`)
  }
}

function scrollToTable() {
  const el = document.querySelector('.el-table')
  if (el) el.scrollIntoView({ behavior: 'smooth' })
}

async function loadAlarms() {
  const params = {
    page: currentPage.value - 1,
    size: pageSize.value,
    sort: `createdAt,${sortOrder.value}`
  }
  if (filters.status) params.status = filters.status
  if (filters.type) params.type = filters.type

  try {
    const res = await alarmStore.fetchAlarms(params)
    if (res) {
      totalElements.value = res.totalElements || 0
    }
    hasError.value = false
  } catch (e) {
    hasError.value = true
  }
}

function handleSearch() {
  currentPage.value = 1
  loadAlarms()
}

function handleReset() {
  filters.status = ''
  filters.type = ''
  sortOrder.value = 'desc'
  currentPage.value = 1
  loadAlarms()
}

function handlePageChange(page) {
  currentPage.value = page
  loadAlarms()
}

function handleSelectionChange(rows) {
  selectedRows.value = rows
}

function handleResolve(row) {
  resolveForm.value = { id: row.id, resolvedBy: '' }
  showResolveDialog.value = true
}

async function confirmResolve() {
  if (!resolveForm.value.resolvedBy) {
    ElMessage.warning('请输入处理人姓名')
    return
  }

  resolveLoading.value = true
  try {
    await resolveAlarm(resolveForm.value.id, resolveForm.value.resolvedBy)
    ElMessage.success('告警已处理')
    showResolveDialog.value = false
    alarmStore.markResolved(resolveForm.value.id)
  } catch (e) {
    // handled by interceptor
  } finally {
    resolveLoading.value = false
  }
}

async function handleBatchResolve() {
  const pendingRows = selectedRows.value.filter(r => r.status === 'pending')
  if (pendingRows.length === 0) {
    ElMessage.info('选中的告警已全部处理')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认批量处理 ${pendingRows.length} 条告警？`,
      '批量处理',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' }
    )
    for (const row of pendingRows) {
      try {
        await resolveAlarm(row.id, '批量处理')
        alarmStore.markResolved(row.id)
      } catch (e) {
        console.error(`Failed to resolve alarm ${row.id}:`, e)
      }
    }
    ElMessage.success(`已处理 ${pendingRows.length} 条告警`)
    selectedRows.value = []
    loadAlarms()
  } catch {
    // cancelled
  }
}

// ==================== Floor Plan ====================
const floorplanRef = ref(null)
const svgWidth = ref(900)
const svgHeight = ref(600)
const deviceRadius = 28
const hoveredDevice = ref(null)
const selectedFloorDevice = ref(null)
const deviceAlarms = ref([])
const deviceAlarmLoading = ref(false)

// Room areas on the floor plan
const rooms = computed(() => [
  { x: 150, y: 50, label: 'A区 - 主干道' },
  { x: 450, y: 50, label: 'B区 - 商业区' },
  { x: 750, y: 50, label: 'C区 - 住宅区' },
  { x: 300, y: svgHeight.value - 50, label: 'D区 - 公园带' },
  { x: 600, y: svgHeight.value - 50, label: 'E区 - 工业区' }
])

// Auto-layout devices on floor plan
const floorDevices = computed(() => {
  const devices = deviceStore.devices || []
  const cols = 5
  const startX = 120
  const startY = 100
  const spacingX = (svgWidth.value - 240) / (cols - 1 || 1)
  const rows = Math.ceil(devices.length / cols)
  const spacingY = Math.min(160, (svgHeight.value - 180) / Math.max(rows, 1))

  // Count pending alarms per device
  const alarmCounts = {}
  const alarmSeverities = {}
  const pendingAlarms = alarmStore.alarms.filter(a => a.status === 'pending')
  pendingAlarms.forEach(a => {
    alarmCounts[a.deviceId] = (alarmCounts[a.deviceId] || 0) + 1
    const sev = a.severity || 'warning'
    if (!alarmSeverities[a.deviceId] || sev === 'critical') {
      alarmSeverities[a.deviceId] = sev
    }
  })

  return devices.map((d, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    return {
      ...d,
      x: startX + col * spacingX,
      y: startY + row * spacingY,
      pendingAlarmCount: alarmCounts[d.deviceId] || 0,
      hasPendingAlarm: (alarmCounts[d.deviceId] || 0) > 0,
      alarmSeverity: alarmSeverities[d.deviceId] || null
    }
  })
})

// Grid lines for visual reference
const gridX = computed(() => {
  const lines = []
  for (let x = 120; x < svgWidth.value - 40; x += 150) {
    lines.push(x)
  }
  return lines
})

const gridY = computed(() => {
  const lines = []
  for (let y = 100; y < svgHeight.value - 40; y += 130) {
    lines.push(y)
  }
  return lines
})

// Connection lines between nearby devices
const connectionLines = computed(() => {
  const lines = []
  const devices = floorDevices.value
  for (let i = 0; i < devices.length; i++) {
    // Connect to next device in same row
    if ((i + 1) % 5 !== 0 && i + 1 < devices.length) {
      lines.push({
        x1: devices[i].x,
        y1: devices[i].y,
        x2: devices[i + 1].x,
        y2: devices[i + 1].y
      })
    }
    // Connect to device below
    if (i + 5 < devices.length) {
      lines.push({
        x1: devices[i].x,
        y1: devices[i].y,
        x2: devices[i + 5].x,
        y2: devices[i + 5].y
      })
    }
  }
  return lines
})

// Tooltip position
const tooltipStyle = computed(() => {
  if (!hoveredDevice.value) return { display: 'none' }
  const d = hoveredDevice.value
  // Position tooltip near the device
  let left = d.x + deviceRadius + 12
  let top = d.y - 40
  if (left + 180 > svgWidth.value) left = d.x - 200
  if (top < 0) top = 10
  return {
    left: left + 'px',
    top: top + 'px'
  }
})

function getDeviceColor(dvc) {
  if (dvc.status === 'offline') return '#C0C4CC'
  if (dvc.alarmSeverity === 'critical') return '#F56C6C'
  if (dvc.hasPendingAlarm) return '#E6A23C'
  if (dvc.lightStatus === 'on') return '#409EFF'
  return '#67C23A'
}

function getDeviceStroke(dvc) {
  if (dvc.status === 'offline') return '#909399'
  if (dvc.alarmSeverity === 'critical') return '#CF4444'
  if (dvc.hasPendingAlarm) return '#B88230'
  return getDeviceColor(dvc)
}

async function handleDeviceClick(dvc) {
  selectedFloorDevice.value = dvc
  deviceAlarmLoading.value = true
  deviceAlarms.value = []
  try {
    const res = await getAlarms({
      deviceId: dvc.deviceId,
      page: 0,
      size: 50,
      sort: 'createdAt,desc'
    })
    deviceAlarms.value = (res && Array.isArray(res.content)) ? res.content : []
  } catch (e) {
    console.error('Failed to load device alarms:', e)
  } finally {
    deviceAlarmLoading.value = false
  }
}

async function handleBatchResolveDeviceAlarms(dvc) {
  const pendingAlarmsList = deviceAlarms.value.filter(a => a.status === 'pending')
  if (pendingAlarmsList.length === 0) {
    ElMessage.info('该设备无待处理告警')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认处理 ${dvc.name} 的 ${pendingAlarmsList.length} 条待处理告警？`,
      '批量处理设备告警',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' }
    )
    for (const alarm of pendingAlarmsList) {
      try {
        await resolveAlarm(alarm.id, '平面图批量处理')
        alarmStore.markResolved(alarm.id)
      } catch (e) {
        console.error(`Failed to resolve alarm ${alarm.id}:`, e)
      }
    }
    ElMessage.success(`已处理 ${pendingAlarmsList.length} 条告警`)
    // Reload device alarms
    if (selectedFloorDevice.value) {
      handleDeviceClick(selectedFloorDevice.value)
    }
  } catch {
    // cancelled
  }
}

// Handle new alarm - browser tab flash
let originalTitle = document.title
function handleNewAlarm(data) {
  if (!data) return
  alarmStore.addNewAlarm(data)

  // Browser tab title flash
  const pendingCount = alarmStore.pendingCount
  if (pendingCount > 0) {
    document.title = `(${pendingCount}) 新告警 - ${originalTitle}`
    setTimeout(() => { document.title = originalTitle }, 3000)
  }
}

onMounted(() => {
  loadAlarms()
  originalTitle = document.title
  wsStore.on('NEW_ALARM', handleNewAlarm)
  // Load devices for floor plan
  deviceStore.fetchDevices()
})

onUnmounted(() => {
  wsStore.off('NEW_ALARM', handleNewAlarm)
  document.title = originalTitle
})
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.view-toggle {
  display: flex;
  gap: 8px;
}

.alarm-banner {
  margin-bottom: 12px;
  cursor: pointer;
}

.ws-banner {
  margin-bottom: 12px;
}

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
  margin-bottom: 12px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
}

.filter-bar {
  margin-bottom: 16px;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #ecf5ff;
  border-radius: 4px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #409EFF;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

.pagination-wrapper {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

/* Floor Plan Styles */
.floorplan-card {
  margin-bottom: 16px;
}

.floorplan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.floorplan-legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-normal { background: #67C23A; }
.dot-warning { background: #E6A23C; }
.dot-critical { background: #F56C6C; }
.dot-offline { background: #C0C4CC; }

.floorplan-container {
  position: relative;
  width: 100%;
  overflow-x: auto;
}

.floorplan-svg {
  display: block;
  margin: 0 auto;
  width: 100%;
  max-width: 1000px;
  height: auto;
  min-height: 500px;
}

.floor-device {
  transition: transform 0.2s;
}

.floor-device:hover {
  transform: translate(-2px, -2px) scale(1.05);
}

.floor-device.has-alarm {
  filter: drop-shadow(0 0 6px rgba(245, 108, 108, 0.5));
}

.floor-device.is-offline {
  opacity: 0.7;
}

.pulse-ring {
  animation: pulse-anim 2s ease-in-out infinite;
}

@keyframes pulse-anim {
  0% { opacity: 0.6; r: 28; }
  50% { opacity: 0.2; r: 36; }
  100% { opacity: 0.6; r: 28; }
}

.device-tooltip {
  position: absolute;
  background: rgba(255, 255, 255, 0.97);
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 10px 14px;
  font-size: 12px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  pointer-events: none;
  z-index: 100;
  min-width: 160px;
}

.tooltip-header {
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
  font-size: 13px;
  border-bottom: 1px solid #f0f0f0;
  padding-bottom: 4px;
}

.tooltip-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: #606266;
  margin: 3px 0;
}

.tooltip-row span {
  color: #909399;
}

.tooltip-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 11px;
  margin-top: 6px;
  font-style: italic;
}

.device-detail-card {
  margin-bottom: 16px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-actions {
  display: flex;
  gap: 8px;
}
</style>

<style>
/* Global style for new alarm highlight - not scoped */
.el-table .row-new-alarm {
  animation: newAlarmFlash 2s ease-out;
}

@keyframes newAlarmFlash {
  0% { background-color: #fef0f0; }
  100% { background-color: transparent; }
}
</style>
