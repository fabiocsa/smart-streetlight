<template>
  <AppLayout />
</template>

<script setup>
import { onErrorCaptured } from 'vue'
import { ElMessage } from 'element-plus'
import AppLayout from '@/components/layout/AppLayout.vue'

// Global error boundary - prevents component crashes from taking down the entire app
onErrorCaptured((err, instance, info) => {
  console.error('Vue error caught:', err, info)
  ElMessage.error('页面渲染异常，请刷新重试')
  return false // Prevent error from propagating
})
</script>
