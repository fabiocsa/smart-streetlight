<template>
  <div :class="['message-item', role === 'user' ? 'msg-user' : 'msg-assistant']">
    <el-avatar :size="32" :icon="role === 'user' ? UserFilled : Service"
      :style="{ background: role === 'user' ? 'var(--blue)' : 'var(--teal)' }" />
    <div class="msg-body">
      <div class="msg-role">{{ role === 'user' ? '我' : '智能助手' }}</div>
      <div class="msg-content">{{ content }}</div>
    </div>
  </div>
</template>

<script setup>
import { UserFilled, Service } from '@element-plus/icons-vue'
defineProps({ role: { type: String, required: true }, content: { type: String, required: true } })
</script>

<style scoped>
.message-item { display: flex; gap: 10px; margin-bottom: 18px; }
.msg-user { flex-direction: row-reverse; }
.msg-user .msg-body { align-items: flex-end; }
.msg-user .msg-content { background: var(--blue); color: #fff; border-radius: 12px 4px 12px 12px; }
.msg-body { display: flex; flex-direction: column; gap: 4px; max-width: 68%; }
.msg-role { font-size: 12px; color: var(--text-muted); padding: 0 4px; }
.msg-content {
  padding: 10px 14px; background: var(--el-fill-color-light); border-radius: 4px 12px 12px 12px;
  font-size: 14px; line-height: 1.6; color: var(--text-primary); white-space: pre-wrap; word-break: break-word;
}
</style>
