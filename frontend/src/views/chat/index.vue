<template>
  <div class="chat-view">
    <el-card shadow="hover" class="chat-container">
      <div class="chat-layout">
        <!-- Main Chat Panel -->
        <div class="chat-main">
          <!-- Header -->
          <div class="chat-header">
            <div class="chat-header-info">
              <el-icon :size="20" color="#409EFF"><ChatDotSquare /></el-icon>
              <span>维护智能问答</span>
            </div>
            <el-button
              text
              size="small"
              :icon="Delete"
              @click="clearMessages"
              :disabled="messages.length <= 1"
            >
              清空对话
            </el-button>
          </div>

          <!-- Messages -->
          <div class="messages-container" ref="messagesRef">
            <div
              v-for="(msg, i) in messages"
              :key="i"
              class="message-row"
              :class="msg.role === 'user' ? 'user-row' : 'assistant-row'"
            >
              <!-- AI avatar -->
              <div v-if="msg.role === 'assistant'" class="avatar ai-avatar">
                <el-icon :size="18" color="#409EFF"><ChatDotRound /></el-icon>
              </div>

              <div class="message-bubble" :class="msg.role">
                <!-- Welcome message with quick questions -->
                <template v-if="i === 0 && msg.role === 'assistant' && msg.isWelcome">
                  <div class="welcome-text">{{ msg.content }}</div>
                  <div class="quick-questions">
                    <el-button
                      v-for="(q, qi) in quickQuestions"
                      :key="qi"
                      size="small"
                      round
                      @click="sendQuickQuestion(q)"
                    >
                      {{ q }}
                    </el-button>
                  </div>
                </template>

                <!-- Normal message -->
                <template v-else>
                  <div class="message-content" v-html="renderMarkdown(msg.content)" />
                  <!-- Copy button -->
                  <div v-if="msg.role === 'assistant' && msg.content" class="message-actions">
                    <el-tooltip content="复制" placement="top">
                      <el-icon
                        :size="14"
                        class="action-icon"
                        @click="copyText(msg.content)"
                      >
                        <CopyDocument />
                      </el-icon>
                    </el-tooltip>
                    <el-tooltip v-if="msg.error" content="重试" placement="top">
                      <el-icon
                        :size="14"
                        class="action-icon"
                        @click="retryMessage(i)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                  </div>
                </template>

                <!-- Typing indicator -->
                <div v-if="msg.typing" class="typing-indicator">
                  <span class="typing-dot"></span>
                  <span class="typing-dot"></span>
                  <span class="typing-dot"></span>
                </div>

                <!-- Error message -->
                <div v-if="msg.error" class="error-message">
                  <el-icon :size="14" color="#F56C6C"><WarningFilled /></el-icon>
                  <span>{{ msg.error }}</span>
                </div>
              </div>

              <!-- User avatar -->
              <div v-if="msg.role === 'user'" class="avatar user-avatar">
                <el-icon :size="18" color="#fff"><User /></el-icon>
              </div>
            </div>
          </div>

          <!-- Input Area -->
          <div class="chat-input-area">
            <el-input
              v-model="inputText"
              type="textarea"
              :rows="2"
              :disabled="!wsStore.connected"
              placeholder="输入您的问题…"
              @keydown.enter.exact.prevent="sendMessage"
              @keydown.shift.enter.exact="() => {}"
              maxlength="500"
              show-word-limit
            >
              <template #prefix>
                <el-icon v-if="!wsStore.connected" color="#F56C6C"><WarningFilled /></el-icon>
              </template>
            </el-input>
            <div class="input-actions">
              <span v-if="!wsStore.connected" class="offline-tip">网络连接已断开</span>
              <el-button
                type="primary"
                :icon="Promotion"
                :disabled="!inputText.trim() || sending || !wsStore.connected"
                :loading="sending"
                @click="sendMessage"
                circle
              />
            </div>
          </div>
        </div>

        <!-- Quick Questions Sidebar -->
        <div class="chat-sidebar" v-if="showSidebar">
          <div class="sidebar-title">快捷问题</div>
          <div class="sidebar-list">
            <div
              v-for="(q, i) in quickQuestions"
              :key="i"
              class="sidebar-item"
              @click="sendQuickQuestion(q)"
            >
              <el-icon :size="14" color="#409EFF"><ChatLineSquare /></el-icon>
              <span>{{ q }}</span>
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotSquare, ChatDotRound, User, Delete,
  CopyDocument, Refresh, Promotion,
  WarningFilled, ChatLineSquare
} from '@element-plus/icons-vue'
import { useWebSocketStore } from '@/stores/websocket'

const wsStore = useWebSocketStore()

const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const showSidebar = ref(true)
const messagesRef = ref(null)

let abortController = null

const quickQuestions = [
  '如何判断传感器是否故障？',
  '路灯离线了怎么办？',
  '如何设置光照阈值？',
  '设备控制没有响应怎么办？'
]

onMounted(() => {
  // Welcome message
  messages.value = [{
    role: 'assistant',
    content: '您好！我是智慧路灯系统的维护助手，可以帮您解答设备维护、故障排查等相关问题。请问有什么可以帮您的？',
    isWelcome: true
  }]
})

function addMessage(msg) {
  messages.value.push(msg)
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    const container = messagesRef.value
    if (container) {
      container.scrollTop = container.scrollHeight
    }
  })
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || sending.value || !wsStore.connected) return

  // Add user message
  addMessage({ role: 'user', content: text })
  inputText.value = ''

  // Add placeholder AI message
  const aiMsg = { role: 'assistant', content: '', typing: true }
  addMessage(aiMsg)
  sending.value = true

  // Cancel previous request
  if (abortController) {
    abortController.abort()
  }
  abortController = new AbortController()

  try {
    // Call the AI API (placeholder endpoint - adjust when backend is ready)
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: text }),
      signal: abortController.signal
    })

    if (!response.ok) throw new Error('请求失败')

    const data = await response.json()
    const answer = data.answer || data.content || data.message || '暂未获取到回复，请稍后再试'

    // Simulate typing effect for realistic feel
    aiMsg.typing = false
    aiMsg.content = answer

    // Add sources if available
    if (data.sources && data.sources.length > 0) {
      aiMsg.content += '\n\n**参考来源：**\n' + data.sources.map(s => `- ${s}`).join('\n')
    }
    if (data.suggestions && data.suggestions.length > 0) {
      aiMsg.content += '\n\n**建议操作：**\n' + data.suggestions.map(s => `- ${s}`).join('\n')
    }

    messages.value = [...messages.value]
    scrollToBottom()
  } catch (e) {
    if (e.name === 'AbortError') return
    aiMsg.typing = false
    aiMsg.error = '回复失败，请重试'
    messages.value = [...messages.value]
  } finally {
    sending.value = false
    abortController = null
  }
}

function sendQuickQuestion(question) {
  inputText.value = question
  sendMessage()
  showSidebar.value = false
  setTimeout(() => { showSidebar.value = true }, 1000)
}

function clearMessages() {
  messages.value = [{
    role: 'assistant',
    content: '您好！我是智慧路灯系统的维护助手，可以帮您解答设备维护、故障排查等相关问题。请问有什么可以帮您的？',
    isWelcome: true
  }]
}

function retryMessage(index) {
  // Find the last user message before this AI message
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      inputText.value = messages.value[i].content
      // Remove the failed AI message and its user message
      messages.value.splice(index, 1)
      messages.value.splice(i, 1)
      sendMessage()
      return
    }
  }
}

function copyText(text) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制')
  }).catch(() => {
    // Fallback
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    ElMessage.success('已复制')
  })
}

// Simple markdown renderer (basic)
function renderMarkdown(text) {
  if (!text) return ''
  let html = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    // Bold
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // Italic
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // Inline code
    .replace(/`(.+?)`/g, '<code style="background:#f0f0f0;padding:1px 4px;border-radius:3px;font-size:90%">$1</code>')
    // Line breaks
    .replace(/\n/g, '<br>')
    // Lists
    .replace(/^- (.+?)(<br>|$)/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>')
  return html
}
</script>

<style scoped>
.chat-view {
  max-width: 1200px;
  margin: 0 auto;
  height: calc(100vh - 120px);
}

.chat-container {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-layout {
  display: flex;
  height: 100%;
  gap: 0;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
}

.chat-header-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fafafa;
}

.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: flex-start;
}

.user-row {
  justify-content: flex-end;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.ai-avatar {
  background: #ecf5ff;
}

.user-avatar {
  background: #409EFF;
}

.message-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.message-bubble.user {
  background: #409EFF;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-bubble.assistant {
  background: #fff;
  color: #303133;
  border: 1px solid #e8e8e8;
  border-bottom-left-radius: 4px;
}

.welcome-text {
  margin-bottom: 12px;
}

.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.message-content {
  white-space: pre-wrap;
}

.message-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity 0.2s;
}

.message-bubble.assistant:hover .message-actions {
  opacity: 1;
}

.action-icon {
  cursor: pointer;
  color: #c0c4cc;
}

.action-icon:hover {
  color: #409EFF;
}

/* Typing animation */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #909399;
  animation: typingBounce 1.4s infinite ease-in-out;
}

.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typingBounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.error-message {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: #F56C6C;
}

/* Input area */
.chat-input-area {
  padding: 12px 16px;
  border-top: 1px solid #ebeef5;
  background: #fff;
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.offline-tip {
  font-size: 12px;
  color: #F56C6C;
}

/* Sidebar */
.chat-sidebar {
  width: 220px;
  border-left: 1px solid #ebeef5;
  padding: 16px;
  flex-shrink: 0;
}

.sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.sidebar-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sidebar-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  color: #606266;
  transition: all 0.2s;
}

.sidebar-item:hover {
  background: #ecf5ff;
  color: #409EFF;
}
</style>
