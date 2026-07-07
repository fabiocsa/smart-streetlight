<template>
  <el-container style="min-height: 100vh">
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
        <el-menu-item index="/devices">
          <el-icon><Cpu /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/sensors">
          <el-icon><Connection /></el-icon>
          <span>传感器管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header style="height: 56px; border-bottom: 1px solid #e4e7ed; display: flex; align-items: center; justify-content: space-between">
        <el-breadcrumb>
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta?.title">{{ route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <el-tag type="success" size="small">后端: 8.130.102.89:8080</el-tag>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => {
  if (route.path.startsWith('/sensors')) return '/sensors'
  return '/devices'
})
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
