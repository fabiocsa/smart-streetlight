import request from './request'

export const getSessions = () => request.get('/chat/sessions')
export const createSession = (title) => request.post('/chat/sessions', { title })
export const deleteSession = (id) => request.delete(`/chat/sessions/${id}`)
export const renameSession = (id, title) => request.put(`/chat/sessions/${id}`, { title })
export const getMessages = (id) => request.get(`/chat/sessions/${id}/messages`)
export const sendMessage = (sessionId, question) =>
  request.post(`/chat/sessions/${sessionId}/messages`, { question })
