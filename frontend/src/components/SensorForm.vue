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
        <div class="form-hint" v-if="!isEdit">
          传感器由模拟器通过 MQTT 独立注册，需在设备详情页手动绑定到设备
        </div>
      </el-form-item>
      <el-form-item label="显示名称" prop="displayName">
        <el-input v-model="form.displayName" placeholder="如 光照传感器A" />
      </el-form-item>
      <el-form-item label="数据主题" prop="dataTopic">
        <div style="display: flex; gap: 6px; width: 100%">
          <el-input v-model="form.dataTopic" placeholder="如 streetlight/sensor/1/data" style="flex: 1" />
          <el-tooltip content="自动生成标准格式: streetlight/sensor/{ID}/data" placement="top">
            <el-button @click="autoGenDataTopic" :disabled="!form.sensorType">
              <el-icon><MagicStick /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <div class="form-hint">模拟器自动注册时由此字段匹配，格式: streetlight/sensor/{simSensorId}/data</div>
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
import { MagicStick } from '@element-plus/icons-vue'
import { useSensorStore } from '../store/sensor'

const props = defineProps({
  visible: Boolean,
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

// 自定义 JSON 校验
const validateJson = (_rule, value, callback) => {
  if (!value || value.trim() === '') {
    callback() // 允许为空
    return
  }
  try {
    JSON.parse(value)
    callback()
  } catch {
    callback(new Error('配置JSON格式不正确'))
  }
}

const rules = {
  sensorType: [{ required: true, message: '请选择传感器类型', trigger: 'change' }],
  dataTopic: [{ required: true, message: '请输入数据主题', trigger: 'blur' }],
  reportFrequency: [{ required: true, message: '请输入上报频率', trigger: 'blur' }],
  configJson: [{ validator: validateJson, trigger: 'blur' }]
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

function autoGenDataTopic() {
  const idSuffix = Math.floor(Math.random() * 90000) + 10000
  form.dataTopic = `streetlight/sensor/${idSuffix}/data`
}

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
      configJson: form.configJson?.trim() || null
    }

    if (isEdit.value) {
      await sensorStore.update(props.editData.id, payload)
      ElMessage.success('传感器配置已更新')
    } else {
      await sensorStore.create(payload)
      ElMessage.success('传感器已创建（未绑定状态）')
    }
    emit('saved')
    emit('update:visible', false)
  } catch {
    // 错误已在拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.form-hint {
  font-size: 12px; color: #909399; line-height: 1.5; margin-top: 4px;
}
</style>
