<template>
  <div>
    <div class="page-header">
      <h2>传感器管理</h2>
      <el-button type="primary" @click="refreshAll">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <!-- 筛选 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="16">
        <el-col :span="6">
          <el-select v-model="filterDeviceId" placeholder="按设备筛选" clearable>
            <el-option
              v-for="d in deviceStore.devices"
              :key="d.deviceId"
              :label="`${d.name} (${d.deviceId})`"
              :value="d.deviceId"
            />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterType" placeholder="传感器类型" clearable>
            <el-option label="光照" value="light" />
            <el-option label="温度" value="temperature" />
            <el-option label="湿度" value="humidity" />
            <el-option label="功率" value="power" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterEnabled" placeholder="启用状态" clearable>
            <el-option label="已启用" :value="true" />
            <el-option label="已停用" :value="false" />
          </el-select>
        </el-col>
      </el-row>
    </el-card>

    <!-- 传感器表格 -->
    <el-card shadow="never">
      <el-table :data="paginatedSensors" v-loading="sensorStore.loading" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="所属设备" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ row.deviceId }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="传感器名称" min-width="140" />
        <el-table-column prop="sensorType" label="类型" width="90">
          <template #default="{ row }">
            {{ typeLabel(row.sensorType) }}
          </template>
        </el-table-column>
        <el-table-column prop="dataTopic" label="数据主题" min-width="200" show-overflow-tooltip />
        <el-table-column label="上报频率" width="100">
          <template #default="{ row }">
            <el-input-number
              :model-value="row.reportFrequency"
              :min="1"
              :max="3600"
              size="small"
              style="width: 80px"
              @change="(v) => handleFrequencyChange(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              @change="(v) => handleToggleEnabled(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="配置" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.configJson || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            {{ row.updatedAt ? formatTime(row.updatedAt) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定解绑该传感器吗？" @confirm="handleDelete(row)">
              <template #reference>
                <el-button type="danger" link>解绑</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无传感器数据" />
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
      :device-id="editingSensor?.deviceId"
      :edit-data="editingSensor"
      @saved="refreshAll"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useDeviceStore } from '../store/device'
import { useSensorStore } from '../store/sensor'
import SensorForm from '../components/SensorForm.vue'

const deviceStore = useDeviceStore()
const sensorStore = useSensorStore()

const filterDeviceId = ref('')
const filterType = ref('')
const filterEnabled = ref('')
const dialogVisible = ref(false)
const editingSensor = ref(null)
const currentPage = ref(1)
const pageSize = 10

const filteredSensors = computed(() => {
  let list = sensorStore.allSensors
  if (filterDeviceId.value) list = list.filter(s => s.deviceId === filterDeviceId.value)
  if (filterType.value) list = list.filter(s => s.sensorType === filterType.value)
  if (filterEnabled.value !== '' && filterEnabled.value !== null) {
    list = list.filter(s => !!s.enabled === filterEnabled.value)
  }
  return list
})

const paginatedSensors = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredSensors.value.slice(start, start + pageSize)
})

function typeLabel(t) {
  const map = { light: '光照', temperature: '温度', humidity: '湿度', power: '功率' }
  return map[t] || t
}

function formatTime(t) {
  return new Date(t).toLocaleString('zh-CN')
}

async function refreshAll() {
  await sensorStore.fetchAll()
}

function openEditDialog(sensor) {
  editingSensor.value = { ...sensor }
  dialogVisible.value = true
}

async function handleDelete(row) {
  await sensorStore.remove(row.deviceId, row.id)
  ElMessage.success('传感器已解绑')
  refreshAll()
}

async function handleToggleEnabled(row, enabled) {
  await sensorStore.update(row.deviceId, row.id, { enabled })
  ElMessage.success(enabled ? '传感器已启用' : '传感器已停用')
  refreshAll()
}

async function handleFrequencyChange(row, val) {
  if (!val || val < 1) return
  await sensorStore.updateFreq(row.deviceId, row.id, { reportFrequency: val })
  ElMessage.success(`上报频率已更新为 ${val} 秒`)
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
</style>
