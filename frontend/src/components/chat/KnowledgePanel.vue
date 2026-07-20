<template>
  <div class="knowledge-panel">
    <div class="upload-section">
      <el-upload ref="uploadRef" class="upload-area" drag multiple :auto-upload="false" :limit="20"
        :accept="allowedTypes" :show-file-list="false" :on-change="handleFileChange" :on-exceed="handleExceed">
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">
          <p>拖拽文件到此处或 <em>点击上传</em></p>
          <p class="upload-hint">支持 .txt .md .pdf .docx，单文件 ≤ 15MB</p>
        </div>
      </el-upload>
      <div v-if="pendingFiles.length > 0" class="pending-list">
        <div v-for="(f, i) in pendingFiles" :key="i" class="pending-item">
          <span class="pending-name">{{ f.name }}</span>
          <span class="pending-size">{{ formatSize(f.size) }}</span>
          <el-icon class="pending-remove" @click="removePending(i)"><Close /></el-icon>
        </div>
        <el-button type="primary" size="small" @click="uploadAll" :loading="uploading">
          全部上传 ({{ pendingFiles.length }})
        </el-button>
      </div>
    </div>
    <div class="file-list-header">
      <span>已上传文件 ({{ files.length }})</span>
      <el-button text size="small" @click="loadFiles" :loading="loading"><el-icon><Refresh /></el-icon></el-button>
    </div>
    <div class="file-list" v-loading="loading">
      <div v-for="f in files" :key="f.fileId" class="file-item">
        <div class="file-icon"><el-icon :size="20"><Document /></el-icon></div>
        <div class="file-info">
          <div class="file-name" :title="f.fileName">{{ f.fileName }}</div>
          <div class="file-meta">
            <el-tag size="small" type="info">{{ f.fileType }}</el-tag>
            <span class="file-chunks">{{ f.chunkCount }} 块</span>
          </div>
        </div>
        <el-popconfirm title="确定删除此文件及所有向量？" @confirm="handleDelete(f.fileId)">
          <template #reference>
            <el-button text type="danger" size="small"><el-icon><Delete /></el-icon></el-button>
          </template>
        </el-popconfirm>
      </div>
      <div v-if="!loading && files.length === 0" class="empty-files">暂无文件，请上传知识文档</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document, Delete, Refresh, Close } from '@element-plus/icons-vue'
import { uploadFile, getFiles, deleteFile } from '../../api/knowledge'

const ALLOWED_EXTENSIONS = ['.txt', '.md', '.markdown', '.pdf', '.docx']
const MAX_SIZE = 15 * 1024 * 1024
const allowedTypes = ALLOWED_EXTENSIONS.join(',')
const files = ref([])
const pendingFiles = ref([])
const uploading = ref(false)
const loading = ref(false)
const uploadRef = ref(null)

onMounted(() => loadFiles())

function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
function handleFileChange(file) {
  const ext = '.' + file.name.split('.').pop()?.toLowerCase()
  if (!ALLOWED_EXTENSIONS.includes(ext)) { ElMessage.warning(`不支持的文件类型: ${ext}`); return }
  if (file.size > MAX_SIZE) { ElMessage.warning(`文件过大: ${file.name}（限制 15MB）`); return }
  pendingFiles.value.push(file)
}
function removePending(index) { pendingFiles.value.splice(index, 1) }
function handleExceed() { ElMessage.warning('单次最多上传 20 个文件') }
async function uploadAll() {
  if (pendingFiles.value.length === 0) return
  uploading.value = true
  let success = 0
  for (const f of pendingFiles.value) {
    try { await uploadFile(f.raw || f); success++ } catch (e) { ElMessage.error(`上传失败: ${f.name}`) }
  }
  uploading.value = false; pendingFiles.value = []
  if (success > 0) { ElMessage.success(`成功导入 ${success} 个文件`); await loadFiles() }
}
async function loadFiles() {
  loading.value = true
  try { files.value = (await getFiles()) || [] } catch { /* */ }
  finally { loading.value = false }
}
async function handleDelete(fileId) {
  try { await deleteFile(fileId); ElMessage.success('文件已删除'); await loadFiles() }
  catch { ElMessage.error('删除失败') }
}
</script>

<style scoped>
.knowledge-panel { display: flex; flex-direction: column; height: 100%; }
.upload-section { padding: 12px; border-bottom: 1px solid var(--el-border-color-light); }
.upload-area { width: 100%; }
.upload-area :deep(.el-upload-dragger) { padding: 16px; }
.upload-icon { font-size: 28px; color: var(--blue); margin-bottom: 4px; }
.upload-text p { font-size: 12px; color: var(--text-secondary); margin: 0; }
.upload-text em { color: var(--blue); font-style: normal; }
.upload-hint { color: var(--text-muted) !important; font-size: 11px !important; margin-top: 4px !important; }
.pending-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.pending-item { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 4px 8px; background: rgba(59,130,246,0.08); border-radius: 4px; }
.pending-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-primary); }
.pending-size { color: var(--text-muted); flex-shrink: 0; }
.pending-remove { cursor: pointer; color: var(--text-muted); flex-shrink: 0; }
.pending-remove:hover { color: var(--red); }
.file-list-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 12px 4px; font-size: 13px; font-weight: 600; color: var(--text-primary); }
.file-list { flex: 1; overflow-y: auto; padding: 0 8px 8px; }
.file-item { display: flex; align-items: center; gap: 8px; padding: 8px; border-radius: 6px; margin-bottom: 2px; transition: background 0.15s; }
.file-item:hover { background: var(--el-fill-color-light); }
.file-icon { color: var(--blue); flex-shrink: 0; }
.file-info { flex: 1; min-width: 0; }
.file-name { font-size: 12px; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-meta { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.file-chunks { font-size: 11px; color: var(--text-muted); }
.empty-files { text-align: center; color: var(--text-muted); font-size: 12px; padding: 24px 0; }
</style>
