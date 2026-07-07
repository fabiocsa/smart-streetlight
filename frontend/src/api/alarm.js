import request from './request'

export function getAlarms(params = {}) {
  return request.get('/alarms', { params })
}

export function resolveAlarm(id, resolvedBy) {
  return request.put(`/alarms/${id}/resolve`, { resolvedBy })
}
