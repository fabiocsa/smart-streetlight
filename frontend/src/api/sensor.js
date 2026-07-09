import request from './request'

export function getSensors(deviceId) {
  return request.get(`/devices/${deviceId}/sensors`)
}

export function getSensor(deviceId, id) {
  return request.get(`/devices/${deviceId}/sensors/${id}`)
}

export function createSensor(deviceId, data) {
  return request.post(`/devices/${deviceId}/sensors`, data)
}

export function updateSensor(deviceId, id, data) {
  return request.put(`/devices/${deviceId}/sensors/${id}`, data)
}

export function deleteSensor(deviceId, id) {
  return request.delete(`/devices/${deviceId}/sensors/${id}`)
}

export function updateFrequency(deviceId, id, data) {
  return request.put(`/devices/${deviceId}/sensors/${id}/frequency`, data)
}

export function unbindSensor(deviceId, id) {
  return request.post(`/devices/${deviceId}/sensors/${id}/unbind`)
}

export function syncToMock(deviceId) {
  return request.post(`/devices/${deviceId}/sensors/sync-to-mock`)
}
