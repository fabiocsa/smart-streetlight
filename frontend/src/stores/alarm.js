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
      alarms.value = res.content || []
      totalElements.value = res.totalElements || 0
      return res
    } catch (e) {
      console.error('Failed to fetch alarms:', e)
    } finally {
      loading.value = false
    }
  }

  function addNewAlarm(alarm) {
    alarms.value.unshift(alarm)
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
