import request from './request'

export function sendCommand(deviceId, command, source = 'manual') {
  return request.post(`/devices/${deviceId}/control`, { command, source })
}

export function getControlLogs(deviceId) {
  return request.get(`/devices/${deviceId}/control/logs`)
}
