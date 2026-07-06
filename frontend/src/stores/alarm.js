import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getAlarms } from '@/api/alarm'

export const useAlarmStore = defineStore('alarm', () => {
  const alarms = ref([])
  const totalElements = ref(0)
  const loading = ref(false)

  const pendingCount = computed(() => alarms.value.filter(a => a.status === 'pending').length)

  async function fetchAlarms(params = {}) {
    loading.value = true
    try {
      const res = await getAlarms(params)
      alarms.value = (res && Array.isArray(res.content)) ? res.content : []
      totalElements.value = res?.totalElements || 0
      return res
    } catch (e) {
      console.error('Failed to fetch alarms:', e)
      alarms.value = []
    } finally {
      loading.value = false
    }
  }

  function addNewAlarm(alarm) {
    // Mark as new for highlight animation
    const newAlarm = { ...alarm, _isNew: true }
    alarms.value.unshift(newAlarm)
    // Remove _isNew flag after animation duration
    setTimeout(() => {
      const idx = alarms.value.findIndex(a => a === newAlarm)
      if (idx >= 0) {
        newAlarm._isNew = false
      }
    }, 2500)
  }

  function markResolved(alarmId) {
    const alarm = alarms.value.find(a => a.id === alarmId)
    if (alarm) {
      alarm.status = 'resolved'
    }
  }

  return {
    alarms, totalElements, loading, pendingCount,
    fetchAlarms, addNewAlarm, markResolved
  }
})
