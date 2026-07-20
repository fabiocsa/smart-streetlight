import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as deviceApi from '../api/device'

export const useDeviceStore = defineStore('device', () => {
  const devices = ref([])
  const currentDevice = ref(null)
  const loading = ref(false)

  async function fetchAll() {
    loading.value = true
    try {
      const res = await deviceApi.getDevices()
      devices.value = Array.isArray(res) ? res : (res.data || [])
    } finally {
      loading.value = false
    }
  }

  async function fetchOne(id) {
    const res = await deviceApi.getDevice(id)
    // API 响应可能直接返回数据，也可能包含在 .data 中
    const device = (res && typeof res.data !== 'undefined') ? res.data : res
    currentDevice.value = device || null
    return currentDevice.value
  }

  async function create(data) {
    const res = await deviceApi.createDevice(data)
    const device = res?.data || res
    // 局部插入，避免全量刷新
    if (device) {
      devices.value.push(device)
    } else {
      await fetchAll()
    }
    return res
  }

  async function update(id, data) {
    const res = await deviceApi.updateDevice(id, data)
    const updated = res?.data || res
    // 局部更新
    if (updated) {
      const idx = devices.value.findIndex(d => d.id === id || d.deviceId === updated.deviceId)
      if (idx !== -1) {
        devices.value[idx] = { ...devices.value[idx], ...updated }
      }
    }
    if (currentDevice.value && (currentDevice.value.id === id || currentDevice.value.deviceId === updated?.deviceId)) {
      currentDevice.value = { ...currentDevice.value, ...updated }
    }
    return res
  }

  async function remove(id) {
    await deviceApi.deleteDevice(id)
    // 局部删除
    devices.value = devices.value.filter(d => d.id !== id && d.deviceId !== id)
  }

  /** 批量删除（串行调用单个删除 API） */
  async function removeBatch(ids) {
    const results = []
    for (const id of ids) {
      try {
        await deviceApi.deleteDevice(id)
        results.push({ id, success: true })
      } catch (e) {
        results.push({ id, success: false, error: e.message })
      }
    }
    // 局部批量删除成功的项
    const successIds = results.filter(r => r.success).map(r => r.id)
    if (successIds.length > 0) {
      devices.value = devices.value.filter(d => !successIds.includes(d.id) && !successIds.includes(d.deviceId))
    }
    return results
  }

  return { devices, currentDevice, loading, fetchAll, fetchOne, create, update, remove, removeBatch }
})
