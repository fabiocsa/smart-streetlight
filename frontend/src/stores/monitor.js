import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getLatestSensorData } from '@/api/sensor'

export const useMonitorStore = defineStore('monitor', () => {
  // Current device selection
  const currentDeviceId = ref(null)

  // Real-time sensor data per device
  const sensorDataMap = ref({}) // { deviceId: { lightIntensity, reportedAt, status } }

  // Recent readings per device (for mini trend)
  const recentReadings = ref({}) // { deviceId: [{ lightIntensity, reportedAt }] }
  const MAX_RECENT = 10

  // Loading states
  const loading = ref(false)
  const dataAbnormal = ref(false)
  const fallbackPolling = ref(false)

  // Computed
  const currentSensorData = computed(() =>
    currentDeviceId.value ? sensorDataMap.value[currentDeviceId.value] : null
  )

  const currentRecentReadings = computed(() =>
    currentDeviceId.value ? recentReadings.value[currentDeviceId.value] || [] : []
  )

  const isCurrentDeviceOffline = computed(() =>
    currentDeviceId.value
      ? sensorDataMap.value[currentDeviceId.value]?.status === 'offline'
      : false
  )

  function setCurrentDevice(deviceId) {
    currentDeviceId.value = deviceId
  }

  function updateSensorData(deviceId, data) {
    if (!deviceId || !data) return

    // Check for abnormal values
    const intensity = data.lightIntensity
    if (intensity !== undefined && (intensity < 0 || intensity > 2000)) {
      dataAbnormal.value = true
    } else {
      dataAbnormal.value = false
    }

    // Update latest data
    sensorDataMap.value[deviceId] = {
      ...sensorDataMap.value[deviceId],
      ...data,
      updatedAt: Date.now()
    }

    // Update recent readings
    if (!recentReadings.value[deviceId]) {
      recentReadings.value[deviceId] = []
    }
    if (intensity !== undefined) {
      recentReadings.value[deviceId].push({
        lightIntensity: intensity,
        reportedAt: data.reportedAt || new Date().toISOString()
      })
      if (recentReadings.value[deviceId].length > MAX_RECENT) {
        recentReadings.value[deviceId].shift()
      }
    }
  }

  function updateDeviceStatus(deviceId, status) {
    if (!sensorDataMap.value[deviceId]) {
      sensorDataMap.value[deviceId] = {}
    }
    sensorDataMap.value[deviceId].status = status
  }

  async function fetchLatestData(deviceId) {
    if (!deviceId) return
    try {
      const data = await getLatestSensorData(deviceId)
      updateSensorData(deviceId, data)
      return data
    } catch (e) {
      console.error('Failed to fetch latest sensor data:', e)
      return null
    }
  }

  function clearData() {
    sensorDataMap.value = {}
    recentReadings.value = {}
    currentDeviceId.value = null
    dataAbnormal.value = false
  }

  return {
    currentDeviceId,
    sensorDataMap,
    recentReadings,
    loading,
    dataAbnormal,
    fallbackPolling,
    currentSensorData,
    currentRecentReadings,
    isCurrentDeviceOffline,
    setCurrentDevice,
    updateSensorData,
    updateDeviceStatus,
    fetchLatestData,
    clearData
  }
})
