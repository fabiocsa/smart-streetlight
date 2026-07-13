import request from './request'

/** 上传知识文档（multipart），超时设为 3 分钟，向量化耗时较长 */
export const uploadFile = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/knowledge/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000
  })
}

/** 获取已上传文件列表 */
export const getFiles = () => request.get('/knowledge/files')

/** 删除指定文件及其向量 */
export const deleteFile = (fileId) => request.delete(`/knowledge/files/${fileId}`)

/** 获取操作日志（可按 fileId 过滤） */
export const getChangelog = (fileId) => {
  const params = fileId ? { fileId } : {}
  return request.get('/knowledge/changelog', { params })
}
