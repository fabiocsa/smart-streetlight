<template>
  <div class="device-list">
    <!-- Header -->
    <div class="page-header">
      <h2>设备管理</h2>
      <div class="page-header-right">
        <el-input
          v-model="searchQuery"
          placeholder="搜索设备名称/编号/位置"
          clearable
          prefix-icon="Search"
          style="width: 260px"
          @input="handleSearchInput"
        />
        <el-button type="primary" @click="showAddDialog = true">
          <el-icon><Plus /></el-icon>添加设备
        </el-button>
      </div>
    </div>

    <template v-if="!deviceStore.loading && filteredDevices.length === 0 && searchQuery">
      <el-card shadow="hover">
        <div class="empty-state">
          <span style="font-size: 48px">🔍</span>
          <span style="color: #909399; font-size: 14px;">没有找到匹配的设备</span>
          <el-button size="small" @click="searchQuery = ''; handleSearchInput()">清除搜索</el-button>
        </div>
      </el-card>
    </template>
    <template v-else-if="!deviceStore.loading && deviceStore.devices.length === 0">
      <el-card shadow="hover">
        <div class="empty-state">
          <span style="font-size: 48px">🏮</span>
          <span style="color: #909399; font-size: 14px;">还没有添加任何路灯设备</span>
          <el-button type="primary" size="small" @click="showAddDialog = true">立即添加</el-button>
        </div>
      </el-card>
    </template>
    <template v-else>
      <el-card shadow="hover">
      <el-table :data="filteredDevices" v-loading="deviceStore.loading" stripe style="width: 100%">
        <el-table-column label="设备名称" min-width="140">
          <template #default="{ row }">
            <div v-if="editCell?.id === row.id && editCell?.field === 'name'" class="inline-edit">
              <el-input
                v-model="editCell.value"
                size="small"
                @blur="saveInlineEdit(row)"
                @keyup.enter="saveInlineEdit(row)"
                @keyup.escape="cancelInlineEdit"
              />
            </div>
            <span v-else class="inline-text" @click="startInlineEdit(row, 'name', row.name)">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备编号" width="120" />
        <el-table-column label="安装位置" min-width="140">
          <template #default="{ row }">
            <div v-if="editCell?.id === row.id && editCell?.field === 'location'" class="inline-edit">
              <el-input
                v-model="editCell.value"
                size="small"
                @blur="saveInlineEdit(row)"
                @keyup.enter="saveInlineEdit(row)"
                @keyup.escape="cancelInlineEdit"
              />
            </div>
            <span v-else class="inline-text" @click="startInlineEdit(row, 'location', row.location || '')">{{ row.location || '未知位置' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="在线状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'online' ? 'success' : 'info'" size="small">
              {{ row.status === 'online' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="灯光状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.lightStatus === 'on' ? 'warning' : 'info'" size="small">
              {{ row.lightStatus === 'on' ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="控制模式" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.controlMode === 'auto'"
              active-text="自动"
              inactive-text="手动"
              @change="(val) => handleModeChange(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="170">
          <template #default="{ row }">
            <span style="font-size: 12px; color: #909399;">
              {{ row.lastHeartbeat ? formatTime(row.lastHeartbeat) : '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="$router.push(`/devices/${row.id}`)">
              详情
            </el-button>
            <el-button size="small" type="primary" link @click="handleEdit(row)">
              编辑
            </el-button>
            <el-button size="small" type="primary" link @click="handleThreshold(row)">
              阈值
            </el-button>
            <el-popconfirm title="确定删除该设备？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      </el-card>
    </template>

    <!-- Add Device Dialog -->
    <el-dialog v-model="showAddDialog" title="添加设备" width="420px">
      <el-form :model="addForm" label-width="90px" :rules="addRules" ref="addFormRef">
        <el-form-item label="设备名称" prop="name">
          <el-input v-model="addForm.name" placeholder="如：路灯A-01" />
        </el-form-item>
        <el-form-item label="设备编号" prop="deviceId">
          <el-input v-model="addForm.deviceId" placeholder="如：SL-001" />
        </el-form-item>
        <el-form-item label="安装位置" prop="location">
          <el-input v-model="addForm.location" placeholder="如：校门口" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAdd" :loading="addLoading">确定</el-button>
      </template>
    </el-dialog>

    <!-- Edit Dialog -->
    <el-dialog v-model="showEditDialog" title="编辑设备" width="420px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="设备名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="安装位置">
          <el-input v-model="editForm.location" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="handleEditConfirm" :loading="editLoading">保存</el-button>
      </template>
    </el-dialog>

    <!-- Threshold Dialog -->
    <el-dialog v-model="showThresholdDialog" title="设置光照阈值" width="420px">
      <el-form :model="thresholdForm" label-width="120px">
        <el-form-item label="开灯光照阈值">
          <el-input-number v-model="thresholdForm.thresholdOn" :min="0" :max="500" :step="5" />
          <span style="margin-left: 8px; color: #909399;">Lux</span>
        </el-form-item>
        <el-form-item label="关灯光照阈值">
          <el-input-number v-model="thresholdForm.thresholdOff" :min="0" :max="500" :step="5" />
          <span style="margin-left: 8px; color: #909399;">Lux</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showThresholdDialog = false">取消</el-button>
        <el-button type="primary" @click="handleThresholdConfirm" :loading="thresholdLoading">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addDevice, updateDevice, deleteDevice, setControlMode, setThreshold } from '@/api/device'
import { useDeviceStore } from '@/stores/device'
import { useAlarmStore } from '@/stores/alarm'

const deviceStore = useDeviceStore()
const alarmStore = useAlarmStore()

// Search
const searchQuery = ref('')
let searchTimer = null

const filteredDevices = computed(() => {
  const devices = deviceStore.devices
  if (!Array.isArray(devices)) return []
  if (!searchQuery.value) return devices
  const q = searchQuery.value.toLowerCase()
  return devices.filter(d =>
    d.name?.toLowerCase().includes(q) ||
    d.deviceId?.toLowerCase().includes(q) ||
    d.location?.toLowerCase().includes(q)
  )
})

// Inline editing
const editCell = ref(null)

function startInlineEdit(row, field, value) {
  editCell.value = { id: row.id, field, value: value ?? '' }
  // Auto-focus the input after render using DOM query
  nextTick(() => {
    const input = document.querySelector('.inline-edit .el-input__inner')
    if (input) input.focus()
  })
}

async function saveInlineEdit(row) {
  if (!editCell.value) return
  const { field, value } = editCell.value
  editCell.value = null
  try {
    await updateDevice(row.id, { [field]: value })
    row[field] = value
    ElMessage.success(`${field === 'name' ? '名称' : '位置'}已更新`)
  } catch (e) {
    // handled by interceptor
  }
}

function cancelInlineEdit() {
  editCell.value = null
}

// Add dialog
const showAddDialog = ref(false)
const addLoading = ref(false)
const addFormRef = ref(null)
const addForm = ref({ name: '', deviceId: '', location: '' })
const addRules = {
  name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
  deviceId: [
    { required: true, message: '请输入设备编号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9-]{2,20}$/, message: '格式：字母+数字+中划线，2-20字符', trigger: 'blur' }
  ]
}

// Edit dialog
const showEditDialog = ref(false)
const editLoading = ref(false)
const editForm = ref({ id: null, name: '', location: '' })

// Threshold dialog
const showThresholdDialog = ref(false)
const thresholdLoading = ref(false)
const thresholdForm = ref({ id: null, thresholdOn: 50, thresholdOff: 100 })
const thresholdPreset = ref(null)
const thresholdBatchApply = ref(false)
const thresholdValidateError = ref('')
const thresholdModified = ref(false)

function formatTime(t) {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN')
}

async function handleAdd() {
  if (!addFormRef.value) return
  const valid = await addFormRef.value.validate().catch(() => false)
  if (!valid) return

  addLoading.value = true
  try {
    await addDevice(addForm.value)
    ElMessage.success('设备添加成功')
    showAddDialog.value = false
    addForm.value = { name: '', deviceId: '', location: '' }
    deviceStore.fetchDevices()
  } catch (e) {
    // Error handled by interceptor
  } finally {
    addLoading.value = false
  }
}

function handleEdit(row) {
  editForm.value = { id: row.id, name: row.name, location: row.location }
  showEditDialog.value = true
}

async function handleEditConfirm() {
  editLoading.value = true
  try {
    await updateDevice(editForm.value.id, {
      name: editForm.value.name,
      location: editForm.value.location
    })
    ElMessage.success('设备更新成功')
    showEditDialog.value = false
    deviceStore.fetchDevices()
  } catch (e) {
    // handled by interceptor
  } finally {
    editLoading.value = false
  }
}

async function handleDelete(id) {
  // Check for pending alarms
  const pendingAlarms = alarmStore.alarms.filter(a => a.status === 'pending' && a.deviceId === deviceStore.devices.find(d => d.id === id)?.deviceId)
  if (pendingAlarms.length > 0) {
    try {
      await ElMessageBox.confirm(
        `该设备有 ${pendingAlarms.length} 条未处理告警，删除后数据不可恢复，是否继续？`,
        '删除确认',
        { confirmButtonText: '继续删除', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }
  try {
    await deleteDevice(id)
    ElMessage.success('设备已删除')
    deviceStore.fetchDevices()
  } catch (e) {
    // handled by interceptor
  }
}

async function handleModeChange(row, isAuto) {
  const mode = isAuto ? 'auto' : 'manual'
  try {
    await setControlMode(row.id, mode)
    row.controlMode = mode
    ElMessage.success(`已切换为${isAuto ? '自动' : '手动'}模式`)
  } catch (e) {
    // handled by interceptor
  }
}

function handleThreshold(row) {
  thresholdForm.value = {
    id: row.id,
    thresholdOn: row.thresholdOn ?? 50,
    thresholdOff: row.thresholdOff ?? 100
  }
  showThresholdDialog.value = true
}

async function handleThresholdConfirm() {
  thresholdLoading.value = true
  try {
    await setThreshold(thresholdForm.value.id, thresholdForm.value.thresholdOn, thresholdForm.value.thresholdOff)
    ElMessage.success('阈值设置成功')
    showThresholdDialog.value = false
    deviceStore.fetchDevices()
  } catch (e) {
    // handled by interceptor
  } finally {
    thresholdLoading.value = false
  }
}

function handleSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    // The filteredDevices computed will auto-update
  }, 300)
}

onMounted(() => {
  deviceStore.fetchDevices()
})
</script>

<style scoped>
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

.page-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 0;
}

.inline-text {
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  transition: background 0.2s;
}

.inline-text:hover {
  background: #ecf5ff;
}

.inline-edit {
  max-width: 180px;
}

.threshold-slider-group {
  display: flex;
  align-items: center;
  width: 100%;
}
</style>
