import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getDevices } from '@/api/device'

export const useDeviceStore = defineStore('device', () => {
  const devices = ref([])
  const loading = ref(false)

  const onlineCount = computed(() => devices.value.filter(d => d.status === 'online').length)
  const offlineCount = computed(() => devices.value.filter(d => d.status === 'offline').length)
  const lightsOnCount = computed(() => devices.value.filter(d => d.lightStatus === 'on').length)
  const lightsOffCount = computed(() => devices.value.filter(d => d.lightStatus === 'off').length)

  async function fetchDevices() {
    loading.value = true
    try {
      devices.value = await getDevices()
    } catch (e) {
      console.error('Failed to fetch devices:', e)
    } finally {
      loading.value = false
    }
  }

  function updateDeviceStatus(deviceId, status, lightStatus) {
    const device = devices.value.find(d => d.deviceId === deviceId)
    if (device) {
      if (status !== undefined) device.status = status
      if (lightStatus !== undefined) device.lightStatus = lightStatus
    }
  }

  function updateSensorData(deviceId, lightIntensity) {
    const device = devices.value.find(d => d.deviceId === deviceId)
    if (device) {
      device.lastLightIntensity = lightIntensity
    }
  }

  return {
    devices, loading,
    onlineCount, offlineCount,
    lightsOnCount, lightsOffCount,
    fetchDevices, updateDeviceStatus, updateSensorData
  }
})
