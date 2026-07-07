<template>
  <div>
    <div class="page-header">
      <h2>设备管理</h2>
      <el-button type="primary" @click="openAddDialog">
        <el-icon><Plus /></el-icon> 添加设备
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-row :gutter="16">
        <el-col :span="6">
          <el-input v-model="searchKeyword" placeholder="搜索名称/设备ID/位置" clearable @input="filterDevices" />
        </el-col>
        <el-col :span="4">
          <el-select v-model="filterStatus" placeholder="设备状态" clearable @change="filterDevices">
            <el-option label="在线" value="online" />
            <el-option label="离线" value="offline" />
          </el-select>
        </el-col>
      </el-row>
    </el-card>

    <!-- 设备表格 -->
    <el-card shadow="never">
      <el-table :data="paginatedDevices" v-loading="deviceStore.loading" stripe style="width: 100%">
        <el-table-column prop="deviceId" label="设备ID" width="120" />
        <el-table-column prop="name" label="设备名称" min-width="140" />
        <el-table-column prop="location" label="安装位置" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'online' ? 'success' : 'danger'" size="small">
              {{ row.status === 'online' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="灯光" width="80">
          <template #default="{ row }">
            <el-tag :type="row.lightStatus === 'on' ? 'warning' : 'info'" size="small">
              {{ row.lightStatus === 'on' ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="控制模式" width="100">
          <template #default="{ row }">
            {{ row.controlMode === 'auto' ? '自动' : '手动' }}
          </template>
        </el-table-column>
        <el-table-column label="传感器数" width="90">
          <template #default="{ row }">
            <el-link type="primary" @click="goDetail(row.id)">
              {{ row.sensorCount ?? '-' }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="170">
          <template #default="{ row }">
            {{ row.lastHeartbeat ? formatTime(row.lastHeartbeat) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goDetail(row.id)">详情</el-button>
            <el-button type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定删除该设备吗？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无设备数据" />
        </template>
      </el-table>

      <el-pagination
        v-if="filteredDevices.length > pageSize"
        style="margin-top: 16px; justify-content: flex-end"
        background
        layout="total, prev, pager, next"
        :total="filteredDevices.length"
        :page-size="pageSize"
        v-model:current-page="currentPage"
      />
    </el-card>

    <!-- 添加/编辑对话框 -->
    <DeviceForm
      v-model:visible="dialogVisible"
      :edit-data="editingDevice"
      @saved="handleSaved"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useDeviceStore } from '../store/device'
import DeviceForm from '../components/DeviceForm.vue'

const router = useRouter()
const deviceStore = useDeviceStore()

const searchKeyword = ref('')
const filterStatus = ref('')
const dialogVisible = ref(false)
const editingDevice = ref(null)
const currentPage = ref(1)
const pageSize = 10

const filteredDevices = computed(() => {
  let list = deviceStore.devices
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(d =>
      d.name?.toLowerCase().includes(kw) ||
      d.deviceId?.toLowerCase().includes(kw) ||
      d.location?.toLowerCase().includes(kw)
    )
  }
  if (filterStatus.value) {
    list = list.filter(d => d.status === filterStatus.value)
  }
  return list
})

const paginatedDevices = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredDevices.value.slice(start, start + pageSize)
})

function filterDevices() {
  currentPage.value = 1
}

function openAddDialog() {
  editingDevice.value = null
  dialogVisible.value = true
}

function openEditDialog(row) {
  editingDevice.value = { ...row }
  dialogVisible.value = true
}

function goDetail(id) {
  router.push(`/devices/${id}`)
}

async function handleDelete(id) {
  await deviceStore.remove(id)
  ElMessage.success('设备已删除')
}

async function handleSaved() {
  dialogVisible.value = false
  await deviceStore.fetchAll()
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(() => {
  deviceStore.fetchAll()
})
</script>

<style scoped>
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }
</style>
