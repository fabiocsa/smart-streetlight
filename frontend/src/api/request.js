import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 响应拦截器：统一处理错误
request.interceptors.response.use(
  (response) => {
    // 后端 Result 包装 { code, message, data }
    const res = response.data
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 200 || res.code === 0) {
        return res.data !== undefined ? res.data : res
      }
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const msg = error.response?.data?.message || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
