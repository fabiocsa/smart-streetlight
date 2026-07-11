import request from './request'

/** 单设备控制（开关/亮度） */
export function sendControl(deviceId, data) {
  return request.post(`/devices/${deviceId}/control`, data)
}

/** 批量设备控制 */
export function sendBatchControl(data) {
  return request.post('/devices/batch/control', data)
}

/** 查询设备控制日志 */
export function getControlLogs(deviceId, params = {}) {
  return request.get(`/devices/${deviceId}/control-logs`, { params })
}

/** 设置设备控制模式 (auto/manual) */
export function setControlMode(id, data) {
  return request.put(`/devices/${id}/control-mode`, data)
}

/** 设置光照阈值 */
export function setThreshold(id, data) {
  return request.put(`/devices/${id}/threshold`, data)
}

/** 批量设置光照阈值 */
export function setBatchThreshold(data) {
  return request.put('/devices/batch/threshold', data)
}
