<template>
  <el-container class="app-layout">
    <!-- Sidebar -->
    <el-aside :width="isCollapse ? '64px' : '220px'" class="app-aside">
      <Sidebar :is-collapse="isCollapse" />
    </el-aside>

    <el-container>
      <!-- Header -->
      <el-header class="app-header">
        <div class="header-left">
          <el-icon
            class="collapse-btn"
            :size="20"
            @click="isCollapse = !isCollapse"
          >
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <!-- Connection status -->
          <div class="status-item">
            <span
              class="status-dot"
              :class="wsStore.connected ? 'connected' : 'disconnected'"
            ></span>
            <span class="status-text">{{ wsStore.connected ? '已连接' : '未连接' }}</span>
          </div>
          <!-- Device counts -->
          <div class="status-item">
            <el-icon color="#67C23A"><CircleCheck /></el-icon>
            <span>{{ deviceStore.onlineCount }}/{{ deviceStore.devices.length }} 在线</span>
          </div>
          <!-- Pending alarms -->
          <div class="status-item" v-if="alarmStore.pendingCount > 0">
            <el-badge :value="alarmStore.pendingCount" :hidden="alarmStore.pendingCount === 0">
              <el-icon color="#E6A23C"><WarningFilled /></el-icon>
            </el-badge>
          </div>
        </div>
      </el-header>

      <!-- Main Content -->
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { Fold, Expand, CircleCheck, WarningFilled } from '@element-plus/icons-vue'
import Sidebar from './Sidebar.vue'
import { useDeviceStore } from '@/stores/device'
import { useAlarmStore } from '@/stores/alarm'
import { useWebSocketStore } from '@/stores/websocket'

const route = useRoute()
const isCollapse = ref(false)

const deviceStore = useDeviceStore()
const alarmStore = useAlarmStore()
const wsStore = useWebSocketStore()

// WebSocket event handlers
function handleDeviceStatus(data, deviceId) {
  if (data) {
    deviceStore.updateDeviceStatus(deviceId, data.status, data.lightStatus)
  }
}

function handleNewAlarm(data) {
  if (data) {
    alarmStore.addNewAlarm(data)
  }
}

onMounted(() => {
  deviceStore.fetchDevices()
  alarmStore.fetchAlarms({ status: 'pending', page: 0, size: 100 })
  wsStore.connect()
  wsStore.on('DEVICE_STATUS', handleDeviceStatus)
  wsStore.on('NEW_ALARM', handleNewAlarm)
})

onUnmounted(() => {
  wsStore.off('DEVICE_STATUS', handleDeviceStatus)
  wsStore.off('NEW_ALARM', handleNewAlarm)
})
</script>

<style scoped>
.app-layout {
  height: 100vh;
}

.app-aside {
  background-color: #001529;
  transition: width 0.3s;
  overflow: hidden;
}

.app-header {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: #409EFF;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-dot.connected {
  background-color: #67C23A;
  box-shadow: 0 0 4px #67C23A;
}

.status-dot.disconnected {
  background-color: #F56C6C;
}

.status-text {
  font-size: 12px;
}

.app-main {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
