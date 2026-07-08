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
        <el-popconfirm
          title="确定同步传感器到模拟器吗？"
          confirm-button-text="确定"
          @confirm="handleSync"
        >
          <template #reference>
            <el-button type="success" :loading="syncing">
              <el-icon><Refresh /></el-icon> 同步传感器到模拟器
            </el-button>
          </template>
        </el-popconfirm>
        <el-button type="primary" @click="openSensorDialog()">
          <el-icon><Plus /></el-icon> 添加传感器
        </el-button>
      </div>
    </div>

    <!-- 设备信息 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header><strong>设备基本信息</strong></template>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        style="max-width: 600px"
      >
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="设备ID">
              <el-input :model-value="form.deviceId" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备名称" prop="name">
              <el-input v-model="form.name" placeholder="请输入设备名称" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="安装位置" prop="location">
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

    <!-- 设备控制面板 -->
    <ControlPanel :device="form" @updated="loadDevice" />

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
    @saved="handleSensorSaved"
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
import ControlPanel from '../components/ControlPanel.vue'

const route = useRoute()
const router = useRouter()
const deviceStore = useDeviceStore()
const sensorStore = useSensorStore()

const formRef = ref(null)
const form = ref({})
const sensors = ref([])
const saving = ref(false)
const syncing = ref(false)
const sensorDialogVisible = ref(false)
const editingSensor = ref(null)

const rules = {
  name: [
    { required: true, message: '请输入设备名称', trigger: 'blur' },
    { min: 1, max: 100, message: '长度在 1 到 100 个字符', trigger: 'blur' }
  ],
  location: [
    { required: true, message: '请输入安装位置', trigger: 'blur' },
    { max: 200, message: '长度不超过 200 个字符', trigger: 'blur' }
  ]
}

async function loadDevice() {
  const id = route.params.id
  const device = await deviceStore.fetchOne(id)
  form.value = { ...device }
}

async function loadSensors() {
  sensors.value = await sensorStore.fetchByDevice(form.value.deviceId)
}

async function handleUpdateDevice() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await deviceStore.update(form.value.id, {
      name: form.value.name,
      location: form.value.location
    })
    ElMessage.success('设备信息已更新')
  } catch {
    // 错误已在拦截器统一提示
  } finally {
    saving.value = false
  }
}

async function handleSync() {
  syncing.value = true
  try {
    const res = await sensorStore.syncToMock(form.value.deviceId)
    ElMessage.success(`已同步 ${res?.syncedCount ?? 0} 个传感器到模拟器`)
  } catch {
    // 错误已在拦截器统一提示
  } finally {
    syncing.value = false
  }
}

function openSensorDialog(sensor) {
  editingSensor.value = sensor || null
  sensorDialogVisible.value = true
}

async function handleDeleteSensor(id) {
  try {
    await sensorStore.remove(form.value.deviceId, id)
    ElMessage.success('传感器已解绑')
    // store 已局部更新，同步本地数据
    sensors.value = sensors.value.filter(s => s.id !== id)
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleUpdateFrequency(id, frequency) {
  try {
    await sensorStore.updateFreq(form.value.deviceId, id, { reportFrequency: frequency })
    ElMessage.success('上报频率已更新')
    // store 已局部更新，同步本地数据
    const sensor = sensors.value.find(s => s.id === id)
    if (sensor) sensor.reportFrequency = frequency
  } catch {
    // 错误已在拦截器统一提示
  }
}

function handleSensorSaved() {
  sensorDialogVisible.value = false
  loadSensors() // 新增传感器需要刷新列表
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
