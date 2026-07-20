<template>
  <div class="chat-layout">
    <!-- 左侧栏：对话 / 知识库 -->
    <div class="chat-sidebar">
      <Sidebar
        :sessions="store.sessions"
        :currentId="store.currentSessionId"
        :activeTab="activeTab"
        @new="handleNew"
        @select="handleSelect"
        @delete="handleDelete"
        @update:activeTab="activeTab = $event"
      />
    </div>

    <!-- 右侧主区域 -->
    <div class="chat-main">
      <!-- 知识库模式提示 -->
      <div v-if="activeTab === 'knowledge' && !store.currentSessionId" class="chat-placeholder">
        <el-icon :size="48" color="#c0c4cc"><FolderOpened /></el-icon>
        <p>在左侧上传知识文档后，切换到「对话」进行问答</p>
      </div>

      <!-- 无会话时占位 -->
      <div v-else-if="!store.currentSessionId" class="chat-placeholder">
        <el-icon :size="56" color="#c0c4cc"><ChatDotRound /></el-icon>
        <p>选择或新建一个对话</p>
        <el-button type="primary" @click="handleNew">新建对话</el-button>
      </div>

      <!-- 有会话时聊天区 -->
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
import { ref, onMounted } from 'vue'
import { ChatDotRound, FolderOpened } from '@element-plus/icons-vue'
import { useChatStore } from '../stores/chatStore'
import Sidebar from '../components/chat/Sidebar.vue'
import MessageList from '../components/chat/MessageList.vue'
import MessageInput from '../components/chat/MessageInput.vue'

const store = useChatStore()
const activeTab = ref('chat')

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
  activeTab.value = 'chat'
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
  border: 1px solid rgba(51,65,85,0.3);
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg-card);
  backdrop-filter: blur(16px);
}

.chat-sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid rgba(51,65,85,0.3);
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
  color: var(--text-muted); font-size: 14px;
}

.chat-topbar {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid rgba(51,65,85,0.3);
  flex-shrink: 0;
}
.session-label {
  font-size: 14px; font-weight: 600; color: var(--text-primary);
}
</style>
