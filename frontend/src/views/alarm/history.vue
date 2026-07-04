<template>
  <div class="alarm-history">
    <div class="page-header">
      <h2>告警历史</h2>
      <el-button type="primary" size="small" @click="exportCSV">
        <el-icon><Download /></el-icon> 导出CSV
      </el-button>
    </div>

    <!-- Filter Bar -->
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" :model="filters" size="small">
        <el-form-item label="时间">
          <el-radio-group v-model="filters.timePreset" @change="handleFilterChange">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="today">今天</el-radio-button>
            <el-radio-button value="3d">3天</el-radio-button>
            <el-radio-button value="7d">7天</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="设备">
          <el-select
            v-model="filters.deviceId"
            placeholder="全部设备"
            clearable
            style="width: 150px"
            @change="handleFilterChange"
          >
            <el-option
              v-for="d in deviceStore.devices"
              :key="d.id"
              :label="d.name"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="filters.type"
            placeholder="全部类型"
            clearable
            style="width: 140px"
            @change="handleFilterChange"
          >
            <el-option label="设备离线" value="offline" />
            <el-option label="传感器异常" value="sensor_abnormal" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="filters.status"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="handleFilterChange"
          >
            <el-option label="待处理" value="pending" />
            <el-option label="已处理" value="resolved" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="filters.keyword"
            placeholder="搜索告警内容"
            clearable
            prefix-icon="Search"
            style="width: 200px"
            @input="handleKeywordInput"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Stats Cards -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">今日告警</div>
          <div class="stat-value" style="color:#F56C6C">{{ stats.todayCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">未处理</div>
          <div class="stat-value" style="color:#E6A23C">{{ stats.pendingCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">已处理</div>
          <div class="stat-value" style="color:#67C23A">{{ stats.resolvedCount }}</div>
        </el-card>
      </el-col>
      <el-col :xs="8" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">总记录</div>
          <div class="stat-value" style="color:#409EFF">{{ totalElements }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Alarm Table -->
    <el-card shadow="hover">
      <el-table
        :data="alarms"
        v-loading="loading"
        stripe
        style="width: 100%"
        @row-click="handleRowClick"
        :row-class-name="tableRowClassName"
      >
        <el-table-column type="selection" width="40" />
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
        <el-table-column label="告警类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.alarmType === 'offline' ? 'danger' : 'warning'" size="small">
              {{ row.alarmType === 'offline' ? '设备离线' : '传感器异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备编号" width="120" />
        <el-table-column prop="content" label="告警内容" min-width="240" show-overflow-tooltip />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.createdAt)" placement="top">
              <span>{{ relativeTime(row.createdAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'pending' ? 'danger' : 'success'" size="small">
              {{ row.status === 'pending' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="showResolvedInfo" label="处理人" width="100">
          <template #default="{ row }">
            <span style="font-size: 12px; color: #909399;">
              {{ row.resolvedBy || '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="showResolvedInfo" label="处理时间" width="170">
          <template #default="{ row }">
            <span style="font-size: 12px; color: #909399;">
              {{ row.resolvedAt ? formatTime(row.resolvedAt) : '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              size="small"
              type="primary"
              @click.stop="handleResolve(row)"
            >处理</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="pagination-wrapper" v-if="totalPages > 0">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="totalElements"
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50]"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>

      <!-- Batch actions -->
      <div v-if="selectedRows.length > 0" class="batch-bar">
        <span>已选择 {{ selectedRows.length }} 条</span>
        <el-button size="small" type="primary" @click="batchResolve">批量处理</el-button>
      </div>
    </el-card>

    <!-- Expanded detail drawer -->
    <el-drawer
      v-model="detailDrawer"
      title="告警详情"
      size="400px"
    >
      <template v-if="detailAlarm">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="告警ID">{{ detailAlarm.id }}</el-descriptions-item>
          <el-descriptions-item label="设备编号">{{ detailAlarm.deviceId }}</el-descriptions-item>
          <el-descriptions-item label="告警类型">
            <el-tag :type="detailAlarm.alarmType === 'offline' ? 'danger' : 'warning'" size="small">
              {{ detailAlarm.alarmType === 'offline' ? '设备离线' : '传感器异常' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="严重级别">
            <el-tag
              :type="detailAlarm.severity === 'critical' ? 'danger' : detailAlarm.severity === 'warning' ? 'warning' : 'info'"
              size="small"
            >
              {{ detailAlarm.severity === 'critical' ? '严重' : detailAlarm.severity === 'warning' ? '警告' : '信息' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="告警内容">{{ detailAlarm.content }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ formatTime(detailAlarm.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detailAlarm.status === 'pending' ? 'danger' : 'success'" size="small">
              {{ detailAlarm.status === 'pending' ? '待处理' : '已处理' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detailAlarm.status === 'resolved'" label="处理人">
            {{ detailAlarm.resolvedBy || '--' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailAlarm.status === 'resolved'" label="处理时间">
            {{ detailAlarm.resolvedAt ? formatTime(detailAlarm.resolvedAt) : '--' }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

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
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { getAlarms, resolveAlarm } from '@/api/alarm'
import { useDeviceStore } from '@/stores/device'
import { useWebSocketStore } from '@/stores/websocket'

const deviceStore = useDeviceStore()
const wsStore = useWebSocketStore()

// Filters
const filters = reactive({
  timePreset: '',
  deviceId: '',
  type: '',
  status: '',
  keyword: ''
})

const alarms = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)

const totalPages = computed(() => Math.ceil(totalElements.value / pageSize.value))

// Stats
const stats = reactive({
  todayCount: 0,
  pendingCount: 0,
  resolvedCount: 0
})

// Selection
const selectedRows = ref([])
const showResolveDialog = ref(false)
const resolveLoading = ref(false)
const resolveForm = ref({ id: null, resolvedBy: '' })

// Detail drawer
const detailDrawer = ref(false)
const detailAlarm = ref(null)

const showResolvedInfo = computed(() =>
  alarms.value.some(a => a.status === 'resolved')
)

let keywordTimer = null

onMounted(() => {
  loadAlarms()
})

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
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
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}天前`
  return formatTime(t)
}

function buildTimeRange() {
  if (!filters.timePreset) return {}
  const now = new Date()
  const start = new Date(now)
  if (filters.timePreset === 'today') {
    start.setHours(0, 0, 0, 0)
  } else {
    const days = parseInt(filters.timePreset)
    start.setDate(start.getDate() - days)
  }
  return { start: start.toISOString(), end: now.toISOString() }
}

async function loadAlarms() {
  loading.value = true
  const timeRange = buildTimeRange()
  const params = {
    page: currentPage.value - 1,
    size: pageSize.value,
    ...timeRange
  }
  if (filters.deviceId) params.deviceId = filters.deviceId
  if (filters.type) params.type = filters.type
  if (filters.status) params.status = filters.status
  if (filters.keyword) params.keyword = filters.keyword

  try {
    const res = await getAlarms(params)
    alarms.value = res.content || []
    totalElements.value = res.totalElements || 0

    // Compute stats
    const all = res.content || []
    stats.todayCount = all.filter(a => {
      const d = new Date(a.createdAt)
      const today = new Date()
      return d.toDateString() === today.toDateString()
    }).length
    stats.pendingCount = all.filter(a => a.status === 'pending').length
    stats.resolvedCount = all.filter(a => a.status === 'resolved').length
  } catch (e) {
    alarms.value = []
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  currentPage.value = 1
  loadAlarms()
}

function handleKeywordInput() {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    handleFilterChange()
  }, 300)
}

function handlePageChange(page) {
  currentPage.value = page
  loadAlarms()
}

function handleSizeChange(size) {
  pageSize.value = size
  currentPage.value = 1
  loadAlarms()
}

function handleRowClick(row) {
  detailAlarm.value = row
  detailDrawer.value = true
}

function tableRowClassName({ row }) {
  if (row.status === 'pending') return 'row-pending'
  return ''
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
    loadAlarms()
  } catch (e) {
    // handled by interceptor
  } finally {
    resolveLoading.value = false
  }
}

function batchResolve() {
  ElMessage.info('批量处理功能待实现')
}

function exportCSV() {
  if (alarms.value.length === 0) {
    ElMessage.warning('没有数据可导出')
    return
  }

  const headers = ['告警ID', '设备编号', '告警类型', '严重级别', '告警内容', '时间', '状态', '处理人', '处理时间']
  const rows = alarms.value.map(a => [
    a.id,
    a.deviceId,
    a.alarmType === 'offline' ? '设备离线' : '传感器异常',
    a.severity === 'critical' ? '严重' : a.severity === 'warning' ? '警告' : '信息',
    a.content,
    formatTime(a.createdAt),
    a.status === 'pending' ? '待处理' : '已处理',
    a.resolvedBy || '',
    a.resolvedAt ? formatTime(a.resolvedAt) : ''
  ])

  const csv = [headers.join(','), ...rows.map(r => r.join(','))].join('\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `告警历史_${new Date().toLocaleDateString('zh-CN')}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
  ElMessage.success('导出成功')
}
</script>

<style scoped>
.alarm-history {
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.filter-card {
  margin-bottom: 16px;
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

.pagination-wrapper {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

.batch-bar {
  margin-top: 12px;
  padding: 8px 12px;
  background: #ecf5ff;
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}

:deep(.row-pending) {
  background-color: #fef0f0;
}
</style>
