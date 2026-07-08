import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import * as sensorApi from '../api/sensor'

export const useSensorStore = defineStore('sensor', () => {
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
      const { useDeviceStore } = await import('./device')
      const deviceStore = useDeviceStore()
      await deviceStore.fetchAll()

      // 并行请求所有设备的传感器
      const results = await Promise.allSettled(
        deviceStore.devices.map(d => sensorApi.getSensors(d.deviceId))
      )

      const all = []
      deviceStore.devices.forEach((device, i) => {
        const result = results[i]
        const list = result.status === 'fulfilled'
          ? (Array.isArray(result.value) ? result.value : (result.value?.data || []))
          : []
        sensorsByDevice[device.deviceId] = list
        all.push(...list)
      })
      allSensors.value = all
      return all
    } finally {
      loading.value = false
    }
  }

  async function create(deviceId, data) {
    const res = await sensorApi.createSensor(deviceId, data)
    const sensor = res?.data || res
    // 局部插入
    if (sensor) {
      if (!sensorsByDevice[deviceId]) sensorsByDevice[deviceId] = []
      sensorsByDevice[deviceId].push(sensor)
      // 同步 allSensors
      allSensors.value = [...allSensors.value, sensor]
    } else {
      await fetchByDevice(deviceId)
    }
    return res
  }

  async function update(deviceId, id, data) {
    const res = await sensorApi.updateSensor(deviceId, id, data)
    const updated = res?.data || res
    // 局部更新
    if (updated && sensorsByDevice[deviceId]) {
      const idx = sensorsByDevice[deviceId].findIndex(s => s.id === id || s.deviceId === updated.deviceId)
      if (idx !== -1) {
        sensorsByDevice[deviceId][idx] = { ...sensorsByDevice[deviceId][idx], ...updated }
      }
    }
    // 同步 allSensors
    const allIdx = allSensors.value.findIndex(s => s.id === id)
    if (allIdx !== -1 && updated) {
      allSensors.value[allIdx] = { ...allSensors.value[allIdx], ...updated }
    }
    return res
  }

  async function remove(deviceId, id) {
    await sensorApi.deleteSensor(deviceId, id)
    // 局部删除
    if (sensorsByDevice[deviceId]) {
      sensorsByDevice[deviceId] = sensorsByDevice[deviceId].filter(s => s.id !== id)
    }
    allSensors.value = allSensors.value.filter(s => !(s.deviceId === deviceId && s.id === id))
  }

  /** 批量删除传感器 */
  async function removeBatch(deviceId, ids) {
    const results = []
    for (const id of ids) {
      try {
        await sensorApi.deleteSensor(deviceId, id)
        results.push({ id, success: true })
      } catch (e) {
        results.push({ id, success: false, error: e.message })
      }
    }
    const successIds = results.filter(r => r.success).map(r => r.id)
    if (successIds.length > 0) {
      if (sensorsByDevice[deviceId]) {
        sensorsByDevice[deviceId] = sensorsByDevice[deviceId].filter(s => !successIds.includes(s.id))
      }
      allSensors.value = allSensors.value.filter(s => !(s.deviceId === deviceId && successIds.includes(s.id)))
    }
    return results
  }

  /** 批量更新传感器状态（启用/停用） */
  async function updateBatch(deviceId, ids, data) {
    const results = []
    for (const id of ids) {
      try {
        await sensorApi.updateSensor(deviceId, id, data)
        results.push({ id, success: true })
      } catch (e) {
        results.push({ id, success: false, error: e.message })
      }
    }
    const successIds = results.filter(r => r.success).map(r => r.id)
    if (successIds.length > 0 && sensorsByDevice[deviceId]) {
      sensorsByDevice[deviceId] = sensorsByDevice[deviceId].map(s =>
        successIds.includes(s.id) ? { ...s, ...data } : s
      )
    }
    allSensors.value = allSensors.value.map(s =>
      s.deviceId === deviceId && successIds.includes(s.id) ? { ...s, ...data } : s
    )
    return results
  }

  async function updateFreq(deviceId, id, data) {
    const res = await sensorApi.updateFrequency(deviceId, id, data)
    // 局部更新频率
    const newFreq = data.reportFrequency
    if (newFreq && sensorsByDevice[deviceId]) {
      const sensor = sensorsByDevice[deviceId].find(s => s.id === id)
      if (sensor) sensor.reportFrequency = newFreq
    }
    const allS = allSensors.value.find(s => s.id === id)
    if (allS && newFreq) allS.reportFrequency = newFreq
    return res
  }

  async function syncToMock(deviceId) {
    return sensorApi.syncToMock(deviceId)
  }

  return {
    sensorsByDevice, allSensors, loading,
    fetchByDevice, fetchAll,
    create, update, remove, removeBatch, updateBatch,
    updateFreq, syncToMock
  }
})
