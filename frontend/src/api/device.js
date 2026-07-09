import request from './request'

export function getDevices() {
  return request.get('/devices')
}

export function getDevice(id) {
  return request.get(`/devices/${id}`)
}

export function createDevice(data) {
  return request.post('/devices', data)
}

export function updateDevice(id, data) {
  return request.put(`/devices/${id}`, data)
}

export function deleteDevice(id) {
  return request.delete(`/devices/${id}`)
}

// 设备-传感器绑定（v2: 设备绑定传感器）

export function getDeviceSensors(deviceId) {
  return request.get(`/devices/${deviceId}/sensors`)
}

export function bindSensor(deviceId, sensorId) {
  return request.post(`/devices/${deviceId}/bind-sensor`, { sensorId })
}

export function unbindSensor(deviceId, sensorId) {
  return request.post(`/devices/${deviceId}/unbind-sensor/${sensorId}`)
}
