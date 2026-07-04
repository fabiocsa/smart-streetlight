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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
