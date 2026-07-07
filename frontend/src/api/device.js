import request from './request'

export function getDevices() {
  return request.get('/devices')
}

export function getDeviceById(id) {
  return request.get(`/devices/${id}`)
}

export function addDevice(data) {
  return request.post('/devices', data)
}

export function updateDevice(id, data) {
  return request.put(`/devices/${id}`, data)
}

export function deleteDevice(id) {
  return request.delete(`/devices/${id}`)
}

export function setControlMode(id, controlMode) {
  return request.put(`/devices/${id}/control-mode`, { controlMode })
}

export function setThreshold(id, thresholdOn, thresholdOff) {
  return request.put(`/devices/${id}/threshold`, { thresholdOn, thresholdOff })
}
