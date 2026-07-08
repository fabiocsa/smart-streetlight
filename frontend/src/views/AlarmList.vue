<template>
  <div>
    <div class="page-header">
      <h2>告警管理</h2>
      <div class="header-actions">
        <el-button
          v-if="selectedIds.length > 0"
          type="primary"
          @click="handleBatchResolve"
        >
          <el-icon><Check /></el-icon> 批量处理 ({{ selectedIds.length }})
        </el-button>
        <el-badge :value="pendingCount" :hidden="!pendingCount" class="pending-badge">
          <el-tag type="danger" v-if="pendingCount">待处理: {{ pendingCount }}</el-tag>
        </el-badge>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="12">
        <el-col :span="4">
          <el-select v-model="filterStatus" placeholder="状态" clearable @change="loadAlarms">
            <el-option label="待处理" value="PENDING" />
            <el-option label="已处理" value="RESOLVED" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterSeverity" placeholder="级别" clearable @change="loadAlarms">
            <el-option label="严重" value="CRITICAL" />
            <el-option label="警告" value="WARNING" />
            <el-option label="信息" value="INFO" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterType" placeholder="类型" clearable @change="loadAlarms">
            <el-option label="设备离线" value="OFFLINE" />
            <el-option label="传感器异常" value="SENSOR_ABNORMAL" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-input v-model="filterDeviceId" placeholder="设备ID" clearable @input="onSearchInput" />
        </el-col>
        <el-col :span="4">
          <el-button type="default" @click="clearFilters">清除筛选</el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 告警表格 -->
    <el-card shadow="never">
      <el-table
        ref="tableRef"
        :data="alarms"
        v-loading="loading"
        stripe
        style="width: 100%"
        @selection-change="handleSelectionChange"
        :default-sort="{ prop: 'createdAt', order: 'descending' }"
      >
        <el-table-column type="selection" width="45" />
        <el-table-column label="级别" width="80">
          <template #default="{ row }">
            <el-tag
              :type="row.severity === 'CRITICAL' ? 'danger' : row.severity === 'WARNING' ? 'warning' : 'info'"
              size="small"
            >
              {{ severityLabel(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PENDING' ? 'danger' : 'success'" size="small">
              {{ row.status === 'PENDING' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备ID" width="110" />
        <el-table-column label="告警类型" width="110">
          <template #default="{ row }">
            {{ row.alarmType === 'OFFLINE' ? '设备离线' : row.alarmType === 'SENSOR_ABNORMAL' ? '传感器异常' : row.alarmType }}
          </template>
        </el-table-column>
        <el-table-column prop="content" label="告警内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="触发时间" width="170" sortable="custom">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="resolvedAt" label="处理时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.resolvedAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="resolvedBy" label="处理人" width="100">
          <template #default="{ row }">
            {{ row.resolvedBy || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              type="primary"
              link
              @click="openResolveDialog(row)"
            >处理</el-button>
            <el-button type="primary" link @click="goDevice(row.deviceId)">查看设备</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无告警记录" />
        </template>
      </el-table>

      <el-pagination
        v-if="totalElements > 0"
        style="margin-top: 16px; justify-content: flex-end"
        background
        layout="total, prev, pager, next"
        :total="totalElements"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadAlarms"
      />
    </el-card>

    <!-- 处理告警对话框 -->
    <el-dialog v-model="resolveDialog" title="处理告警" width="480px">
      <el-form :model="resolveForm" label-width="80px">
        <el-form-item label="告警内容">
          <el-input :model-value="resolvingAlarm?.content" disabled type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="处理人" required>
          <el-input v-model="resolveForm.resolvedBy" placeholder="请输入处理人姓名" />
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="resolveForm.notes" placeholder="可选：处理过程描述" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="handleResolve">
          确认处理
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAlarms, resolveAlarm, batchResolve, getPendingCount } from '../api/alarm'
import { formatTime, debounce, resetPage } from '../utils/common'

const router = useRouter()

// 数据
const alarms = ref([])
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = 15
const pendingCount = ref(0)
const selectedIds = ref([])

// 筛选
const filterStatus = ref('')
const filterSeverity = ref('')
const filterType = ref('')
const filterDeviceId = ref('')

// 处理对话框
const resolveDialog = ref(false)
const resolving = ref(false)
const resolvingAlarm = ref(null)
const resolveForm = ref({ resolvedBy: 'admin', notes: '' })

const onSearchInput = debounce(loadAlarms, 300)

function severityLabel(s) {
  const map = { CRITICAL: '严重', WARNING: '警告', INFO: '信息' }
  return map[s] || s
}

function clearFilters() {
  filterStatus.value = ''
  filterSeverity.value = ''
  filterType.value = ''
  filterDeviceId.value = ''
  loadAlarms()
}

function handleSelectionChange(rows) {
  selectedIds.value = rows.filter(r => r.status === 'PENDING').map(r => r.id)
}

async function loadAlarms() {
  loading.value = true
  resetPage(currentPage)
  try {
    const params = { page: currentPage.value - 1, size: pageSize }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterSeverity.value) params.severity = filterSeverity.value
    if (filterType.value) params.type = filterType.value
    if (filterDeviceId.value) params.deviceId = filterDeviceId.value

    const res = await getAlarms(params)
    const data = res?.data || res
    alarms.value = data.content || data || []
    totalElements.value = data.totalElements || alarms.value.length
  } catch {
    alarms.value = []
  } finally {
    loading.value = false
  }
}

async function loadPendingCount() {
  try {
    const res = await getPendingCount()
    pendingCount.value = (res?.data || res)?.pendingCount || 0
  } catch { /* ignore */ }
}

function openResolveDialog(row) {
  resolvingAlarm.value = row
  resolveForm.value = { resolvedBy: 'admin', notes: '' }
  resolveDialog.value = true
}

async function handleResolve() {
  if (!resolveForm.value.resolvedBy?.trim()) {
    ElMessage.warning('请输入处理人姓名')
    return
  }
  resolving.value = true
  try {
    await resolveAlarm(resolvingAlarm.value.id, {
      resolvedBy: resolveForm.value.resolvedBy,
      notes: resolveForm.value.notes
    })
    ElMessage.success('告警已处理')
    resolveDialog.value = false
    loadAlarms()
    loadPendingCount()
  } catch {
    // 错误已拦截
  } finally {
    resolving.value = false
  }
}

async function handleBatchResolve() {
  try {
    await ElMessageBox.confirm(
      `确定批量处理选中的 ${selectedIds.value.length} 个告警吗？`,
      '批量处理确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  try {
    const res = await batchResolve({
      ids: selectedIds.value,
      resolvedBy: 'admin',
      notes: '批量处理'
    })
    const result = res?.data || res
    ElMessage.success(`成功处理 ${result.success} 个告警`)
    selectedIds.value = []
    loadAlarms()
    loadPendingCount()
  } catch {
    // 错误已拦截
  }
}

function goDevice(deviceId) {
  // 通过 deviceId 跳转到设备详情（需要先查 id）
  router.push(`/devices/${deviceId}`)
}

onMounted(() => {
  loadAlarms()
  loadPendingCount()
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
.header-actions {
  display: flex; gap: 12px; align-items: center;
}
.pending-badge { margin-left: 8px; }
</style>
