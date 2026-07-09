<template>
  <el-dialog
    :model-value="visible"
    :title="isEdit ? '编辑设备' : '添加设备'"
    width="500px"
    @update:model-value="$emit('update:visible', $event)"
    @close="resetForm"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="100px"
      @submit.prevent
    >
      <el-form-item label="设备ID" prop="deviceId">
        <el-input v-model="form.deviceId" placeholder="如 SL-005" :disabled="isEdit" />
        <div class="form-hint" v-if="!isEdit">
          <i class="el-icon-info"></i> 设备通过 REST API 管理，创建后传感器可通过 MQTT 自动挂载到此设备
        </div>
      </el-form-item>
      <el-form-item label="设备名称" prop="name">
        <el-input v-model="form.name" placeholder="如 路灯A-05" />
      </el-form-item>
      <el-form-item label="安装位置" prop="location">
        <el-input v-model="form.location" placeholder="如 图书馆东侧" />
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
import { useDeviceStore } from '../store/device'

const props = defineProps({
  visible: Boolean,
  editData: Object
})

const emit = defineEmits(['update:visible', 'saved'])
const deviceStore = useDeviceStore()

const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)

const form = reactive({
  deviceId: '',
  name: '',
  location: ''
})

const rules = {
  deviceId: [{ required: true, message: '请输入设备ID', trigger: 'blur' }],
  name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }]
}

watch(() => props.editData, (val) => {
  if (val) {
    isEdit.value = true
    form.deviceId = val.deviceId || ''
    form.name = val.name || ''
    form.location = val.location || ''
  }
}, { immediate: true })

watch(() => props.visible, (val) => {
  if (!val) return
  if (!props.editData) {
    isEdit.value = false
    form.deviceId = ''
    form.name = ''
    form.location = ''
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
    if (isEdit.value) {
      await deviceStore.update(props.editData.id, {
        name: form.name,
        location: form.location
      })
      ElMessage.success('设备信息已更新')
    } else {
      await deviceStore.create({
        deviceId: form.deviceId,
        name: form.name,
        location: form.location
      })
      ElMessage.success('设备添加成功')
    }
    emit('saved')
    emit('update:visible', false)
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
