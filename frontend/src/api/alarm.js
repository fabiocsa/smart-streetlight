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

/** 待处理告警数量 */
export function getPendingCount() {
  return request.get('/alarms/pending-count')
}
