<template>
  <div>
    <div class="page-header">
      <h2>设备管理</h2>
      <div class="header-actions">
        <el-button
          v-if="selectedIds.length > 0"
          type="success"
          @click="handleBatchControl('on')"
        >
          <el-icon><Sunny /></el-icon> 批量开灯 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0"
          type="warning"
          @click="handleBatchControl('off')"
        >
          <el-icon><Moon /></el-icon> 批量关灯 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0 && authStore.isAdmin"
          type="danger"
          @click="handleBatchDelete"
        >
          <el-icon><Delete /></el-icon> 批量删除 ({{ selectedIds.length }})
        </el-button>
        <el-button v-if="authStore.isAdmin" type="primary" @click="openAddDialog">
            <el-icon><Plus /></el-icon> 添加设备
          </el-button>
      </div>
    </div>

    <!-- 搜索栏 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="16">
        <el-col :span="6">
          <el-input v-model="searchKeyword" placeholder="搜索名称/设备ID/位置" clearable @input="onSearchInput" />
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterStatus" placeholder="设备状态" clearable @change="filterDevices">
            <el-option label="在线" value="online" />
            <el-option label="离线" value="offline" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterControlMode" placeholder="控制模式" clearable @change="filterDevices">
            <el-option label="自动" value="auto" />
            <el-option label="手动" value="manual" />
          </el-select>
        </el-col>
      </el-row>
    </el-card>

    <!-- 设备表格 -->
    <el-card shadow="never">
      <el-table
        ref="tableRef"
        :data="paginatedDevices"
        v-loading="deviceStore.loading"
        stripe
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" />
        <el-table-column prop="deviceId" label="设备ID" width="120" sortable />
        <el-table-column prop="name" label="设备名称" min-width="140" sortable />
        <el-table-column prop="location" label="安装位置" min-width="140" sortable />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'online' ? 'success' : 'danger'" size="small">
              {{ row.status === 'online' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="灯光" width="80">
          <template #default="{ row }">
            <el-tag :type="row.lightStatus === 'on' ? 'warning' : 'info'" size="small">
              {{ row.lightStatus === 'on' ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="控制模式" width="100" sortable prop="controlMode">
          <template #default="{ row }">
            {{ row.controlMode === 'auto' ? '自动' : '手动' }}
          </template>
        </el-table-column>
        <el-table-column label="传感器数" width="90">
          <template #default="{ row }">
            <el-link type="primary" @click="goDetail(row.id)">
              {{ row.sensorCount ?? '-' }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="170" sortable prop="lastHeartbeat">
          <template #default="{ row }">
            {{ formatTime(row.lastHeartbeat) }}
          </template>
        </el-table-column>
        <el-table-column label="快捷控制" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'online'"
              :type="row.lightStatus === 'on' ? 'warning' : 'success'"
              size="small"
              :loading="controllingId === row.deviceId"
              @click="handleQuickControl(row)"
            >
              {{ row.lightStatus === 'on' ? '关灯' : '开灯' }}
            </el-button>
            <el-tooltip v-else content="设备离线，无法控制" placement="top">
              <el-button size="small" disabled type="info">不可控</el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goDetail(row.id)">详情</el-button>
            <el-button v-if="authStore.isAdmin" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm
              v-if="authStore.isAdmin"
              title="确定删除该设备吗？"
              confirm-button-text="确定删除"
              cancel-button-text="取消"
              @confirm="handleDelete(row.id)"
            >
              <template #reference>
                <el-button type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="searchKeyword || filterStatus || filterControlMode"
            description="没有匹配的设备">
            <el-button type="primary" @click="clearFilters">清除筛选</el-button>
          </el-empty>
          <el-empty v-else description="暂无设备">
            <template #description>
              <p style="color: #909399; margin: 0">尚未添加任何设备</p>
              <p style="color: #c0c4cc; font-size: 12px; margin: 4px 0 0 0">
                点击「添加设备」通过 REST API 创建，传感器将通过 MQTT 自动挂载到已有设备下
              </p>
            </template>
            <el-button type="primary" @click="refreshDevices">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-if="filteredDevices.length > pageSize"
        style="margin-top: 16px; justify-content: flex-end"
        background
        layout="total, prev, pager, next"
        :total="filteredDevices.length"
        :page-size="pageSize"
        v-model:current-page="currentPage"
      />
    </el-card>

    <!-- 添加/编辑对话框 -->
    <DeviceForm
      v-model:visible="dialogVisible"
      :edit-data="editingDevice"
      @saved="handleSaved"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Sunny, Moon, Refresh } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/authStore'
import { useDeviceStore } from '../store/device'
import DeviceForm from '../components/DeviceForm.vue'
import { sendControl, sendBatchControl } from '../api/control'
import { formatTime, debounce, resetPage } from '../utils/common'

const router = useRouter()
const deviceStore = useDeviceStore()
const authStore = useAuthStore()
const tableRef = ref(null)

const searchKeyword = ref('')
const filterStatus = ref('')
const filterControlMode = ref('')
const dialogVisible = ref(false)
const editingDevice = ref(null)
const currentPage = ref(1)
const pageSize = 10
const selectedIds = ref([])
const controllingId = ref('')   // 当前正在控制的设备ID

// 防抖搜索
const onSearchInput = debounce(filterDevices, 300)

const filteredDevices = computed(() => {
  let list = deviceStore.devices
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(d =>
      d.name?.toLowerCase().includes(kw) ||
      d.deviceId?.toLowerCase().includes(kw) ||
      d.location?.toLowerCase().includes(kw)
    )
  }
  if (filterStatus.value) {
    list = list.filter(d => d.status === filterStatus.value)
  }
  if (filterControlMode.value) {
    list = list.filter(d => d.controlMode === filterControlMode.value)
  }
  return list
})

const paginatedDevices = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredDevices.value.slice(start, start + pageSize)
})

function filterDevices() {
  resetPage(currentPage)
}

function clearFilters() {
  searchKeyword.value = ''
  filterStatus.value = ''
  filterControlMode.value = ''
  filterDevices()
}

function handleSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.id)
}

function openAddDialog() {
  editingDevice.value = null
  dialogVisible.value = true
}

function openEditDialog(row) {
  editingDevice.value = { ...row }
  dialogVisible.value = true
}

function goDetail(id) {
  router.push(`/devices/${id}`)
}

async function handleDelete(id) {
  try {
    await deviceStore.remove(id)
    ElMessage.success('设备已删除')
    selectedIds.value = selectedIds.value.filter(i => i !== id)
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleBatchDelete() {
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedIds.value.length} 个设备吗？此操作不可恢复。`,
      '批量删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }

  const results = await deviceStore.removeBatch(selectedIds.value)
  const successCount = results.filter(r => r.success).length
  const failCount = results.filter(r => !r.success).length

  if (failCount > 0) {
    ElMessage.warning(`成功删除 ${successCount} 个，${failCount} 个失败`)
  } else {
    ElMessage.success(`成功删除 ${successCount} 个设备`)
  }
  selectedIds.value = []
}

/** 单行快捷控制（开关灯） */
async function handleQuickControl(row) {
  const newCmd = row.lightStatus === 'on' ? 'off' : 'on'
  const actionText = newCmd === 'on' ? '开灯' : '关灯'

  try {
    await ElMessageBox.confirm(
      `确定要${actionText}设备「${row.name || row.deviceId}」吗？`,
      `${actionText}确认`,
      { confirmButtonText: actionText, cancelButtonText: '取消', type: newCmd === 'on' ? 'warning' : 'info' }
    )
  } catch { return }

  controllingId.value = row.deviceId
  try {
    await sendControl(row.deviceId, { command: newCmd })
    row.lightStatus = newCmd
    row.controlMode = 'manual'  // ★ 手动控制后切换为手动模式
    ElMessage.success(`设备「${row.name || row.deviceId}」已${actionText}，已切换为手动模式`)
  } catch {
    // 错误已拦截
  } finally {
    controllingId.value = ''
  }
}

/** 批量控制（开关灯） */
async function handleBatchControl(command) {
  const actionText = command === 'on' ? '开灯' : '关灯'
  try {
    await ElMessageBox.confirm(
      `确定要对选中的 ${selectedIds.value.length} 个设备执行批量${actionText}吗？`,
      `批量${actionText}确认`,
      { confirmButtonText: actionText, cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  // 获取选中的设备ID列表
  const selectedDevices = deviceStore.devices.filter(d => selectedIds.value.includes(d.id))
  const deviceIds = selectedDevices.map(d => d.deviceId)

  try {
    const res = await sendBatchControl({ deviceIds, command })
    const result = res?.data || res
    ElMessage.success(`批量${actionText}: 成功 ${result?.successCount ?? deviceIds.length} 个，已切换为手动模式`)
    // 局部更新设备状态
    selectedDevices.forEach(d => { d.lightStatus = command; d.controlMode = 'manual' })
    selectedIds.value = []
  } catch {
    // 错误已拦截
  }
}

async function handleSaved() {
  dialogVisible.value = false
  // store 已局部更新，无需全量刷新
}

async function refreshDevices() {
  await deviceStore.fetchAll()
  ElMessage.success('设备列表已刷新')
}

onMounted(() => {
  deviceStore.fetchAll()
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
.header-actions {
  display: flex; gap: 8px;
}
</style>
