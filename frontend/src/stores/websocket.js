import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWebSocketStore = defineStore('websocket', () => {
  const connected = ref(false)
  let ws = null
  let reconnectTimer = null
  let reconnectAttempts = 0
  const maxReconnectAttempts = 10

  const handlers = {
    SENSOR_DATA: [],
    DEVICE_STATUS: [],
    NEW_ALARM: [],
    CONTROL_RESULT: []
  }

  function connect() {
    if (ws && ws.readyState === WebSocket.OPEN) return

    ws = new WebSocket(`ws://${window.location.host}/ws/monitor`)

    ws.onopen = () => {
      console.log('WebSocket connected')
      connected.value = true
      reconnectAttempts = 0
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        const { type, deviceId, data } = msg
        if (handlers[type]) {
          handlers[type].forEach(fn => fn(data, deviceId))
        }
      } catch (e) {
        console.error('WebSocket message parse error:', e)
      }
    }

    ws.onclose = () => {
      console.log('WebSocket disconnected')
      connected.value = false
      scheduleReconnect()
    }

    ws.onerror = (err) => {
      console.error('WebSocket error:', err)
      ws.close()
    }
  }

  function scheduleReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) return
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)
    reconnectAttempts++
    reconnectTimer = setTimeout(() => {
      console.log(`Reconnecting (attempt ${reconnectAttempts})...`)
      connect()
    }, delay)
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
  }

  function on(type, handler) {
    if (handlers[type]) {
      handlers[type].push(handler)
    }
  }

  function off(type, handler) {
    if (handlers[type]) {
      const idx = handlers[type].indexOf(handler)
      if (idx >= 0) handlers[type].splice(idx, 1)
    }
  }

  return {
    connected,
    connect, disconnect, on, off
  }
})
