<template>
  <div class="sidebar">
    <div class="sidebar-header">
      <div class="tab-row">
        <span
          :class="['tab-item', { active: activeTab === 'chat' }]"
          @click="$emit('update:activeTab', 'chat')"
        >对话</span>
        <span
          v-if="!authStore.isMunicipal"
          :class="['tab-item', { active: activeTab === 'knowledge' }]"
          @click="$emit('update:activeTab', 'knowledge')"
        >知识库</span>
      </div>
      <el-button
        v-if="activeTab === 'chat'"
        type="primary" size="small" @click="$emit('new')"
      >
        <el-icon><Plus /></el-icon> 新建
      </el-button>
    </div>

    <!-- 对话列表 -->
    <div v-if="activeTab === 'chat'" class="session-list">
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

    <!-- 知识库面板 -->
    <KnowledgePanel v-if="activeTab === 'knowledge'" />
  </div>
</template>

<script setup>
import { Plus, Delete } from '@element-plus/icons-vue'
import KnowledgePanel from './KnowledgePanel.vue'
import { useAuthStore } from '../../stores/authStore'

const authStore = useAuthStore()

defineProps({
  sessions: { type: Array, default: () => [] },
  currentId: { type: [Number, String], default: null },
  activeTab: { type: String, default: 'chat' }
})

defineEmits(['new', 'select', 'delete', 'update:activeTab'])

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
  display: flex; flex-direction: column; height: 100%;
  background: var(--el-fill-color-light);
}
.sidebar-header {
  padding: 12px 12px 0;
  border-bottom: 1px solid rgba(51,65,85,0.3);
}
.tab-row {
  display: flex;
  gap: 0;
  margin-bottom: 8px;
}
.tab-item {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  font-size: 13px; font-weight: 500; color: var(--text-muted);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}
.tab-item:hover { color: var(--blue); }
.tab-item.active { color: var(--blue); border-bottom-color: var(--blue); }

.sidebar-header .el-button {
  margin-bottom: 8px;
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
.session-item:hover { background: var(--el-fill-color-light); }
.session-item.active { background: rgba(59,130,246,0.1); }
.session-title { font-size: 13px; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-bottom: 4px; }
.session-meta { display: flex; align-items: center; justify-content: space-between; }
.session-time { font-size: 11px; color: var(--text-muted); }
.delete-btn { opacity: 0; transition: opacity 0.15s; padding: 2px; }
.session-item:hover .delete-btn { opacity: 1; }
.empty-sessions { text-align: center; color: var(--text-muted);
  font-size: 13px;
  padding: 32px 0;
}
</style>
