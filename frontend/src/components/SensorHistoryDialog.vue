<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    :title="dialogTitle"
    width="800px"
    destroy-on-close
    @open="onDialogOpen"
  >
    <div class="history-header">
      <el-tag :type="sensorTypeTag(sensor?.sensorType)" size="small">
        {{ typeLabel(sensor?.sensorType) }}
      </el-tag>
      <span class="sensor-name">{{ sensor?.displayName || sensor?.id || '-' }}</span>
      <el-divider direction="vertical" />
      <el-radio-group v-model="selectedRange" size="small" @change="loadData">
        <el-radio-button value="1h">1小时</el-radio-button>
        <el-radio-button value="6h">6小时</el-radio-button>
        <el-radio-button value="24h">24小时</el-radio-button>
        <el-radio-button value="3d">3天</el-radio-button>
        <el-radio-button value="7d">7天</el-radio-button>
      </el-radio-group>
      <span v-if="historyData.length > 0" style="margin-left:8px;color:#909399;font-size:13px">
        共 {{ historyData.length }} 条
      </span>
    </div>

    <div v-loading="loading">
      <el-table
        v-if="historyData.length > 0"
        :data="historyData"
        stripe
        size="small"
        max-height="450"
        border
      >
        <el-table-column type="index" label="#" width="50" />
        <el-table-column label="上报时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.reportedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          v-for="col in dataColumns"
          :key="col.key"
          :label="col.label"
          min-width="120"
        >
          <template #default="{ row }">
            {{ formatValue(row, col.key) }}
          </template>
        </el-table-column>
        <el-table-column label="原始 JSON" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.dataJson" placement="top" :show-after="400">
              <span class="json-preview">{{ row.dataJson }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading" :description="errorMsg || '该传感器在选定时间范围内没有历史数据'" />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { getSensorHistory } from '../api/sensor'
import { typeLabel, sensorTypeTag, formatTime } from '../utils/common'

const props = defineProps({
  visible: Boolean,
  sensor: Object
})

defineEmits(['update:visible'])

const selectedRange = ref('1h')
const loading = ref(false)
const historyData = ref([])
const errorMsg = ref('')

const dialogTitle = computed(() => {
  const name = props.sensor?.displayName || props.sensor?.id || '传感器'
  return `${name} - 历史数据`
})

const dataColumns = computed(() => {
  const typeMap = {
    light: [
      { key: 'illuminance', label: '光照强度 (Lux)' }
    ],
    temperature: [
      { key: 'temperature', label: '温度 (℃)' }
    ],
    humidity: [
      { key: 'humidity', label: '湿度 (%)' }
    ],
    power: [
      { key: 'voltage', label: '电压 (V)' },
      { key: 'power', label: '功率 (W)' }
    ]
  }
  return typeMap[props.sensor?.sensorType] || []
})

function formatValue(row, key) {
  let parsed = row.dataJson
  if (typeof parsed === 'string') {
    try { parsed = JSON.parse(parsed) } catch { parsed = {} }
  }
  const v = parsed[key]
  if (v === undefined || v === null) return '-'
  if (typeof v === 'number') return v.toFixed(2)
  return v
}

function rangeToMs(range) {
  return { '1h': 3600000, '6h': 21600000, '24h': 86400000, '3d': 259200000, '7d': 604800000 }[range] || 3600000
}

function formatLocalISO(d) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function loadData() {
  const sensorId = props.sensor?.id
  if (!sensorId) return
  loading.value = true
  historyData.value = []
  errorMsg.value = ''

  try {
    const end = new Date()
    const start = new Date(end.getTime() - rangeToMs(selectedRange.value))
    const params = { start: formatLocalISO(start), end: formatLocalISO(end), limit: 2000 }
    const res = await getSensorHistory(sensorId, params)
    let arr = Array.isArray(res) ? res : (res?.data || res?.records || [])
    arr.reverse()
    historyData.value = arr
  } catch (e) {
    errorMsg.value = e?.message || '请求失败'
  } finally {
    loading.value = false
  }
}

function onDialogOpen() {
  selectedRange.value = '1h'
  errorMsg.value = ''
  loadData()
}

onMounted(() => {
  if (props.visible) {
    selectedRange.value = '1h'
    errorMsg.value = ''
    loadData()
  }
})
</script>

<style scoped>
.history-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.sensor-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-primary);
}
.json-preview {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-muted);
  cursor: default;
}
</style>
