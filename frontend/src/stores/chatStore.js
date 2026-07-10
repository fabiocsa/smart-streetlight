import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getSessions, createSession, deleteSession, renameSession,
  getMessages, sendMessage
} from '../api/chat'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref([])
  const currentSessionId = ref(null)
  const currentMessages = ref([])
  const isLoading = ref(false)

  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) || null
  )

  async function loadSessions() {
    sessions.value = await getSessions()
  }

  async function selectSession(id) {
    currentSessionId.value = id
    currentMessages.value = await getMessages(id)
  }

  async function newSession() {
    const session = await createSession('新对话')
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    currentMessages.value = []
    return session
  }

  async function removeSession(id) {
    await deleteSession(id)
    sessions.value = sessions.value.filter(s => s.id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = null
      currentMessages.value = []
    }
  }

  async function renameCurrentSession(title) {
    if (!currentSessionId.value) return
    await renameSession(currentSessionId.value, title)
    const s = sessions.value.find(s => s.id === currentSessionId.value)
    if (s) s.title = title
  }

  async function send(question) {
    if (!currentSessionId.value || isLoading.value) return
    isLoading.value = true
    currentMessages.value.push({ role: 'user', content: question })

    try {
      const data = await sendMessage(currentSessionId.value, question)
      currentMessages.value.push({ role: 'assistant', content: data.answer })
      // 刷新会话列表以更新标题和时间
      await loadSessions()
    } catch {
      currentMessages.value.push({
        role: 'assistant',
        content: '抱歉，AI 服务暂不可用，请稍后再试。'
      })
    } finally {
      isLoading.value = false
    }
  }

  function reset() {
    sessions.value = []
    currentSessionId.value = null
    currentMessages.value = []
    isLoading.value = false
  }

  return {
    sessions, currentSessionId, currentMessages, isLoading, currentSession,
    loadSessions, selectSession, newSession, removeSession, renameCurrentSession, send, reset
  }
})
