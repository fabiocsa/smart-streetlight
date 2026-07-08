<template>
  <div class="chat-layout">
    <!-- 左侧历史会话栏 -->
    <div class="chat-sidebar">
      <Sidebar
        :sessions="store.sessions"
        :currentId="store.currentSessionId"
        @new="handleNew"
        @select="handleSelect"
        @delete="handleDelete"
      />
    </div>

    <!-- 右侧主区域 -->
    <div class="chat-main">
      <!-- 无会话时显示占位 -->
      <div v-if="!store.currentSessionId" class="chat-placeholder">
        <el-icon :size="56" color="#c0c4cc"><ChatDotRound /></el-icon>
        <p>选择或新建一个对话</p>
        <el-button type="primary" @click="handleNew">新建对话</el-button>
      </div>

      <!-- 有会话时显示聊天区 -->
      <template v-else>
        <div class="chat-topbar">
          <span class="session-label">{{ store.currentSession?.title || '对话' }}</span>
        </div>
        <MessageList
          :messages="store.currentMessages"
          :loading="store.isLoading"
        />
        <MessageInput
          :disabled="!store.currentSessionId"
          :loading="store.isLoading"
          @send="handleSend"
        />
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useChatStore } from '../stores/chatStore'
import Sidebar from '../components/chat/Sidebar.vue'
import MessageList from '../components/chat/MessageList.vue'
import MessageInput from '../components/chat/MessageInput.vue'

const store = useChatStore()

onMounted(async () => {
  await store.loadSessions()
  if (store.sessions.length > 0) {
    await store.selectSession(store.sessions[0].id)
  }
})

async function handleNew() {
  await store.newSession()
}

async function handleSelect(id) {
  await store.selectSession(id)
}

async function handleDelete(id) {
  await store.removeSession(id)
}

async function handleSend(text) {
  if (!store.currentSessionId) {
    await store.newSession()
  }
  await store.send(text)
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: calc(100vh - 120px);
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.chat-sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid #e4e7ed;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 12px;
  color: #909399;
  font-size: 14px;
}

.chat-topbar {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}
.session-label {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
</style>
