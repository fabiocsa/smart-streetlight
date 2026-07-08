<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>
          <strong>已绑定传感器</strong>
          <span style="color: #909399; margin-left: 8px">（共 {{ sensors.length }} 个）</span>
        </span>
        <el-button
          v-if="selectedIds.length > 0"
          type="danger"
          size="small"
          @click="handleBatchDelete"
        >
          批量解绑 ({{ selectedIds.length }})
        </el-button>
      </div>
    </template>
    <el-table
      ref="tableRef"
      :data="sensors"
      v-loading="loading"
      stripe
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="40" />
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
      <el-table-column label="启用" width="80">
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
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="$emit('edit', row)">编辑</el-button>
          <el-popconfirm
            title="确定解绑该传感器吗？"
            confirm-button-text="确定"
            @confirm="$emit('delete', row.id)"
          >
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
import { ref } from 'vue'
import { useSensorStore } from '../store/sensor'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatTime, typeLabel } from '../utils/common'

const props = defineProps({
  deviceId: { type: String, required: true },
  sensors: { type: Array, default: () => [] },
  loading: Boolean
})

const emit = defineEmits(['refresh', 'edit', 'delete', 'updateFrequency', 'batchDelete'])
const sensorStore = useSensorStore()
const tableRef = ref(null)
const selectedIds = ref([])

function handleSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.id)
}

async function handleToggle(row, enabled) {
  try {
    await sensorStore.update(row.deviceId, row.id, { enabled })
    ElMessage.success(enabled ? '传感器已启用' : '传感器已停用')
    emit('refresh')
  } catch {
    // 错误已在拦截器统一提示
  }
}

async function handleBatchDelete() {
  try {
    await ElMessageBox.confirm(
      `确定解绑选中的 ${selectedIds.value.length} 个传感器吗？`,
      '批量解绑确认',
      { confirmButtonText: '确定解绑', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  let successCount = 0
  let failCount = 0
  for (const id of selectedIds.value) {
    try {
      await sensorStore.remove(props.deviceId, id)
      successCount++
    } catch {
      failCount++
    }
  }

  if (failCount > 0) {
    ElMessage.warning(`成功解绑 ${successCount} 个，${failCount} 个失败`)
  } else {
    ElMessage.success(`成功解绑 ${successCount} 个传感器`)
  }
  selectedIds.value = []
  emit('refresh')
}
</script>
