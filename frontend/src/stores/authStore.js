import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const username = ref(localStorage.getItem('username') || '')
  const role = ref(localStorage.getItem('role') || '')
  const token = ref(localStorage.getItem('token') || '')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'admin')
  const isOperator = computed(() => role.value === 'operator')
  const isMunicipal = computed(() => role.value === 'municipal')

  async function login(data) {
    const res = await authApi.login(data)
    const info = res?.data || res
    token.value = info.token
    username.value = info.username
    role.value = info.role
    localStorage.setItem('token', info.token)
    localStorage.setItem('username', info.username)
    localStorage.setItem('role', info.role)
    return info
  }

  async function register(data) {
    const res = await authApi.register(data)
    const info = res?.data || res
    token.value = info.token
    username.value = info.username
    role.value = info.role
    localStorage.setItem('token', info.token)
    localStorage.setItem('username', info.username)
    localStorage.setItem('role', info.role)
    return info
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      const res = await authApi.getMe()
      const info = res?.data || res
      if (info) {
        username.value = info.username
        role.value = info.role
        localStorage.setItem('username', info.username)
        localStorage.setItem('role', info.role)
      }
    } catch (e) {
      // 仅 token 过期/无效时登出；网络错误等保留现有登录态
      if (e.response?.status === 401) {
        logout()
      }
    }
  }

  function logout() {
    token.value = ''
    username.value = ''
    role.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
  }

  return { username, role, token, isLoggedIn, isAdmin, isOperator, isMunicipal, login, register, fetchMe, logout }
})
