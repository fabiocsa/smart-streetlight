<template>
  <!-- 登录页不显示导航 -->
  <div v-if="route.meta?.public">
    <router-view />
  </div>

  <div v-else class="app-layout">
    <NavBar />
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import NavBar from './components/NavBar.vue'

const route = useRoute()
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
}
.app-main {
  padding-top: 68px;  /* navbar 52px + 16px gap */
  padding-left: 20px;
  padding-right: 20px;
  max-width: 1440px;
  margin: 0 auto;
}
</style>
