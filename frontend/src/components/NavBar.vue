<template>
  <nav class="navbar" :class="{ hidden: isHidden, 'has-bg': showBg }">
    <div class="nav-inner">
      <!-- 左侧：Logo + 菜单 -->
      <div class="nav-left">
        <svg class="nav-logo" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#3B82F6" stroke-width="1.5">
          <rect x="8" y="2" width="8" height="4" rx="1" />
          <line x1="12" y1="6" x2="12" y2="14" />
          <circle cx="12" cy="17" r="2.5" />
          <path d="M4 22 L12 14 L20 22" stroke="#3B82F6" stroke-opacity="0.3" />
        </svg>
        <span class="nav-title" @click="go('/dashboard')">智慧路灯</span>

        <router-link v-for="m in menus" :key="m.path" :to="m.path" class="nav-link" active-class="nav-active">
          <el-icon :size="16"><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
        </router-link>
      </div>

      <!-- 右侧：状态 -->
      <div class="nav-right">
        <span class="ws-dot" :class="wsOk ? 'ws-on' : 'ws-off'" :title="wsOk ? 'WebSocket 已连接' : 'WebSocket 断开'"></span>
        <span class="nav-clock">{{ now }}</span>
        <!-- 主题切换按钮 -->
        <button class="theme-btn" @click="toggleTheme" :title="isDark ? '切换亮色' : '切换暗色'">
          <span class="theme-icon" :class="{ switched: !isDark }">
            <!-- 太阳 -->
            <svg class="icon-sun" viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
            <!-- 月亮 -->
            <svg class="icon-moon" viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>
          </span>
        </button>
        <router-link to="/alarms" class="nav-bell" v-if="authStore.isAdmin || authStore.isOperator">
          <el-icon :size="17"><Bell /></el-icon>
          <span v-if="pendingCount > 0" class="bell-dot">{{ pendingCount > 99 ? '99+' : pendingCount }}</span>
        </router-link>
        <el-dropdown trigger="click" @command="onCmd">
          <span class="nav-avatar">{{ authStore.username?.charAt(0)?.toUpperCase() || 'U' }}</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/authStore'
import { useTheme } from '../composables/useTheme'

const router = useRouter()
const authStore = useAuthStore()
const { isDark, toggle: toggleTheme } = useTheme()

const menus = computed(() => {
  const items = [
    { path: '/dashboard', label: '仪表盘', icon: 'DataAnalysis' },
    { path: '/devices', label: '设备', icon: 'Cpu' },
    { path: '/sensors', label: '传感器', icon: 'Connection' },
    { path: '/light-trend', label: '趋势', icon: 'TrendCharts' }
  ]
  if (authStore.isAdmin || authStore.isOperator) {
    items.push({ path: '/alarms', label: '告警', icon: 'Bell' })
  }
  items.push({ path: '/chat', label: 'AI问答', icon: 'ChatDotRound' })
  return items
})

const pendingCount = ref(0)
const wsOk = ref(false)
const now = ref('')
let clockTimer = null

setInterval(() => {
  now.value = new Date().toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}, 1000)

// ===== 智能显隐：顶部透明 + 鼠标靠近/滚动后毛玻璃 =====
const isHidden = ref(false)
const showBg = ref(false)
let lastScroll = 0
let mouseNearTop = false

function updateVisibility() {
  const y = window.scrollY
  showBg.value = y > 5 || mouseNearTop
  if (y < 5 || mouseNearTop || y < lastScroll - 10) {
    isHidden.value = false
  } else if (y > 60 && y > lastScroll) {
    isHidden.value = true
  }
  lastScroll = y
}

function onScroll() { updateVisibility() }
function onMouseMove(e) {
  mouseNearTop = e.clientY < 60
  updateVisibility()
}

function go(path) { router.push(path) }
function onCmd(cmd) {
  if (cmd === 'logout') { authStore.logout(); router.push('/login') }
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('mousemove', onMouseMove, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('mousemove', onMouseMove)
})
</script>

<style scoped>
.navbar {
  position: fixed; top: 0; left: 0; right: 0; z-index: 1000;
  height: 52px;
  background: transparent;
  border-bottom: 1px solid transparent;
  transition: transform 0.3s cubic-bezier(.4,0,.2,1),
              background 0.35s ease,
              backdrop-filter 0.35s ease,
              border-color 0.35s ease;
}
.navbar.has-bg {
  background: var(--navbar-bg);
  backdrop-filter: blur(20px);
  border-bottom-color: var(--border-card);
}
.navbar.hidden { transform: translateY(-100%); }

.nav-inner {
  max-width: 1440px; margin: 0 auto; height: 100%;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px;
}

/* 左侧 */
.nav-left { display: flex; align-items: center; gap: 4px; }
.nav-logo { flex-shrink: 0; }
.nav-title {
  font-size: 16px; font-weight: 700; color: var(--text-primary); cursor: pointer;
  margin-right: 20px; letter-spacing: 0.5px;
}

.nav-link {
  display: flex; align-items: center; gap: 5px;
  padding: 6px 14px; border-radius: 8px;
  font-size: 13px; color: var(--text-secondary); text-decoration: none;
  transition: all 0.2s;
}
.nav-link:hover { color: var(--text-primary); background: rgba(59,130,246,0.1); }
.nav-active { color: var(--blue) !important; background: rgba(59,130,246,0.12); }

/* 右侧 */
.nav-right { display: flex; align-items: center; gap: 16px; }
.ws-dot { width: 7px; height: 7px; border-radius: 50%; }
.ws-on { background: #06D6A0; box-shadow: 0 0 6px rgba(6,214,160,0.5); }
.ws-off { background: #EF4444; animation: blink 1s infinite; }
.nav-clock { font-family: monospace; font-size: 13px; color: var(--text-secondary); letter-spacing: 1px; }
.nav-bell { position: relative; color: var(--text-secondary); cursor: pointer; }
.nav-bell:hover { color: var(--text-primary); }
.bell-dot {
  position: absolute; top: -6px; right: -8px;
  background: #EF4444; color: #fff; font-size: 10px; font-weight: 700;
  min-width: 18px; height: 18px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center; padding: 0 4px;
}
.nav-avatar {
  width: 30px; height: 30px; border-radius: 50%; cursor: pointer;
  background: linear-gradient(135deg, #3B82F6, #06D6A0);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; color: #fff;
}

/* 主题切换按钮 */
.theme-btn {
  width: 34px; height: 34px; border-radius: 50%;
  border: 1px solid var(--border-card); background: var(--bg-float);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  overflow: hidden; position: relative;
}
.theme-btn:hover { border-color: var(--blue); box-shadow: 0 0 10px rgba(59,130,246,0.2); }
.theme-icon {
  position: relative; width: 17px; height: 17px;
  display: flex; align-items: center; justify-content: center;
}
.theme-icon svg {
  position: absolute;
  transition: transform 0.5s cubic-bezier(.4,0,.2,1), opacity 0.35s ease;
  color: var(--amber);
}
.icon-sun { opacity: 1; transform: rotate(0deg) scale(1); }
.icon-moon { opacity: 0; transform: rotate(-90deg) scale(0.6); }
.theme-icon.switched .icon-sun { opacity: 0; transform: rotate(90deg) scale(0.6); }
.theme-icon.switched .icon-moon { opacity: 1; transform: rotate(0deg) scale(1); }

@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }
</style>
