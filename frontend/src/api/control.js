import request from './request'

/** 单设备控制（开关/亮度） — 8s 超时，MQTT 下发应该很快 */
export function sendControl(deviceId, data) {
  return request.post(`/devices/${deviceId}/control`, data, { timeout: 8000 })
}

/** 批量设备控制 */
export function sendBatchControl(data) {
  return request.post('/devices/batch/control', data, { timeout: 10000 })
}

/** 查询设备控制日志 */
export function getControlLogs(deviceId, params = {}) {
  return request.get(`/devices/${deviceId}/control-logs`, { params })
}

/** 设置设备控制模式 (auto/manual) */
export function setControlMode(id, data) {
  return request.put(`/devices/${id}/control-mode`, data, { timeout: 8000 })
}

/** 设置光照阈值 */
export function setThreshold(id, data) {
  return request.put(`/devices/${id}/threshold`, data, { timeout: 8000 })
}

/** 批量设置光照阈值 */
export function setBatchThreshold(data) {
  return request.put('/devices/batch/threshold', data, { timeout: 10000 })
}

/** 设置传感器决策策略 */
export function setSensorStrategy(id, data) {
  return request.put(`/devices/${id}/sensor-strategy`, data, { timeout: 8000 })
}
