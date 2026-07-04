<template>
  <el-menu
    :default-active="activeMenu"
    :collapse="isCollapse"
    router
    background-color="#001529"
    text-color="#ffffffcc"
    active-text-color="#fff"
  >
    <div class="sidebar-logo">
      <el-icon :size="28" color="#409EFF">
        <Lightning />
      </el-icon>
      <span v-show="!isCollapse" class="sidebar-title">智慧路灯系统</span>
    </div>

    <el-menu-item index="/dashboard">
      <el-icon><Odometer /></el-icon>
      <template #title>总览</template>
    </el-menu-item>
    <el-menu-item index="/monitor">
      <el-icon><DataAnalysis /></el-icon>
      <template #title>实时监测</template>
    </el-menu-item>
    <el-menu-item index="/history">
      <el-icon><TrendCharts /></el-icon>
      <template #title>历史趋势</template>
    </el-menu-item>
    <el-menu-item index="/control">
      <el-icon><Switch /></el-icon>
      <template #title>设备控制</template>
    </el-menu-item>
    <el-menu-item index="/devices">
      <el-icon><Monitor /></el-icon>
      <template #title>设备管理</template>
    </el-menu-item>
    <el-menu-item index="/alarms">
      <el-icon><WarningFilled /></el-icon>
      <template #title>告警管理</template>
    </el-menu-item>
    <el-menu-item index="/chat">
      <el-icon><ChatDotRound /></el-icon>
      <template #title>智能问答</template>
    </el-menu-item>
  </el-menu>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Odometer, Monitor, WarningFilled, Lightning, DataAnalysis, TrendCharts, Switch, ChatDotRound } from '@element-plus/icons-vue'

const route = useRoute()
const props = defineProps({
  isCollapse: {
    type: Boolean,
    default: false
  }
})

const activeMenu = computed(() => route.path)
</script>

<style scoped>
.sidebar-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.sidebar-title {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  white-space: nowrap;
}

.el-menu {
  border-right: none;
}
</style>
