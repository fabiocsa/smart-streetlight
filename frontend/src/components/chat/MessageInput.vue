<template>
  <div class="msg-input">
    <el-input
      v-model="text"
      placeholder="输入您的问题... (Enter 发送)"
      :disabled="disabled"
      clearable
      @keyup.enter="handleSend"
      @clear="text = ''"
    >
      <template #append>
        <el-button
          type="primary"
          :disabled="!text.trim() || disabled"
          :loading="loading"
          @click="handleSend"
        >
          <span v-if="!loading">发送</span>
        </el-button>
      </template>
    </el-input>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['send'])
const text = ref('')

function handleSend() {
  const val = text.value.trim()
  if (!val || props.disabled) return
  emit('send', val)
  text.value = ''
}
</script>

<style scoped>
.msg-input {
  padding: 12px 0 4px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
  flex-shrink: 0;
}
</style>
