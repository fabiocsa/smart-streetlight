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
      // axios 拦截器已解包，但后端返回的是数组直接放在 data 里
      devices.value = Array.isArray(res) ? res : (res.data || [])
    } finally {
      loading.value = false
    }
  }

  async function fetchOne(id) {
    const res = await deviceApi.getDevice(id)
    currentDevice.value = res || res.data || null
    return currentDevice.value
  }

  async function create(data) {
    const res = await deviceApi.createDevice(data)
    await fetchAll()
    return res
  }

  async function update(id, data) {
    const res = await deviceApi.updateDevice(id, data)
    currentDevice.value = res || res.data || null
    await fetchAll()
    return res
  }

  async function remove(id) {
    await deviceApi.deleteDevice(id)
    await fetchAll()
  }

  return { devices, currentDevice, loading, fetchAll, fetchOne, create, update, remove }
})
