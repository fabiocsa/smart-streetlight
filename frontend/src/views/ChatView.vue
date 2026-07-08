<template>
  <div class="chat-container">
    <div class="chat-header">
      <h2>智能问答</h2>
      <el-button text type="danger" @click="clearHistory">清空对话</el-button>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="chat-placeholder">
        <el-icon :size="48" color="#c0c4cc"><ChatDotRound /></el-icon>
        <p>请输入问题，智能助手将为您解答</p>
      </div>

      <div
        v-for="(msg, idx) in messages"
        :key="idx"
        :class="['message-row', msg.role === 'user' ? 'message-user' : 'message-assistant']"
      >
        <div class="message-avatar">
          <el-avatar
            :size="36"
            :icon="msg.role === 'user' ? UserFilled : Service"
            :style="{ background: msg.role === 'user' ? '#409EFF' : '#67C23A' }"
          />
        </div>
        <div class="message-bubble">
          <div class="message-role">{{ msg.role === 'user' ? '我' : '智能助手' }}</div>
          <div class="message-text">{{ msg.content }}</div>
        </div>
      </div>

      <div v-if="loading" class="message-row message-assistant">
        <div class="message-avatar">
          <el-avatar :size="36" :icon="Service" style="background: #67C23A" />
        </div>
        <div class="message-bubble">
          <div class="message-role">智能助手</div>
          <div class="message-text typing-indicator">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="inputText"
        placeholder="输入您的问题..."
        :disabled="loading"
        clearable
        @keyup.enter="handleSend"
        @clear="inputText = ''"
      >
        <template #append>
          <el-button
            type="primary"
            :disabled="!inputText.trim() || loading"
            :loading="loading"
            @click="handleSend"
          >
            <span v-if="!loading">发送</span>
          </el-button>
        </template>
      </el-input>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch } from 'vue'
import { ChatDotRound, UserFilled, Service } from '@element-plus/icons-vue'
import { sendMessage } from '../api/chat'
import { ElMessage } from 'element-plus'

const inputText = ref('')
const loading = ref(false)
const messages = ref([])
const messagesRef = ref(null)

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

watch(messages, () => scrollToBottom(), { deep: true })

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true

  try {
    const data = await sendMessage(text)
    messages.value.push({ role: 'assistant', content: data.answer })
  } catch {
    messages.value.push({ role: 'assistant', content: '抱歉，问答服务暂不可用，请稍后再试。' })
  } finally {
    loading.value = false
  }
}

function clearHistory() {
  messages.value = []
}
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
  max-width: 900px;
  margin: 0 auto;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0 16px;
  border-bottom: 1px solid #e4e7ed;
  margin-bottom: 16px;
  flex-shrink: 0;
}

.chat-header h2 {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 0 4px 16px;
}

.chat-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
  gap: 12px;
  font-size: 14px;
}

.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.message-user {
  flex-direction: row-reverse;
}

.message-user .message-bubble {
  align-items: flex-end;
}

.message-user .message-text {
  background: #409EFF;
  color: #fff;
  border-radius: 12px 4px 12px 12px;
}

.message-avatar {
  flex-shrink: 0;
  padding-top: 2px;
}

.message-bubble {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 70%;
}

.message-role {
  font-size: 12px;
  color: #909399;
  padding: 0 4px;
}

.message-text {
  padding: 10px 14px;
  background: #f0f2f5;
  border-radius: 4px 12px 12px 12px;
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-input {
  flex-shrink: 0;
  padding: 16px 0 8px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

/* 正在输入动画 */
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 14px 18px;
  min-width: 56px;
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
