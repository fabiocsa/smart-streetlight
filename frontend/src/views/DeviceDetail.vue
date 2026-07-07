<template>
  <div>
    <div class="page-header">
      <div>
        <el-button type="default" @click="router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <h2 style="display: inline; margin-left: 8px">设备详情</h2>
      </div>
      <div>
        <el-button type="success" @click="handleSync" :loading="syncing">
          <el-icon><Refresh /></el-icon> 同步传感器到模拟器
        </el-button>
        <el-button type="primary" @click="openSensorDialog()">
          <el-icon><Plus /></el-icon> 添加传感器
        </el-button>
      </div>
    </div>

    <!-- 设备信息 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header><strong>设备基本信息</strong></template>
      <el-form :model="form" label-width="100px" style="max-width: 600px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="设备ID">
              <el-input :model-value="form.deviceId" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备名称">
              <el-input v-model="form.name" placeholder="请输入设备名称" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="安装位置">
              <el-input v-model="form.location" placeholder="请输入安装位置" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="当前状态">
              <el-tag :type="form.status === 'online' ? 'success' : 'danger'">
                {{ form.status === 'online' ? '在线' : '离线' }}
              </el-tag>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="灯光状态">
              <el-tag :type="form.lightStatus === 'on' ? 'warning' : 'info'">
                {{ form.lightStatus === 'on' ? '已开启' : '已关闭' }}
              </el-tag>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="控制模式">
              <el-tag :type="form.controlMode === 'auto' ? 'success' : ''">
                {{ form.controlMode === 'auto' ? '自动' : '手动' }}
              </el-tag>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" @click="handleUpdateDevice" :loading="saving">保存设备信息</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 传感器列表 -->
    <SensorTable
      :device-id="form.deviceId"
      :sensors="sensors"
      :loading="sensorStore.loading"
      @refresh="loadSensors"
      @edit="openSensorDialog"
      @delete="handleDeleteSensor"
      @update-frequency="handleUpdateFrequency"
    />
  </div>

  <!-- 传感器表单对话框 -->
  <SensorForm
    v-model:visible="sensorDialogVisible"
    :device-id="form.deviceId"
    :edit-data="editingSensor"
    @saved="loadSensors"
  />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useDeviceStore } from '../store/device'
import { useSensorStore } from '../store/sensor'
import SensorTable from '../components/SensorTable.vue'
import SensorForm from '../components/SensorForm.vue'

const route = useRoute()
const router = useRouter()
const deviceStore = useDeviceStore()
const sensorStore = useSensorStore()

const form = ref({})
const sensors = ref([])
const saving = ref(false)
const syncing = ref(false)
const sensorDialogVisible = ref(false)
const editingSensor = ref(null)

async function loadDevice() {
  const id = route.params.id
  const device = await deviceStore.fetchOne(id)
  form.value = { ...device }
}

async function loadSensors() {
  sensors.value = await sensorStore.fetchByDevice(form.value.deviceId)
}

async function handleUpdateDevice() {
  saving.value = true
  try {
    await deviceStore.update(form.value.id, {
      name: form.value.name,
      location: form.value.location
    })
    ElMessage.success('设备信息已更新')
  } finally {
    saving.value = false
  }
}

async function handleSync() {
  syncing.value = true
  try {
    const res = await sensorStore.syncToMock(form.value.deviceId)
    ElMessage.success(`已同步 ${res?.syncedCount ?? 0} 个传感器到模拟器`)
  } finally {
    syncing.value = false
  }
}

function openSensorDialog(sensor) {
  editingSensor.value = sensor || null
  sensorDialogVisible.value = true
}

async function handleDeleteSensor(id) {
  await sensorStore.remove(form.value.deviceId, id)
  ElMessage.success('传感器已解绑')
  loadSensors()
}

async function handleUpdateFrequency(id, frequency) {
  await sensorStore.updateFreq(form.value.deviceId, id, { reportFrequency: frequency })
  ElMessage.success('上报频率已更新')
  loadSensors()
}

onMounted(() => {
  loadDevice().then(loadSensors)
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
</style>
