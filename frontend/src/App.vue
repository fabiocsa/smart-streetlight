<template>
  <!-- 登录页不显示侧边栏 -->
  <div v-if="route.meta?.public" style="background: #f0f2f5; min-height: 100vh">
    <router-view />
  </div>

  <el-container v-else style="min-height: 100vh">
    <el-aside width="220px" style="background: #1d1e2b; overflow: hidden">
      <div class="logo">
        <el-icon :size="24" color="#409EFF"><Monitor /></el-icon>
        <span>智慧路灯管理</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1d1e2b"
        text-color="#aeb6c6"
        active-text-color="#409EFF"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/devices">
          <el-icon><Cpu /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/sensors">
          <el-icon><Connection /></el-icon>
          <span>传感器管理</span>
        </el-menu-item>
        <el-menu-item index="/light-trend">
          <el-icon><TrendCharts /></el-icon>
          <span>光照趋势</span>
        </el-menu-item>
        <!-- ★ 告警管理对所有已认证用户可见（处理权限由后端控制） -->
        <el-menu-item index="/alarms">
          <el-icon><Bell /></el-icon>
          <span>告警管理</span>
        </el-menu-item>
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能问答</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header style="height: 56px; border-bottom: 1px solid #e4e7ed; display: flex; align-items: center; justify-content: space-between">
        <el-breadcrumb>
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta?.title">{{ route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div style="display: flex; align-items: center; gap: 12px">
          <el-tag :type="authStore.isAdmin ? 'danger' : (authStore.isOperator ? 'warning' : 'success')" size="small">
            {{ authStore.isAdmin ? '管理员' : (authStore.isOperator ? '路灯管理员' : '市政人员') }}
          </el-tag>
          <span style="font-size: 13px; color: #606266">{{ authStore.username }}</span>
          <el-button type="danger" link size="small" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/authStore'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeMenu = computed(() => {
  if (route.path.startsWith('/dashboard')) return '/dashboard'
  if (route.path.startsWith('/light-trend')) return '/light-trend'
  if (route.path.startsWith('/alarms')) return '/alarms'
  if (route.path.startsWith('/sensors')) return '/sensors'
  if (route.path.startsWith('/chat')) return '/chat'
  if (route.path.startsWith('/devices')) return '/devices'
  return '/dashboard'
})

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif; }
.logo {
  height: 56px; display: flex; align-items: center; gap: 8px;
  padding: 0 20px; color: #fff; font-size: 16px; font-weight: 600;
  border-bottom: 1px solid #2a2b3d;
}
</style>
