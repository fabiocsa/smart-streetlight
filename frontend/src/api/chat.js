import request from './request'

export function sendMessage(question) {
  return request.post('/chat', { question })
}
