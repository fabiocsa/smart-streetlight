import request from './request'

export function sendCommand(deviceId, command, source = 'manual') {
  return request.post(`/devices/${deviceId}/control`, { command, source })
}

export function getControlLogs(deviceId) {
  return request.get(`/devices/${deviceId}/control/logs`)
}

export function batchSendCommand(deviceIds, command, source = 'manual') {
  return request.post('/devices/batch/control', { deviceIds, command, source })
}

export function batchSetControlMode(ids, controlMode) {
  return request.put('/devices/batch/control-mode', { ids, controlMode })
}
