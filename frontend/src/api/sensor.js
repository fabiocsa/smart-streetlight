import request from './request'

// 独立传感器 API（v2: 传感器不再嵌套在设备路径下）

export function getAllSensors() {
  return request.get('/sensors')
}

export function getUnboundSensors() {
  return request.get('/sensors/unbound')
}

export function getSensor(id) {
  return request.get(`/sensors/${id}`)
}

export function createSensor(data) {
  return request.post('/sensors', data)
}

export function updateSensor(id, data) {
  return request.put(`/sensors/${id}`, data)
}

export function deleteSensor(id) {
  return request.delete(`/sensors/${id}`)
}

export function updateFrequency(id, data) {
  return request.put(`/sensors/${id}/frequency`, data)
}
