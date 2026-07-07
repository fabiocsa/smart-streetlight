import request from './request'

export function getLatestSensorData(deviceId) {
  return request.get(`/devices/${deviceId}/sensor-data/latest`)
}

export function getSensorHistory(deviceId, start, end) {
  return request.get(`/devices/${deviceId}/sensor-data`, {
    params: { start, end }
  })
}

export function getSensorStats(deviceId, start, end) {
  return request.get(`/devices/${deviceId}/sensor-data/stats`, {
    params: { start, end }
  })
}
