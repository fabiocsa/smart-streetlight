<template>
  <div class="alarm-list">
    <div class="page-header">
      <h2>告警管理</h2>
    </div>

    <el-card shadow="hover">
      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-form :inline="true" :model="filters">
          <el-form-item label="状态">
            <el-select v-model="filters.status" placeholder="全部" clearable style="width: 130px">
              <el-option label="待处理" value="pending" />
              <el-option label="已处理" value="resolved" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="filters.type" placeholder="全部" clearable style="width: 150px">
              <el-option label="设备离线" value="offline" />
              <el-option label="传感器异常" value="sensor_abnormal" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- Alarm Table -->
      <el-table :data="alarmStore.alarms" v-loading="alarmStore.loading" stripe style="width: 100%">
        <el-table-column prop="deviceId" label="设备编号" width="120" />
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
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { resolveAlarm } from '@/api/alarm'
import { useAlarmStore } from '@/stores/alarm'

const alarmStore = useAlarmStore()

const filters = reactive({
  status: '',
  type: ''
})
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)

const totalPages = computed(() => Math.ceil(totalElements.value / pageSize.value))

const showResolveDialog = ref(false)
const resolveLoading = ref(false)
const resolveForm = ref({ id: null, resolvedBy: '' })

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadAlarms() {
  const params = {
    page: currentPage.value - 1,
    size: pageSize.value
  }
  if (filters.status) params.status = filters.status
  if (filters.type) params.type = filters.type

  const res = await alarmStore.fetchAlarms(params)
  if (res) {
    totalElements.value = res.totalElements || 0
  }
}

function handleSearch() {
  currentPage.value = 1
  loadAlarms()
}

function handleReset() {
  filters.status = ''
  filters.type = ''
  currentPage.value = 1
  loadAlarms()
}

function handlePageChange(page) {
  currentPage.value = page
  loadAlarms()
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

onMounted(() => {
  loadAlarms()
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

.filter-bar {
  margin-bottom: 16px;
}

.pagination-wrapper {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
