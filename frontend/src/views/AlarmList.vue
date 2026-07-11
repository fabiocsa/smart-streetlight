<template>
  <div>
    <div class="page-header">
      <h2>告警管理</h2>
      <div class="header-actions">
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
          type="warning"
          @click="handleBatchUpdateResolvedBy"
        >
          <el-icon><Edit /></el-icon> 批量修改处理人 ({{ selectedIds.length }})
        </el-button>
        <el-button
          v-if="selectedIds.length > 0 && (authStore.isAdmin || authStore.isOperator)"
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

    <!-- 分配模式开关 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="12" align="middle">
        <el-col :span="6">
          <span style="font-weight: 600; margin-right: 12px">分配模式：</span>
          <el-switch
            v-model="isAutoMode"
            :disabled="!(authStore.isAdmin || authStore.isOperator)"
            active-text="自动"
            inactive-text="手动"
            @change="handleModeChange"
          />
        </el-col>
        <el-col :span="4">
          <el-button
            type="default"
            @click="showHandlerPanel = !showHandlerPanel"
          >
            {{ showHandlerPanel ? '收起' : '展开' }}处理人管理
          </el-button>
        </el-col>
        <el-col :span="4">
          <el-button
            type="default"
            @click="showVoltageConfig = !showVoltageConfig"
          >
            {{ showVoltageConfig ? '收起' : '展开' }}电压配置
          </el-button>
        </el-col>
        <el-col :span="4">
          <el-button
            type="default"
            @click="showTempConfig = !showTempConfig"
          >
            {{ showTempConfig ? '收起' : '展开' }}温度配置
          </el-button>
        </el-col>
        <el-col :span="4">
          <el-button
            type="default"
            @click="showPowerConfig = !showPowerConfig"
          >
            {{ showPowerConfig ? '收起' : '展开' }}功率配置
          </el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 处理人管理面板 -->
    <el-card v-if="showHandlerPanel" shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-weight: 600">处理人列表</span>
          <el-button
            v-if="authStore.isAdmin"
            type="primary"
            size="small"
            @click="openAddHandlerDialog"
          >添加处理人</el-button>
        </div>
      </template>
      <el-table :data="handlers" stripe size="small" v-loading="handlersLoading">
        <el-table-column prop="handlerName" label="处理人" width="120" />
        <el-table-column prop="handlerCount" label="处理次数" width="100" />
        <el-table-column prop="priority" label="优先级" width="100">
          <template #default="{ row }">
            <span v-if="!row._editingPriority">{{ row.priority }}</span>
            <el-input-number
              v-else
              v-model="row._priorityDraft"
              :min="0"
              :max="999"
              size="small"
              style="width: 80px"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.isOccupied === 0 ? 'success' : 'warning'" size="small">
              {{ row.isOccupied === 0 ? '空闲' : '占用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" v-if="authStore.isAdmin">
          <template #default="{ row }">
            <template v-if="!row._editingPriority">
              <el-button link type="primary" size="small" @click="startEditPriority(row)">
                编辑优先级
              </el-button>
              <el-button
                v-if="row.isOccupied === 1"
                link type="warning" size="small"
                @click="handleReleaseHandler(row)"
              >释放</el-button>
              <el-button link type="danger" size="small" @click="handleDeleteHandler(row)">
                删除
              </el-button>
            </template>
            <template v-else>
              <el-button link type="primary" size="small" @click="savePriority(row)">保存</el-button>
              <el-button link size="small" @click="row._editingPriority = false">取消</el-button>
            </template>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无处理人" />
        </template>
      </el-table>
    </el-card>

    <!-- 电压区间配置 -->
    <el-card v-if="showVoltageConfig" shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-weight: 600">电压区间配置</span>
          <el-button
            v-if="authStore.isAdmin || authStore.isOperator || authStore.isMunicipal"
            type="primary"
            size="small"
            @click="openVoltageConfigDialog"
          >编辑区间</el-button>
        </div>
      </template>
      <div style="display: flex; gap: 24px; align-items: center">
        <span>正常电压范围：</span>
        <el-tag type="success" size="large">{{ voltageMin }}V ~ {{ voltageMax }}V</el-tag>
        <span style="color: #909399; font-size: 13px">电压超出此范围将自动触发告警</span>
      </div>
    </el-card>

    <!-- 温度上限配置 -->
    <el-card v-if="showTempConfig" shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-weight: 600">温度上限配置</span>
          <el-button
            v-if="authStore.isAdmin || authStore.isOperator || authStore.isMunicipal"
            type="primary"
            size="small"
            @click="openTempConfigDialog"
          >编辑上限</el-button>
        </div>
      </template>
      <div style="display: flex; gap: 24px; align-items: center">
        <span>正常温度上限：</span>
        <el-tag type="success" size="large">{{ tempMax }}°C</el-tag>
        <span style="color: #909399; font-size: 13px">温度超过此上限将自动触发告警</span>
      </div>
    </el-card>

    <!-- 功率上限配置 -->
    <el-card v-if="showPowerConfig" shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-weight: 600">功率上限配置</span>
          <el-button
            v-if="authStore.isAdmin || authStore.isOperator || authStore.isMunicipal"
            type="primary"
            size="small"
            @click="openPowerConfigDialog"
          >编辑上限</el-button>
        </div>
      </template>
      <div style="display: flex; gap: 24px; align-items: center">
        <span>正常功率上限：</span>
        <el-tag type="success" size="large">{{ powerMax }}W</el-tag>
        <span style="color: #909399; font-size: 13px">功率超过此上限将自动触发告警</span>
      </div>
    </el-card>

    <!-- 电压配置对话框 -->
    <el-dialog v-model="voltageDialog" title="编辑电压区间" width="400px">
      <el-form :model="voltageForm" label-width="100px">
        <el-form-item label="最低电压 (V)">
          <el-input-number v-model="voltageForm.min" :min="0" :max="500" :step="1" />
        </el-form-item>
        <el-form-item label="最高电压 (V)">
          <el-input-number v-model="voltageForm.max" :min="0" :max="500" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="voltageDialog = false">取消</el-button>
        <el-button type="primary" @click="saveVoltageConfig">确定</el-button>
      </template>
    </el-dialog>

    <!-- 温度配置对话框 -->
    <el-dialog v-model="tempDialog" title="编辑温度上限" width="400px">
      <el-form :model="tempForm" label-width="100px">
        <el-form-item label="温度上限 (°C)">
          <el-input-number v-model="tempForm.max" :min="0" :max="100" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tempDialog = false">取消</el-button>
        <el-button type="primary" @click="saveTemperatureConfig">确定</el-button>
      </template>
    </el-dialog>

    <!-- 功率配置对话框 -->
    <el-dialog v-model="powerDialog" title="编辑功率上限" width="400px">
      <el-form :model="powerForm" label-width="100px">
        <el-form-item label="功率上限 (W)">
          <el-input-number v-model="powerForm.max" :min="0" :max="5000" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="powerDialog = false">取消</el-button>
        <el-button type="primary" @click="savePowerConfig">确定</el-button>
      </template>
    </el-dialog>

    <!-- 筛选栏 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="12">
        <el-col :span="4">
          <el-select v-model="filterStatus" placeholder="状态" clearable @change="loadAlarms">
            <el-option label="全部" value="" />
            <el-option label="待处理" value="PENDING" />
            <el-option label="已处理" value="RESOLVED" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterSeverity" placeholder="级别" clearable @change="loadAlarms">
            <el-option label="全部" value="" />
            <el-option label="严重" value="CRITICAL" />
            <el-option label="警告" value="WARNING" />
            <el-option label="信息" value="INFO" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterType" placeholder="类型" clearable @change="loadAlarms">
            <el-option label="全部" value="" />
            <el-option label="设备离线" value="OFFLINE" />
            <el-option label="传感器异常" value="SENSOR_ABNORMAL" />
            <el-option label="电压异常" value="VOLTAGE_ABNORMAL" />
            <el-option label="温度过高" value="TEMPERATURE_HIGH" />
            <el-option label="功率过高" value="POWER_HIGH" />
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
            {{ row.alarmType === 'OFFLINE' ? '设备离线' : row.alarmType === 'SENSOR_ABNORMAL' ? '传感器异常' : row.alarmType === 'VOLTAGE_ABNORMAL' ? '电压异常' : row.alarmType === 'TEMPERATURE_HIGH' ? '温度过高' : row.alarmType === 'POWER_HIGH' ? '功率过高' : row.alarmType }}
          </template>
        </el-table-column>
        <el-table-column prop="content" label="告警内容" min-width="200" show-overflow-tooltip />
        <el-table-column label="触发时间" width="210">
          <template #header>
            <div style="display: flex; align-items: center; gap: 6px">
              <span>触发时间</span>
              <el-button-group style="margin-left: 2px">
                <el-button
                  :type="sortOrder === 'ascending' ? 'primary' : 'default'"
                  size="small"
                  style="padding: 2px 6px; font-size: 10px; min-height: 20px"
                  @click="sortAsc"
                  title="最早优先"
                >▲</el-button>
                <el-button
                  :type="sortOrder === 'descending' ? 'primary' : 'default'"
                  size="small"
                  style="padding: 2px 6px; font-size: 10px; min-height: 20px"
                  @click="sortDesc"
                  title="最新优先"
                >▼</el-button>
              </el-button-group>
            </div>
          </template>
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="resolvedAt" label="处理时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.resolvedAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="resolvedBy" label="处理人" width="140">
          <template #default="{ row }">
            <span v-if="!row._editingResolvedBy" style="display: flex; align-items: center; gap: 4px">
              {{ row.resolvedBy || '-' }}
              <el-button
                v-if="authStore.isAdmin || authStore.isOperator"
                link
                size="small"
                @click="startEditResolvedBy(row)"
              ><el-icon><Edit /></el-icon></el-button>
            </span>
            <span v-else style="display: flex; gap: 4px">
              <el-input v-model="row._resolvedByDraft" size="small" style="width: 80px" @keyup.enter="saveResolvedBy(row)" />
              <el-button link type="primary" size="small" @click="saveResolvedBy(row)">✓</el-button>
              <el-button link size="small" @click="row._editingResolvedBy = false">✕</el-button>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' && (authStore.isAdmin || authStore.isOperator)"
              type="primary"
              link
              @click="openResolveDialog(row)"
            >{{ isAutoMode ? '处理' : '分配处理人' }}</el-button>
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
        @current-change="(page) => { currentPage = page; loadAlarms(false) }"
      />
    </el-card>

    <!-- 处理告警对话框 -->
    <el-dialog v-model="resolveDialog" title="处理告警" width="480px">
      <el-form :model="resolveForm" label-width="80px">
        <el-form-item label="告警内容">
          <el-input :model-value="resolvingAlarm?.content" disabled type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="处理人" required>
          <!-- 手动模式且有处理人时显示下拉选择器；否则显示文本输入 -->
          <el-select
            v-if="handlers.length > 0"
            v-model="resolveForm.handlerId"
            placeholder="请选择处理人"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="h in handlers"
              :key="h.id"
              :label="`${h.handlerName} (${h.isOccupied === 0 ? '空闲' : '占用'})`"
              :value="h.id"
            />
          </el-select>
          <el-input
            v-else
            v-model="resolveForm.resolvedBy"
            placeholder="请输入处理人姓名"
          />
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="resolveForm.notes" placeholder="可选：处理过程描述" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="handleResolve">
          确认{{ handlers.length > 0 ? '分配' : '处理' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Check } from '@element-plus/icons-vue'
import { getAlarms, resolveAlarm, batchResolve, getPendingCount, updateResolvedBy, batchUpdateResolvedBy, getVoltageConfig, setVoltageConfig, getTemperatureConfig, setTemperatureConfig, getPowerConfig, setPowerConfig } from '../api/alarm'
import { getHandlers, createHandler, updateHandler, deleteHandler, releaseHandler, getAssignmentMode, setAssignmentMode, assignHandler, batchAutoAssign } from '../api/handler'
import { formatTime, debounce, resetPage } from '../utils/common'
import { useAuthStore } from '../stores/authStore'

const router = useRouter()
const authStore = useAuthStore()

// 数据
const alarms = ref([])
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = 15
const pendingCount = ref(0)
const selectedIds = ref([])

// 排序
const sortProp = ref('createdAt')
const sortOrder = ref('descending')

// 筛选
const filterStatus = ref('')
const filterSeverity = ref('')
const filterType = ref('')
const filterDeviceId = ref('')

// 处理人管理
const showHandlerPanel = ref(false)
const handlers = ref([])
const handlersLoading = ref(false)
const isAutoMode = ref(false)

// 电压配置（默认展开）
const showVoltageConfig = ref(true)
const voltageMin = ref(210)
const voltageMax = ref(240)
const voltageDialog = ref(false)
const voltageForm = ref({ min: 210, max: 240 })

// 温度配置
const showTempConfig = ref(true)
const tempMax = ref(45)
const tempDialog = ref(false)
const tempForm = ref({ max: 45 })

// 功率配置
const showPowerConfig = ref(true)
const powerMax = ref(100)
const powerDialog = ref(false)
const powerForm = ref({ max: 100 })

// 处理对话框
const resolveDialog = ref(false)
const resolving = ref(false)
const resolvingAlarm = ref(null)
const resolveForm = ref({ handlerId: null, resolvedBy: 'admin', notes: '' })

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
  selectedIds.value = rows.map(r => r.id)
}

function sortAsc() {
  sortOrder.value = 'ascending'
  currentPage.value = 1
  loadAlarms(false)
}

function sortDesc() {
  sortOrder.value = 'descending'
  currentPage.value = 1
  loadAlarms(false)
}

async function loadAlarms(resetPageNum = true) {
  loading.value = true
  if (resetPageNum) resetPage(currentPage)
  try {
    const params = { page: currentPage.value - 1, size: pageSize }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterSeverity.value) params.severity = filterSeverity.value
    if (filterType.value) params.type = filterType.value
    if (filterDeviceId.value) params.deviceId = filterDeviceId.value
    params.sort = sortProp.value
    params.order = sortOrder.value

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

// ========== 处理人管理 ==========

async function loadHandlers() {
  handlersLoading.value = true
  try {
    const res = await getHandlers()
    handlers.value = (res?.data || res) ?? []
  } catch {
    handlers.value = []
  } finally {
    handlersLoading.value = false
  }
}

async function loadAssignmentMode() {
  try {
    const res = await getAssignmentMode()
    const mode = (res?.data || res)?.mode || 'MANUAL'
    isAutoMode.value = (mode === 'AUTO')
  } catch {
    isAutoMode.value = false
  }
}

// ========== 电压配置 ==========

async function loadVoltageConfig() {
  try {
    const res = await getVoltageConfig()
    const data = res?.data || res
    voltageMin.value = data.min ?? 210
    voltageMax.value = data.max ?? 240
  } catch {
    voltageMin.value = 210
    voltageMax.value = 240
  }
}

function openVoltageConfigDialog() {
  voltageForm.value = { min: voltageMin.value, max: voltageMax.value }
  voltageDialog.value = true
}

async function saveVoltageConfig() {
  try {
    await setVoltageConfig({ min: voltageForm.value.min, max: voltageForm.value.max })
    voltageMin.value = voltageForm.value.min
    voltageMax.value = voltageForm.value.max
    voltageDialog.value = false
    ElMessage.success('电压区间已更新')
  } catch {
    // 错误已拦截
  }
}

// ========== 温度配置 ==========

async function loadTemperatureConfig() {
  try {
    const res = await getTemperatureConfig()
    const data = res?.data || res
    tempMax.value = data.max ?? 45
  } catch {
    tempMax.value = 45
  }
}

function openTempConfigDialog() {
  tempForm.value = { max: tempMax.value }
  tempDialog.value = true
}

async function saveTemperatureConfig() {
  try {
    await setTemperatureConfig({ max: tempForm.value.max })
    tempMax.value = tempForm.value.max
    tempDialog.value = false
    ElMessage.success('温度上限已更新')
  } catch {
    // 错误已拦截
  }
}

// ========== 功率配置 ==========

async function loadPowerConfig() {
  try {
    const res = await getPowerConfig()
    const data = res?.data || res
    powerMax.value = data.max ?? 100
  } catch {
    powerMax.value = 100
  }
}

function openPowerConfigDialog() {
  powerForm.value = { max: powerMax.value }
  powerDialog.value = true
}

async function savePowerConfig() {
  try {
    await setPowerConfig({ max: powerForm.value.max })
    powerMax.value = powerForm.value.max
    powerDialog.value = false
    ElMessage.success('功率上限已更新')
  } catch {
    // 错误已拦截
  }
}

// ========== 分配模式 ==========

async function handleModeChange(val) {
  try {
    await setAssignmentMode({ mode: val ? 'AUTO' : 'MANUAL' })
    ElMessage.success(`已切换为${val ? '自动' : '手动'}分配模式`)
    if (val) {
      // 切换到自动模式后，立即对全部待处理告警执行自动分配
      const res = await batchAutoAssign()
      const result = res?.data || res
      ElMessage.success(`自动分配完成: 成功 ${result.success} 条, 失败 ${result.fail} 条`)
      loadAlarms()
      loadPendingCount()
      loadHandlers()
    }
  } catch {
    isAutoMode.value = !val
  }
}

async function openAddHandlerDialog() {
  try {
    const { value: name } = await ElMessageBox.prompt('请输入处理人名称', '添加处理人', {
      confirmButtonText: '确定', cancelButtonText: '取消'
    })
    if (!name?.trim()) return

    const { value: priStr } = await ElMessageBox.prompt(
      '请设置优先级（数字越小优先级越高，默认0）',
      '设置优先级',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputValue: '0' }
    )
    const priority = parseInt(priStr) || 0
    await createHandler({ handlerName: name.trim(), priority })
    ElMessage.success('处理人已添加')
    loadHandlers()
  } catch {
    // 取消或错误
  }
}

function startEditPriority(row) {
  row._priorityDraft = row.priority
  row._editingPriority = true
}

async function savePriority(row) {
  try {
    await updateHandler(row.id, { priority: row._priorityDraft })
    row.priority = row._priorityDraft
    row._editingPriority = false
    ElMessage.success('优先级已更新')
  } catch {
    // 错误已拦截
  }
}

async function handleReleaseHandler(row) {
  try {
    await ElMessageBox.confirm(`确定释放处理人 "${row.handlerName}" 吗？`, '释放确认', {
      confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
    })
    await releaseHandler(row.id)
    row.isOccupied = 0
    ElMessage.success('处理人已释放')
    loadHandlers()
  } catch {
    // 取消或错误
  }
}

async function handleDeleteHandler(row) {
  try {
    await ElMessageBox.confirm(`确定删除处理人 "${row.handlerName}" 吗？`, '删除确认', {
      confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
    })
    await deleteHandler(row.id)
    ElMessage.success('处理人已删除')
    loadHandlers()
  } catch {
    // 取消或错误
  }
}

// ========== 告警处理 ==========

function openResolveDialog(row) {
  resolvingAlarm.value = row
  resolveForm.value = { handlerId: null, resolvedBy: 'admin', notes: '' }
  resolveDialog.value = true
}

async function handleResolve() {
  // 有处理人列表且选中了处理人 → 走分配接口
  if (handlers.value.length > 0 && resolveForm.value.handlerId) {
    resolving.value = true
    try {
      await assignHandler(resolvingAlarm.value.id, resolveForm.value.handlerId)
      ElMessage.success('处理人已分配，告警已解决')
      resolveDialog.value = false
      loadAlarms()
      loadPendingCount()
      loadHandlers()
    } catch {
      // 错误已拦截
    } finally {
      resolving.value = false
    }
    return
  }

  // 无处理人列表或未选中 → 走传统自由文本处理
  if (!resolveForm.value.resolvedBy?.trim()) {
    ElMessage.warning('请选择或输入处理人')
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

async function handleBatchUpdateResolvedBy() {
  try {
    const { value } = await ElMessageBox.prompt(
      `请输入新的处理人名称（选中 ${selectedIds.value.length} 条）`,
      '批量修改处理人',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputValue: 'admin' }
    )
    if (!value?.trim()) {
      ElMessage.warning('处理人名称不能为空')
      return
    }
    const res = await batchUpdateResolvedBy({ ids: selectedIds.value, resolvedBy: value.trim() })
    const result = res?.data || res
    ElMessage.success(`成功更新 ${result.success} 条，失败 ${result.fail} 条`)
    selectedIds.value = []
    loadAlarms()
  } catch {
    // 用户取消或错误已拦截
  }
}

function goDevice(deviceId) {
  router.push(`/devices/${deviceId}`)
}

// --- 行内编辑处理人 ---
function startEditResolvedBy(row) {
  row._resolvedByDraft = row.resolvedBy || ''
  row._editingResolvedBy = true
}

async function saveResolvedBy(row) {
  try {
    await updateResolvedBy(row.id, { resolvedBy: row._resolvedByDraft || 'admin' })
    row.resolvedBy = row._resolvedByDraft || 'admin'
    row._editingResolvedBy = false
    ElMessage.success('处理人已更新')
  } catch {
    // 错误已拦截
  }
}

onMounted(() => {
  loadAlarms()
  loadPendingCount()
  loadHandlers()
  loadAssignmentMode()
  loadVoltageConfig()
  loadTemperatureConfig()
  loadPowerConfig()
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
