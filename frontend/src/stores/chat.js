import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])

  function setMessages(newMessages) {
    messages.value = newMessages
  }

  function addMessage(msg) {
    messages.value.push(msg)
  }

  function clearMessages() {
    messages.value = []
  }

  return { messages, setMessages, addMessage, clearMessages }
})
