import request from './request'

/** 获取总览统计卡片数据 */
export function getStats() {
  return request.get('/dashboard/stats')
}

/** 最新传感器数据（各设备最新一条，含完整 data_json） */
export function getLatestSensorData() {
  return request.get('/dashboard/latest-sensor-data')
}

/** 通用传感器趋势 */
export function getSensorTrend(deviceId, metric = 'illuminance', range = '24h') {
  const params = { metric, range }
  if (deviceId) params.deviceId = deviceId
  return request.get('/dashboard/sensor-trend', { params })
}

/** 多设备趋势对比 */
export function getSensorTrendCompare(deviceIds, metric = 'illuminance', range = '24h') {
  return request.post('/dashboard/sensor-trend-compare', { deviceIds, metric, range })
}

/** 近期告警列表 */
export function getRecentAlarms() {
  return request.get('/dashboard/recent-alarms')
}

/** 近期控制日志 */
export function getRecentControls() {
  return request.get('/dashboard/recent-controls')
}
