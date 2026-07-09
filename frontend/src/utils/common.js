/**
 * 公共工具函数
 */

// 传感器类型中文映射
const SENSOR_TYPE_MAP = {
  light: '光照',
  temperature: '温度',
  humidity: '湿度',
  power: '功率'
}

// 传感器类型 Element Plus Tag 颜色映射
const SENSOR_TYPE_TAG_MAP = {
  light: '',
  temperature: 'danger',
  humidity: 'info',
  power: 'warning'
}

// 格式化时间为中文格式
export function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

// 传感器类型转中文标签
export function typeLabel(t) {
  return SENSOR_TYPE_MAP[t] || t
}

// 传感器类型转 Element Plus Tag type
export function sensorTypeTag(t) {
  return SENSOR_TYPE_TAG_MAP[t] || 'info'
}

// 搜索过滤时重置分页
export function resetPage(ref) {
  ref.value = 1
}

// 防抖函数
export function debounce(fn, delay = 300) {
  let timer = null
  return function (...args) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => fn.apply(this, args), delay)
  }
}

// 简易深度克隆（用于列表局部更新）
export function clone(obj) {
  return JSON.parse(JSON.stringify(obj))
}
