<template>
  <el-dialog
    :model-value="visible"
    :title="isEdit ? '编辑传感器' : '添加传感器'"
    width="540px"
    @update:model-value="$emit('update:visible', $event)"
    @close="resetForm"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
      @submit.prevent
    >
      <el-form-item label="传感器类型" prop="sensorType">
        <el-select v-model="form.sensorType" placeholder="请选择传感器类型" style="width: 100%">
          <el-option label="光照" value="light" />
          <el-option label="温度" value="temperature" />
          <el-option label="湿度" value="humidity" />
          <el-option label="功率" value="power" />
        </el-select>
      </el-form-item>
      <el-form-item label="显示名称" prop="displayName">
        <el-input v-model="form.displayName" placeholder="如 光照传感器A" />
      </el-form-item>
      <el-form-item label="数据主题" prop="dataTopic">
        <el-input v-model="form.dataTopic" placeholder="如 streetlight/SL-001/sensor/data" />
      </el-form-item>
      <el-form-item label="上报频率（秒）" prop="reportFrequency">
        <el-input-number v-model="form.reportFrequency" :min="1" :max="3600" style="width: 100%" />
      </el-form-item>
      <el-form-item label="是否启用" prop="enabled">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="配置JSON" prop="configJson">
        <el-input
          v-model="form.configJson"
          type="textarea"
          :rows="3"
          placeholder='如 {"min": 0, "max": 800}'
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>
      <el-button type="primary" @click="submit" :loading="submitting">
        {{ isEdit ? '保存' : '添加' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useSensorStore } from '../store/sensor'

const props = defineProps({
  visible: Boolean,
  deviceId: { type: String, required: true },
  editData: Object
})

const emit = defineEmits(['update:visible', 'saved'])
const sensorStore = useSensorStore()

const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)

function defaultForm() {
  return {
    sensorType: 'light',
    displayName: '',
    dataTopic: '',
    reportFrequency: 5,
    enabled: true,
    configJson: ''
  }
}

const form = reactive(defaultForm())

const rules = {
  sensorType: [{ required: true, message: '请选择传感器类型', trigger: 'change' }],
  dataTopic: [{ required: true, message: '请输入数据主题', trigger: 'blur' }],
  reportFrequency: [{ required: true, message: '请输入上报频率', trigger: 'blur' }]
}

watch(() => props.editData, (val) => {
  if (val) {
    isEdit.value = true
    form.sensorType = val.sensorType || 'light'
    form.displayName = val.displayName || ''
    form.dataTopic = val.dataTopic || ''
    form.reportFrequency = val.reportFrequency ?? 5
    form.enabled = val.enabled ?? true
    form.configJson = val.configJson || ''
  }
}, { immediate: true })

watch(() => props.visible, (val) => {
  if (!val) return
  if (!props.editData) {
    isEdit.value = false
    Object.assign(form, defaultForm())
  }
})

function resetForm() {
  formRef.value?.resetFields()
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      sensorType: form.sensorType,
      displayName: form.displayName,
      dataTopic: form.dataTopic,
      reportFrequency: form.reportFrequency,
      enabled: form.enabled,
      configJson: form.configJson || null
    }

    if (isEdit.value) {
      await sensorStore.update(props.deviceId, props.editData.id, payload)
      ElMessage.success('传感器配置已更新')
    } else {
      await sensorStore.create(props.deviceId, payload)
      ElMessage.success('传感器已绑定')
    }
    emit('saved')
    emit('update:visible', false)
  } finally {
    submitting.value = false
  }
}
</script>
