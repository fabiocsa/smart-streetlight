import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '仪表盘', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/devices',
    name: 'DeviceList',
    component: () => import('../views/DeviceList.vue'),
    meta: { title: '设备管理', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/devices/:id',
    name: 'DeviceDetail',
    component: () => import('../views/DeviceDetail.vue'),
    meta: { title: '设备详情', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/sensors',
    name: 'SensorList',
    component: () => import('../views/SensorList.vue'),
    meta: { title: '传感器查看', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/chat',
    name: 'ChatView',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '智能问答', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/light-trend',
    name: 'LightTrend',
    component: () => import('../views/LightTrend.vue'),
    meta: { title: '历史光照趋势', roles: ['admin', 'manager', 'municipal', 'operator'] }
  },
  {
    path: '/alarms',
    name: 'AlarmList',
    component: () => import('../views/AlarmList.vue'),
    meta: { title: '告警管理', roles: ['admin', 'manager', 'operator'] }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 路由守卫：未登录跳转登录页，无权限跳转 Dashboard
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role')

  // 公开页面直接放行
  if (to.meta.public) {
    return next()
  }

  // 未登录 → 跳转登录
  if (!token) {
    return next('/login')
  }

  // 检查角色权限
  const allowedRoles = to.meta.roles
  if (allowedRoles && !allowedRoles.includes(role)) {
    // 无权限 → 回退到 Dashboard
    return next('/dashboard')
  }

  next()
})

export default router
