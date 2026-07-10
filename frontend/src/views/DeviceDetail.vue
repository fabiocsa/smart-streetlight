<template>
  <div>
    <div class="page-header">
      <div>
        <el-button type="default" @click="router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <h2 style="display: inline; margin-left: 8px">{{ form.name || '设备详情' }}</h2>
      </div>
      <div>
        <el-tooltip content="设备绑定传感器：选择未绑定的传感器进行绑定" placement="top">
          <el-button v-if="authStore.isAdmin" type="primary" @click="openBindDialog()">
            <el-icon><Plus /></el-icon> 绑定传感器
          </el-button>
        </el-tooltip>
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
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="纬度">
              <el-input :model-value="form.latitude ?? '-'" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="经度">
              <el-input :model-value="form.longitude ?? '-'" disabled />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="authStore.isAdmin">
          <el-button type="primary" @click="handleUpdateDevice" :loading="saving">保存设备信息</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 设备控制面板 -->
    <ControlPanel :device="form" @updated="loadDevice" />

    <!-- 已绑定传感器列表 -->
    <SensorTable
      :device-id="form.id"
      :sensors="sensors"
      :loading="sensorStore.loading"
      @refresh="loadSensors"
      @unbind="handleUnbindSensor"
      @update-frequency="handleUpdateFrequency"
    />

    <!-- 绑定传感器对话框 -->
    <el-dialog v-model="bindDialogVisible" title="设备绑定传感器" width="550px">
      <p class="text-muted">选择未绑定的传感器绑定到此设备。</p>
      <el-table :data="unboundSensors" height="300" @selection-change="onBindSelectionChange">
        <el-table-column type="selection" width="50" />
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="displayName" label="名称" />
        <el-table-column prop="sensorType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.sensorType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dataTopic" label="MQTT 主题" min-width="180" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="bindDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBindSensors" :disabled="bindSelection.length === 0">
          绑定选中 ({{ bindSelection.length }})
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/authStore'
import { useDeviceStore } from '../store/device'
import { useSensorStore } from '../store/sensor'
import SensorTable from '../components/SensorTable.vue'
import ControlPanel from '../components/ControlPanel.vue'

const route = useRoute()
const router = useRouter()
const deviceStore = useDeviceStore()
const sensorStore = useSensorStore()
const authStore = useAuthStore()

const formRef = ref(null)
const form = ref({})
const sensors = ref([])
const saving = ref(false)
const bindDialogVisible = ref(false)
const unboundSensors = ref([])
const bindSelection = ref([])

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
  sensors.value = await sensorStore.fetchByDevice(form.value.id)
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

async function openBindDialog() {
  unboundSensors.value = await sensorStore.fetchUnbound()
  bindSelection.value = []
  bindDialogVisible.value = true
}

function onBindSelectionChange(selection) {
  bindSelection.value = selection
}

async function handleBindSensors() {
  for (const s of bindSelection.value) {
    try {
      await sensorStore.bindToDevice(form.value.id, s.id)
      ElMessage.success(`传感器 ${s.displayName || s.id} 绑定成功`)
    } catch {
      // 错误已在拦截器统一提示
    }
  }
  bindDialogVisible.value = false
  loadSensors()
}

async function handleUnbindSensor(id) {
  try {
    await sensorStore.unbindFromDevice(form.value.id, id)
    ElMessage.success('传感器已移除绑定（保留记录，可重新绑定）')
    sensors.value = sensors.value.filter(s => s.id !== id)
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleUpdateFrequency(id, frequency) {
  try {
    await sensorStore.updateFreq(id, { reportFrequency: frequency })
    ElMessage.success('上报频率已更新')
    const sensor = sensors.value.find(s => s.id === id)
    if (sensor) sensor.reportFrequency = frequency
  } catch {
    // 错误已在拦截器统一提示
  }
}

onMounted(() => {
  loadDevice().then(loadSensors)
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.text-muted { color: #888; font-size: 0.9rem; }
</style>
