<template>
  <el-card shadow="never">
    <template #header>
      <strong>已绑定传感器</strong>
      <span style="color: #909399; margin-left: 8px">（共 {{ sensors.length }} 个）</span>
    </template>
    <el-table :data="sensors" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="displayName" label="传感器名称" min-width="130" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ typeLabel(row.sensorType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dataTopic" label="数据主题" min-width="180" show-overflow-tooltip />
      <el-table-column label="上报频率" width="120">
        <template #default="{ row }">
          <el-input-number
            :model-value="row.reportFrequency"
            :min="1"
            :max="3600"
            size="small"
            style="width: 80px"
            @change="(v) => $emit('updateFrequency', row.id, v)"
          />
          <span style="margin-left: 4px; font-size: 12px; color: #909399">秒</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            size="small"
            @change="(v) => handleToggle(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="配置" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.configJson || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">
          {{ row.createdAt ? formatTime(row.createdAt) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="$emit('edit', row)">编辑</el-button>
          <el-popconfirm title="确定解绑该传感器吗？" @confirm="$emit('delete', row.id)">
            <template #reference>
              <el-button type="danger" link>解绑</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无传感器，请点击上方按钮添加" />
      </template>
    </el-table>
  </el-card>
</template>

<script setup>
import { useSensorStore } from '../store/sensor'
import { ElMessage } from 'element-plus'

defineProps({
  deviceId: { type: String, required: true },
  sensors: { type: Array, default: () => [] },
  loading: Boolean
})

const emit = defineEmits(['refresh', 'edit', 'delete', 'updateFrequency'])
const sensorStore = useSensorStore()

function typeLabel(t) {
  const map = { light: '光照', temperature: '温度', humidity: '湿度', power: '功率' }
  return map[t] || t
}

function formatTime(t) {
  return new Date(t).toLocaleString('zh-CN')
}

async function handleToggle(row, enabled) {
  await sensorStore.update(row.deviceId, row.id, { enabled })
  ElMessage.success(enabled ? '传感器已启用' : '传感器已停用')
  emit('refresh')
}
</script>
