import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/devices'
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
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
