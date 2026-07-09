import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as sensorApi from '../api/sensor'
import * as deviceApi from '../api/device'

export const useSensorStore = defineStore('sensor', () => {
  const allSensors = ref([])
  const unboundSensors = ref([])
  const loading = ref(false)

  /** 获取所有传感器 */
  async function fetchAll() {
    loading.value = true
    try {
      const res = await sensorApi.getAllSensors()
      allSensors.value = Array.isArray(res) ? res : (res?.data || [])
      return allSensors.value
    } finally {
      loading.value = false
    }
  }

  /** 获取未绑定传感器 */
  async function fetchUnbound() {
    loading.value = true
    try {
      const res = await sensorApi.getUnboundSensors()
      unboundSensors.value = Array.isArray(res) ? res : (res?.data || [])
      return unboundSensors.value
    } finally {
      loading.value = false
    }
  }

  /** 获取设备已绑定传感器 */
  async function fetchByDevice(deviceId) {
    loading.value = true
    try {
      const res = await deviceApi.getDeviceSensors(deviceId)
      return Array.isArray(res) ? res : (res?.data || [])
    } finally {
      loading.value = false
    }
  }

  /** 创建独立传感器 */
  async function create(data) {
    const res = await sensorApi.createSensor(data)
    const sensor = res?.data || res
    if (sensor) {
      allSensors.value = [...allSensors.value, sensor]
    }
    return res
  }

  /** 更新传感器 */
  async function update(id, data) {
    const res = await sensorApi.updateSensor(id, data)
    const updated = res?.data || res
    const idx = allSensors.value.findIndex(s => s.id === id)
    if (idx !== -1 && updated) {
      allSensors.value[idx] = { ...allSensors.value[idx], ...updated }
    }
    return res
  }

  /** 删除传感器 */
  async function remove(id) {
    await sensorApi.deleteSensor(id)
    allSensors.value = allSensors.value.filter(s => s.id !== id)
  }

  /** 更新频率 */
  async function updateFreq(id, data) {
    const res = await sensorApi.updateFrequency(id, data)
    const newFreq = data.reportFrequency
    if (newFreq) {
      const s = allSensors.value.find(s => s.id === id)
      if (s) s.reportFrequency = newFreq
    }
    return res
  }

  /** 设备绑定传感器 */
  async function bindToDevice(deviceId, sensorId) {
    return deviceApi.bindSensor(deviceId, sensorId)
  }

  /** 设备解绑传感器 */
  async function unbindFromDevice(deviceId, sensorId) {
    return deviceApi.unbindSensor(deviceId, sensorId)
  }

  return {
    allSensors, unboundSensors, loading,
    fetchAll, fetchUnbound, fetchByDevice,
    create, update, remove,
    updateFreq,
    bindToDevice, unbindFromDevice
  }
})
