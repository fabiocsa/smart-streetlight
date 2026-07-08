<template>
  <div class="msg-list" ref="listRef">
    <div v-if="messages.length === 0 && !loading" class="msg-empty">
      <el-icon :size="40" color="#c0c4cc"><ChatDotRound /></el-icon>
      <p>开始对话吧</p>
    </div>
    <MessageItem
      v-for="(m, i) in messages"
      :key="i"
      :role="m.role"
      :content="m.content"
    />
    <div v-if="loading" class="message-item msg-assistant">
      <el-avatar :size="32" :icon="Service" style="background: #67C23A; flex-shrink: 0" />
      <div class="msg-body">
        <div class="msg-role">智能助手</div>
        <div class="typing-dots">
          <span class="dot"></span><span class="dot"></span><span class="dot"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { ChatDotRound, Service } from '@element-plus/icons-vue'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

const listRef = ref(null)

watch(() => [props.messages.length, props.loading], () => {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}, { deep: true })
</script>

<style scoped>
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 4px;
}
.msg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
  font-size: 13px;
  gap: 8px;
}
.message-item {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.msg-assistant { flex-direction: row; }
.msg-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.msg-role {
  font-size: 12px;
  color: #909399;
  padding: 0 4px;
}
.typing-dots {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
  background: #f0f2f5;
  border-radius: 4px 12px 12px 12px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #909399;
  animation: blink 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: 0s; }
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
</style>
