import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '仪表盘' }
  },
  {
    path: '/devices',
    name: 'DeviceList',
    component: () => import('../views/DeviceList.vue'),
    meta: { title: '设备管理' }
  },
  {
    path: '/devices/:id',
    name: 'DeviceDetail',
    component: () => import('../views/DeviceDetail.vue'),
    meta: { title: '设备详情' }
  },
  {
    path: '/sensors',
    name: 'SensorList',
    component: () => import('../views/SensorList.vue'),
    meta: { title: '传感器管理' }
  },
  {
    path: '/chat',
    name: 'ChatView',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '智能问答' }
  },
  {
    path: '/light-trend',
    name: 'LightTrend',
    component: () => import('../views/LightTrend.vue'),
    meta: { title: '历史光照趋势' }
  },
  {
    path: '/alarms',
    name: 'AlarmList',
    component: () => import('../views/AlarmList.vue'),
    meta: { title: '告警管理' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
