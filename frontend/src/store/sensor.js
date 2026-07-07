import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import * as sensorApi from '../api/sensor'

export const useSensorStore = defineStore('sensor', () => {
  // 以 deviceId 为 key 缓存传感器列表，同时也存一份 allSensors 供总览页使用
  const sensorsByDevice = reactive({})
  const allSensors = ref([])
  const loading = ref(false)

  async function fetchByDevice(deviceId) {
    loading.value = true
    try {
      const res = await sensorApi.getSensors(deviceId)
      const list = Array.isArray(res) ? res : (res.data || [])
      sensorsByDevice[deviceId] = list
      return list
    } finally {
      loading.value = false
    }
  }

  async function fetchAll() {
    loading.value = true
    try {
      // 先获取所有设备，再逐个获取传感器
      const { useDeviceStore } = await import('./device')
      const deviceStore = useDeviceStore()
      await deviceStore.fetchAll()
      const all = []
      for (const device of deviceStore.devices) {
        const sensors = await sensorApi.getSensors(device.deviceId)
        const list = Array.isArray(sensors) ? sensors : (sensors || [])
        sensorsByDevice[device.deviceId] = list
        all.push(...list)
      }
      allSensors.value = all
      return all
    } finally {
      loading.value = false
    }
  }

  async function create(deviceId, data) {
    const res = await sensorApi.createSensor(deviceId, data)
    await fetchByDevice(deviceId)
    return res
  }

  async function update(deviceId, id, data) {
    const res = await sensorApi.updateSensor(deviceId, id, data)
    await fetchByDevice(deviceId)
    return res
  }

  async function remove(deviceId, id) {
    await sensorApi.deleteSensor(deviceId, id)
    await fetchByDevice(deviceId)
  }

  async function updateFreq(deviceId, id, data) {
    const res = await sensorApi.updateFrequency(deviceId, id, data)
    await fetchByDevice(deviceId)
    return res
  }

  async function syncToMock(deviceId) {
    return sensorApi.syncToMock(deviceId)
  }

  return { sensorsByDevice, allSensors, loading, fetchByDevice, fetchAll, create, update, remove, updateFreq, syncToMock }
})
