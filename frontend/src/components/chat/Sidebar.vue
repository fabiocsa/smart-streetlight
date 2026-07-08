<template>
  <div class="sidebar">
    <div class="sidebar-header">
      <h3>对话记录</h3>
      <el-button type="primary" size="small" @click="$emit('new')">
        <el-icon><Plus /></el-icon> 新建
      </el-button>
    </div>
    <div class="session-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        :class="['session-item', { active: s.id === currentId }]"
        @click="$emit('select', s.id)"
      >
        <div class="session-title">{{ s.title }}</div>
        <div class="session-meta">
          <span class="session-time">{{ formatTime(s.updatedAt) }}</span>
          <el-popconfirm
            title="确定删除此对话？"
            confirm-button-text="删除"
            @confirm.stop="$emit('delete', s.id)"
            @click.stop
          >
            <template #reference>
              <el-button text size="small" class="delete-btn" @click.stop>
                <el-icon><Delete /></el-icon>
              </el-button>
            </template>
          </el-popconfirm>
        </div>
      </div>
      <div v-if="sessions.length === 0" class="empty-sessions">
        暂无对话记录
      </div>
    </div>
  </div>
</template>

<script setup>
import { Plus, Delete } from '@element-plus/icons-vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  currentId: { type: [Number, String], default: null }
})

defineEmits(['new', 'select', 'delete'])

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const diff = now - d
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
  return d.toLocaleDateString()
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f7fa;
}
.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid #e4e7ed;
}
.sidebar-header h3 {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.session-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s;
}
.session-item:hover { background: #e8eaed; }
.session-item.active { background: #d9ecff; }
.session-title {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}
.session-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.session-time {
  font-size: 11px;
  color: #909399;
}
.delete-btn {
  opacity: 0;
  transition: opacity 0.15s;
  padding: 2px;
}
.session-item:hover .delete-btn { opacity: 1; }
.empty-sessions {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 32px 0;
}
</style>
