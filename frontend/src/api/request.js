import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：自动附加 JWT Token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 清理登录态并跳转登录页 */
function handleLogout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  // 使用 router.push 替代 window.location.hash，保持 Vue Router 状态一致
  router.push('/login').catch(() => {})
}

// 响应拦截器：统一处理错误 + 401 自动跳转登录
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 401) {
        handleLogout()
        return Promise.reject(new Error('请先登录'))
      }
      if (res.code === 403) {
        ElMessage.error(res.msg || '权限不足')
        return Promise.reject(new Error(res.msg || '权限不足'))
      }
      if (res.code === 0) {
        return res.data !== undefined ? res.data : res
      }
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response?.status === 401) {
      handleLogout()
    }
    const msg = error.response?.data?.message || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)
export default request
