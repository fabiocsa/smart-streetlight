import request from './request'

/** GET /handlers — 获取处理人列表 */
export function getHandlers() {
  return request.get('/handlers')
}

/** POST /handlers — 添加处理人 */
export function createHandler(data) {
  return request.post('/handlers', data)
}

/** PUT /handlers/{id} — 更新处理人 */
export function updateHandler(id, data) {
  return request.put(`/handlers/${id}`, data)
}

/** DELETE /handlers/{id} — 删除处理人 */
export function deleteHandler(id) {
  return request.delete(`/handlers/${id}`)
}

/** PUT /handlers/{id}/release — 释放处理人 */
export function releaseHandler(id) {
  return request.put(`/handlers/${id}/release`)
}

/** GET /handlers/mode — 获取分配模式 */
export function getAssignmentMode() {
  return request.get('/handlers/mode')
}

/** PUT /handlers/mode — 切换分配模式 */
export function setAssignmentMode(data) {
  return request.put('/handlers/mode', data)
}

/** PUT /alarms/{alarmId}/assign/{handlerId} — 手动分配处理人 */
export function assignHandler(alarmId, handlerId) {
  return request.put(`/alarms/${alarmId}/assign/${handlerId}`)
}

/** POST /alarms/batch-auto-assign — 批量自动分配所有待处理告警 */
export function batchAutoAssign() {
  return request.post('/alarms/batch-auto-assign')
}
