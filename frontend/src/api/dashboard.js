import request from './request'

/** 获取总览统计卡片数据 */
export function getStats() {
  return request.get('/dashboard/stats')
}

/** 设备状态分布（饼图） */
export function getDeviceStatusDistribution() {
  return request.get('/dashboard/device-status-distribution')
}

/** 最新传感器数据（各设备最新一条，含完整 data_json） */
export function getLatestSensorData() {
  return request.get('/dashboard/latest-sensor-data')
}

/** 按传感器类型获取最新数据 */
export function getLatestSensorDataByType(sensorType) {
  return request.get(`/dashboard/latest-sensor-data/${sensorType}`)
}

/** 通用传感器趋势：可选设备ID + 指标名(lightIntensity/temperature/humidity/power) + 时间范围(24h/7d/30d) */
export function getSensorTrend(deviceId, metric = 'lightIntensity', range = '24h') {
  const params = { metric, range }
  if (deviceId) params.deviceId = deviceId
  return request.get('/dashboard/sensor-trend', { params })
}

/** 光照趋势（旧接口，保留兼容） */
export function getLightTrend(deviceId, range = '24h') {
  const params = { range }
  if (deviceId) params.deviceId = deviceId
  return request.get('/dashboard/light-trend', { params })
}

/** 多设备趋势对比：传入设备ID数组，返回各设备趋势数据 */
export function getSensorTrendCompare(deviceIds, metric = 'lightIntensity', range = '24h') {
  return request.post('/dashboard/sensor-trend-compare', { deviceIds, metric, range })
}

/** 告警统计（近7日趋势 + 严重级别） */
export function getAlarmStats() {
  return request.get('/dashboard/alarm-stats')
}

/** 近期告警列表 */
export function getRecentAlarms() {
  return request.get('/dashboard/recent-alarms')
}

/** 近期控制日志 */
export function getRecentControls() {
  return request.get('/dashboard/recent-controls')
}
