<template>
  <div>
    <div class="page-header">
      <h2>{{ authStore.isAdmin || authStore.isOperator ? '传感器管理' : '传感器查看' }}</h2>
      <div class="header-actions">
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
          type="success"
          @click="handleBatchEnable(true)"
        >
          批量启用 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
          type="warning"
          @click="handleBatchEnable(false)"
        >
          批量禁用 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
          type="danger"
          @click="handleBatchDelete"
        >
          <el-icon><Delete /></el-icon> 批量删除 ({{ selectedIds.length }})
        </el-button>
        <el-button type="primary" @click="refreshAll">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <!-- 筛选 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="16">
        <el-col :span="6">
          <el-input v-model="searchKeyword" placeholder="搜索传感器名称/主题" clearable @input="onSearchInput" />
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterDeviceId" placeholder="按设备筛选" clearable @change="filterSensors">
            <el-option
              v-for="d in deviceStore.devices"
              :key="d.deviceId"
              :label="`${d.name} (${d.deviceId})`"
              :value="d.deviceId"
            />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterType" placeholder="传感器类型" clearable @change="filterSensors">
            <el-option label="光照" value="light" />
            <el-option label="温度" value="temperature" />
            <el-option label="湿度" value="humidity" />
            <el-option label="功率" value="power" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterEnabled" placeholder="启用状态" clearable @change="filterSensors">
            <el-option label="已启用" :value="true" />
            <el-option label="已停用" :value="false" />
          </el-select>
        </el-col>
      </el-row>
    </el-card>

    <!-- 传感器表格 -->
    <el-card shadow="never">
      <el-table
        ref="tableRef"
        :data="paginatedSensors"
        v-loading="sensorStore.loading"
        stripe
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" />
        <el-table-column prop="id" label="ID" width="60" sortable />
        <el-table-column label="绑定状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.boundDeviceId ? 'success' : 'info'">
              {{ row.boundDeviceId || '未绑定' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="传感器名称" min-width="140" sortable />
        <el-table-column prop="sensorType" label="类型" width="90" sortable>
          <template #default="{ row }">
            {{ typeLabel(row.sensorType) }}
          </template>
        </el-table-column>
        <el-table-column prop="dataTopic" label="数据主题" min-width="200" show-overflow-tooltip />
        <el-table-column label="上报频率" width="120" sortable prop="reportFrequency">
          <template #default="{ row }">
            <el-input-number
              v-if="authStore.isAdmin || authStore.isOperator"
              :model-value="row.reportFrequency"
              :min="1"
              :max="3600"
              size="small"
              style="width: 80px"
              @change="(v) => handleFrequencyChange(row, v)"
            />
            <span v-else>{{ row.reportFrequency || '-' }}</span>
            <span v-if="authStore.isAdmin || authStore.isOperator" style="margin-left: 2px; font-size: 12px; color: #909399">秒</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              v-if="authStore.isAdmin || authStore.isOperator"
              :model-value="row.enabled"
              @change="(v) => handleToggleEnabled(row, v)"
            />
            <el-tag v-else :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="配置" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.configJson || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170" sortable prop="updatedAt">
          <template #default="{ row }">
            {{ formatTime(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openHistoryDialog(row)">历史数据</el-button>
            <el-button v-if="authStore.isAdmin || authStore.isOperator" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm
              v-if="authStore.isAdmin || authStore.isOperator"
              title="确定删除该传感器吗？模拟器仍在运行时会自动重新识别。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="hasFilter ? '没有匹配的传感器' : '暂无传感器数据'">
            <el-button v-if="hasFilter" type="primary" @click="clearFilters">清除筛选</el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-if="filteredSensors.length > pageSize"
        style="margin-top: 16px; justify-content: flex-end"
        background
        layout="total, prev, pager, next"
        :total="filteredSensors.length"
        :page-size="pageSize"
        v-model:current-page="currentPage"
      />
    </el-card>

    <!-- 传感器编辑对话框 -->
    <SensorForm
      v-model:visible="dialogVisible"
      :edit-data="editingSensor"
      @saved="handleSaved"
    />

    <!-- 传感器历史数据对话框 -->
    <SensorHistoryDialog
      v-model:visible="historyDialogVisible"
      :sensor="selectedSensorForHistory"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDeviceStore } from '../store/device'
import { useSensorStore } from '../store/sensor'
import SensorForm from '../components/SensorForm.vue'
import SensorHistoryDialog from '../components/SensorHistoryDialog.vue'
import { formatTime, typeLabel, debounce, resetPage } from '../utils/common'
import { useAuthStore } from '../stores/authStore'

const deviceStore = useDeviceStore()
const sensorStore = useSensorStore()
const authStore = useAuthStore()

const searchKeyword = ref('')
const filterDeviceId = ref('')
const filterType = ref('')
const filterEnabled = ref('')
const dialogVisible = ref(false)
const editingSensor = ref(null)
const historyDialogVisible = ref(false)
const selectedSensorForHistory = ref(null)
const currentPage = ref(1)
const pageSize = 10
const selectedIds = ref([])
const tableRef = ref(null)

const hasFilter = computed(() =>
  !!(searchKeyword.value || filterDeviceId.value || filterType.value || filterEnabled.value !== '')
)

const filteredSensors = computed(() => {
  let list = sensorStore.allSensors
  if (filterDeviceId.value) list = list.filter(s => s.boundDeviceId === filterDeviceId.value)
  if (filterType.value) list = list.filter(s => s.sensorType === filterType.value)
  if (filterEnabled.value !== '' && filterEnabled.value !== null && filterEnabled.value !== undefined) {
    list = list.filter(s => !!s.enabled === filterEnabled.value)
  }
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(s =>
      s.displayName?.toLowerCase().includes(kw) ||
      s.dataTopic?.toLowerCase().includes(kw)
    )
  }
  return list
})

const paginatedSensors = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredSensors.value.slice(start, start + pageSize)
})

// 防抖搜索
const onSearchInput = debounce(filterSensors, 300)

function filterSensors() {
  resetPage(currentPage)
}

function clearFilters() {
  searchKeyword.value = ''
  filterDeviceId.value = ''
  filterType.value = ''
  filterEnabled.value = ''
  filterSensors()
}

function handleSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.id)
}

async function refreshAll() {
  await sensorStore.fetchAll()
  selectedIds.value = []
}

function openEditDialog(sensor) {
  editingSensor.value = { ...sensor }
  dialogVisible.value = true
}

function openHistoryDialog(sensor) {
  selectedSensorForHistory.value = sensor
  historyDialogVisible.value = true
}

function handleSaved() {
  dialogVisible.value = false
  // store 已局部更新
}

async function handleDelete(row) {
  try {
    await sensorStore.remove(row.id)
    ElMessage.success('传感器已删除，模拟器仍在运行时会自动重新识别')
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleToggleEnabled(row, enabled) {
  try {
    await sensorStore.update(row.id, { enabled })
    ElMessage.success(enabled ? '传感器已启用' : '传感器已停用')
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleFrequencyChange(row, val) {
  if (!val || val < 1) return
  try {
    await sensorStore.updateFreq(row.id, { reportFrequency: val })
    ElMessage.success(`上报频率已更新为 ${val} 秒`)
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleBatchDelete() {
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedIds.value.length} 个传感器吗？模拟器仍在运行时会自动重新识别。`,
      '批量删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  let totalSuccess = 0
  let totalFail = 0
  for (const id of selectedIds.value) {
    try {
      await sensorStore.remove(id)
      totalSuccess++
    } catch {
      totalFail++
    }
  }

  if (totalFail > 0) {
    ElMessage.warning(`成功删除 ${totalSuccess} 个，${totalFail} 个失败`)
  } else {
    ElMessage.success(`成功删除 ${totalSuccess} 个传感器`)
  }
  selectedIds.value = []
}

async function handleBatchEnable(enabled) {
  const label = enabled ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定批量${label}选中的 ${selectedIds.value.length} 个传感器吗？`,
      `批量${label}确认`,
      { confirmButtonText: `确定${label}`, cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  let totalSuccess = 0
  let totalFail = 0
  for (const id of selectedIds.value) {
    try {
      await sensorStore.update(id, { enabled })
      totalSuccess++
    } catch {
      totalFail++
    }
  }

  if (totalFail > 0) {
    ElMessage.warning(`成功${label} ${totalSuccess} 个，${totalFail} 个失败`)
  } else {
    ElMessage.success(`成功${label} ${totalSuccess} 个传感器`)
  }
  selectedIds.value = []
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
  flex-wrap: wrap; gap: 8px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
.header-actions {
  display: flex; gap: 8px; flex-wrap: wrap;
}
</style>
