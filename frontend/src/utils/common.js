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

// 灯光状态中文标签（离线时显示"未知"）
export function lightStatusLabel(device) {
  if (!device) return '-'
  if (device.status === 'offline') return '未知'
  if (device.lightStatus === 'unknown') return '未知'
  return device.lightStatus === 'on' ? '开启' : '关闭'
}

// 灯光状态 Element Plus Tag type（离线/未知时灰色 info）
export function lightStatusTagType(device) {
  if (!device) return 'info'
  if (device.status === 'offline' || device.lightStatus === 'unknown') return 'info'
  return device.lightStatus === 'on' ? 'warning' : 'info'
}

// 灯是否确定在开启状态（离线/未知返回 false）
export function isLightOn(device) {
  if (!device) return false
  if (device.status === 'offline' || device.lightStatus === 'unknown') return false
  return device.lightStatus === 'on'
}
