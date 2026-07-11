<template>
  <div>
    <div class="page-header">
      <h2>设备管理</h2>
      <div class="header-actions">
        <el-button-group style="margin-right: 12px">
          <el-button
            :type="viewMode === 'table' ? 'primary' : 'default'"
            @click="viewMode = 'table'"
            size="default"
          >
            <el-icon><Grid /></el-icon> 表格模式
          </el-button>
          <el-button
            :type="viewMode === 'map' ? 'primary' : 'default'"
            @click="switchToMap"
            size="default"
          >
            <el-icon><MapLocation /></el-icon> 地图模式
          </el-button>
        </el-button-group>
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
        <el-dropdown
          v-if="selectedIds.length > 0"
          trigger="click"
          @command="handleBatchModeChange"
          style="margin-left: 8px"
        >
          <el-button type="primary">
            <el-icon><Setting /></el-icon> 批量切换模式
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="auto">切换为自动模式</el-dropdown-item>
              <el-dropdown-item command="manual">切换为手动模式</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          v-if="selectedIds.length > 0 && authStore.isAdmin"
          type="info"
          @click="handleBatchUnbind"
        >
          <el-icon><Unlock /></el-icon> 批量解绑传感器 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
          type="danger"
          @click="handleBatchDelete"
        >
          <el-icon><Delete /></el-icon> 批量删除 ({{ selectedIds.length }})
        </el-button>
        <el-button v-if="authStore.isAdmin || authStore.isOperator" type="primary" @click="openAddDialog">
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

    <!-- 表格模式 -->
    <template v-if="viewMode === 'table'">
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
                v-if="authStore.isAdmin || authStore.isOperator"
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
    </template>

    <!-- 地图模式 -->
    <template v-else>
      <el-card shadow="never">
        <div style="margin-bottom: 12px; color: #909399; font-size: 13px;">
          地图上显示 {{ filteredDevices.filter(d => d.latitude != null && d.longitude != null).length }} /
          {{ filteredDevices.length }} 个设备
          <span v-if="filteredDevices.filter(d => d.latitude == null || d.longitude == null).length > 0" style="color: #e6a23c; margin-left: 8px;">
            ⚠ {{ filteredDevices.filter(d => d.latitude == null || d.longitude == null).length }} 个设备缺少坐标信息
          </span>
        </div>
        <DeviceMap :devices="filteredDevices" height="600px" @refresh="refreshDevices" @add-device="handleMapAddDevice" />
      </el-card>
    </template>

    <!-- 添加/编辑对话框 -->
    <DeviceForm
      v-model:visible="dialogVisible"
      :edit-data="editingDevice"
      :init-coords="mapAddCoords"
      @saved="handleSaved"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Sunny, Moon, Refresh, Grid, MapLocation, Setting, Unlock } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/authStore'
import { useDeviceStore } from '../store/device'
import DeviceForm from '../components/DeviceForm.vue'
import DeviceMap from '../components/DeviceMap.vue'
import { sendControl, sendBatchControl, setControlMode } from '../api/control'
import { unbindSensor } from '../api/device'
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
const viewMode = ref('table')   // 'table' | 'map'
const mapAddCoords = ref(null)  // 地图添加设备坐标

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

/** 切换地图模式时清空表格选中状态 */
function switchToMap() {
  viewMode.value = 'map'
  selectedIds.value = []
  tableRef.value?.clearSelection()
}

/** 地图「添加设备」事件：打开对话框并预填经纬度 */
function handleMapAddDevice({ latitude, longitude }) {
  mapAddCoords.value = { latitude, longitude }
  editingDevice.value = null
  dialogVisible.value = true
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
  mapAddCoords.value = null
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

  // 过滤出在线设备
  const selectedDevices = deviceStore.devices.filter(d => selectedIds.value.includes(d.id))
  const onlineDevices = selectedDevices.filter(d => d.status === 'online')
  const offlineCount = selectedDevices.length - onlineDevices.length

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
    // 局部更新在线设备的状态
    onlineDevices.forEach(d => { d.lightStatus = command; d.controlMode = 'manual' })
    selectedIds.value = []
  } catch {
    // 错误已拦截
  }
}

/** 批量切换控制模式 */
async function handleBatchModeChange(mode) {
  const modeText = mode === 'auto' ? '自动' : '手动'
  const selectedDevices = deviceStore.devices.filter(d => selectedIds.value.includes(d.id))
  const onlineDevices = selectedDevices.filter(d => d.status === 'online')
  const offlineCount = selectedDevices.length - onlineDevices.length

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
  }
  selectedIds.value = []
}

/** 批量解绑传感器 */
async function handleBatchUnbind() {
  const selectedDevices = deviceStore.devices.filter(d => selectedIds.value.includes(d.id))
  const onlineDevices = selectedDevices.filter(d => d.status === 'online')
  const offlineCount = selectedDevices.length - onlineDevices.length

  if (onlineDevices.length === 0) {
    ElMessage.warning('选中的设备均处于离线状态')
    return
  }

  // 统计有传感器的设备
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
    // 本地清空传感器的绑定
    d.sensors = []
  }

  if (successCount > 0) {
    ElMessage.success(`已解绑 ${successCount} 个传感器` + (failCount > 0 ? `，${failCount} 个失败` : ''))
  } else {
    ElMessage.error('解绑失败')
  }
  selectedIds.value = []
  // 刷新设备数据，确保表格和地图同步
  await deviceStore.fetchAll()
}

async function handleSaved() {
  dialogVisible.value = false
  mapAddCoords.value = null
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
