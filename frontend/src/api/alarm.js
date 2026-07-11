import request from './request'

/** 获取告警列表（分页 + 筛选） */
export function getAlarms(params = {}) {
  return request.get('/alarms', { params })
}

/** 处理单个告警 */
export function resolveAlarm(id, data) {
  return request.put(`/alarms/${id}/resolve`, data)
}

/** 批量处理告警 */
export function batchResolve(data) {
  return request.put('/alarms/batch-resolve', data)
}

/** 修改告警处理人 */
export function updateResolvedBy(id, data) {
  return request.put(`/alarms/${id}/resolvedBy`, data)
}

/** 批量修改告警处理人 */
export function batchUpdateResolvedBy(data) {
  return request.put('/alarms/batch-resolvedBy', data)
}

/** 待处理告警数量 */
export function getPendingCount() {
  return request.get('/alarms/pending-count')
}

/** 批量自动分配 */
export function batchAutoAssign() {
  return request.post('/alarms/batch-auto-assign')
}

/** 获取电压正常区间 */
export function getVoltageConfig() {
  return request.get('/alarms/voltage-config')
}

/** 设置电压正常区间 */
export function setVoltageConfig(data) {
  return request.put('/alarms/voltage-config', data)
}

/** 获取温度上限 */
export function getTemperatureConfig() {
  return request.get('/alarms/temperature-config')
}

/** 设置温度上限 */
export function setTemperatureConfig(data) {
  return request.put('/alarms/temperature-config', data)
}

/** 获取功率上限 */
export function getPowerConfig() {
  return request.get('/alarms/power-config')
}

/** 设置功率上限 */
export function setPowerConfig(data) {
  return request.put('/alarms/power-config', data)
}
