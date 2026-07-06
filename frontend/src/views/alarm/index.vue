<template>
  <div class="alarm-list">
    <div class="page-header">
      <h2>告警管理</h2>
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
          <div class="stat-value" style="color:#F56C6C">{{ alarmStore.alarms.filter(a => { const d = new Date(a.createdAt); const t = new Date(); return d.toDateString() === t.toDateString() }).length }}</div>
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
          <div class="stat-value" style="color:#67C23A">{{ alarmStore.alarms.filter(a => a.status === 'resolved').length }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">平均处理时长</div>
          <div class="stat-value" style="color:#409EFF;font-size:16px">{{ avgResolveTime }}</div>
        </el-card>
      </el-col>
    </el-row>

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
import { ref, reactive, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resolveAlarm } from '@/api/alarm'
import { useAlarmStore } from '@/stores/alarm'
import { useWebSocketStore } from '@/stores/websocket'

const router = useRouter()
const alarmStore = useAlarmStore()
const wsStore = useWebSocketStore()

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

// Import deviceStore for goToDevice
import { useDeviceStore } from '@/stores/device'
const deviceStore = useDeviceStore()

onMounted(() => {
  loadAlarms()
  originalTitle = document.title
  wsStore.on('NEW_ALARM', handleNewAlarm)
})

onUnmounted(() => {
  wsStore.off('NEW_ALARM', handleNewAlarm)
  document.title = originalTitle
})
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
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
