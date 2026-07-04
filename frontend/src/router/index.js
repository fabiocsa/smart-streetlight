import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/dashboard/index.vue'),
    meta: { title: '总览' }
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('@/views/monitor/index.vue'),
    meta: { title: '实时监测' }
  },
  {
    path: '/history',
    name: 'History',
    component: () => import('@/views/history/index.vue'),
    meta: { title: '历史趋势' }
  },
  {
    path: '/control',
    name: 'Control',
    component: () => import('@/views/control/index.vue'),
    meta: { title: '设备控制' }
  },
  {
    path: '/devices',
    name: 'Devices',
    component: () => import('@/views/device/index.vue'),
    meta: { title: '设备管理' }
  },
  {
    path: '/devices/:id',
    name: 'DeviceDetail',
    component: () => import('@/views/device/detail.vue'),
    meta: { title: '设备详情' }
  },
  {
    path: '/alarms',
    name: 'Alarms',
    component: () => import('@/views/alarm/index.vue'),
    meta: { title: '告警管理' }
  },
  {
    path: '/alarms/history',
    name: 'AlarmHistory',
    component: () => import('@/views/alarm/history.vue'),
    meta: { title: '告警历史' }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('@/views/chat/index.vue'),
    meta: { title: '智能问答' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
