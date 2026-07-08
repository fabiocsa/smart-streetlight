<template>
  <div class="dashboard">
    <div class="page-header">
      <h2>仪表盘</h2>
      <el-button type="primary" @click="refreshAll" :loading="loading">
        <el-icon><Refresh /></el-icon> 刷新数据
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-cards">
      <el-col :xs="12" :sm="8" :md="4" v-for="card in statCards" :key="card.label">
        <el-card shadow="hover" :body-style="{ padding: '20px' }">
          <div class="stat-card">
            <div class="stat-icon" :style="{ background: card.color }">
              <el-icon :size="24"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ card.value }}</div>
              <div class="stat-label">{{ card.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 设备状态分布 -->
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><strong>设备状态分布</strong></template>
          <v-chart :option="deviceStatusOption" autoresize style="height: 280px" />
        </el-card>
      </el-col>

      <!-- 24h光照趋势 -->
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="chart-header">
              <strong>24小时光照趋势</strong>
              <el-select v-model="lightTrendDevice" placeholder="全部设备" clearable size="small" style="width: 160px" @change="loadLightTrend">
                <el-option label="全部设备" value="" />
                <el-option v-for="d in devices" :key="d.deviceId" :label="d.name" :value="d.deviceId" />
              </el-select>
            </div>
          </template>
          <v-chart :option="lightTrendOption" autoresize style="height: 280px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 第二行 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 近7日告警趋势 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近7日告警趋势</strong></template>
          <v-chart :option="alarmTrendOption" autoresize style="height: 260px" />
        </el-card>
      </el-col>

      <!-- 告警级别分布 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>本月告警级别分布</strong></template>
          <v-chart :option="alarmSeverityOption" autoresize style="height: 260px" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 第三行 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 近期告警 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近期告警</strong></template>
          <el-table :data="recentAlarms" size="small" max-height="300" style="width: 100%">
            <el-table-column prop="deviceId" label="设备" width="90" />
            <el-table-column prop="content" label="告警内容" min-width="180" show-overflow-tooltip />
            <el-table-column label="级别" width="70">
              <template #default="{ row }">
                <el-tag :type="severityTag(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.status === 'PENDING' ? 'danger' : 'info'" size="small">
                  {{ row.status === 'PENDING' ? '待处理' : '已处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 近期控制日志 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><strong>近期控制日志</strong></template>
          <el-table :data="recentControls" size="small" max-height="300" style="width: 100%">
            <el-table-column prop="deviceId" label="设备" width="90" />
            <el-table-column label="指令" width="70">
              <template #default="{ row }">
                <el-tag :type="row.command === 'on' ? 'warning' : 'info'" size="small">
                  {{ row.command === 'on' ? '开灯' : '关灯' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="70">
              <template #default="{ row }">
                {{ row.source === 'auto' ? '自动' : '手动' }}
              </template>
            </el-table-column>
            <el-table-column label="结果" width="70">
              <template #default="{ row }">
                <el-tag :type="row.result === 'success' ? 'success' : 'danger'" size="small">
                  {{ row.result === 'success' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 各设备最新光照 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header><strong>设备最新光照数据</strong></template>
      <el-table :data="latestSensorData" size="small" max-height="280" style="width: 100%">
        <el-table-column prop="deviceId" label="设备ID" width="120" />
        <el-table-column label="光照强度" min-width="160">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 8px">
              <el-progress
                :percentage="Math.min(row.lightIntensity / 8, 100)"
                :color="lightColor(row.lightIntensity)"
                :stroke-width="16"
                style="flex: 1"
              />
              <span style="font-weight: 600; min-width: 60px">{{ row.lightIntensity }} Lux</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="上报时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.reportedAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import * as dashboardApi from '../api/dashboard'
import { useDeviceStore } from '../store/device'
import { formatTime } from '../utils/common'

// 注册 ECharts 组件
use([PieChart, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, CanvasRenderer])

const deviceStore = useDeviceStore()
const loading = ref(false)

// 数据
const stats = ref({})
const devices = ref([])
const latestSensorData = ref([])
const lightTrend = ref({ hours: [], values: [] })
const alarmStats = ref({ days: [], dailyCounts: [], criticalCount: 0, warningCount: 0, infoCount: 0 })
const recentAlarms = ref([])
const recentControls = ref([])
const lightTrendDevice = ref('')

// 统计卡片
const statCards = computed(() => [
  { label: '设备总数', value: stats.value.totalDevices ?? '-', icon: 'Cpu', color: '#409EFF' },
  { label: '在线设备', value: stats.value.onlineDevices ?? '-', icon: 'CircleCheck', color: '#67C23A' },
  { label: '离线设备', value: stats.value.offlineDevices ?? '-', icon: 'CircleClose', color: '#F56C6C' },
  { label: '已开灯', value: stats.value.lightsOn ?? '-', icon: 'Sunny', color: '#E6A23C' },
  { label: '待处理告警', value: stats.value.pendingAlarms ?? '-', icon: 'Bell', color: '#F56C6C' },
  { label: '今日数据量', value: stats.value.todayDataPoints ?? '-', icon: 'DataLine', color: '#909399' }
])

// -- 设备状态饼图 --
const deviceStatusOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['45%', '75%'],
    center: ['50%', '45%'],
    avoidLabelOverlap: false,
    itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
    label: { show: true, formatter: '{b}: {c}' },
    data: [
      { name: '在线', value: stats.value.onlineDevices || 0, itemStyle: { color: '#67C23A' } },
      { name: '离线', value: stats.value.offlineDevices || 0, itemStyle: { color: '#F56C6C' } },
      { name: '已开灯', value: stats.value.lightsOn || 0, itemStyle: { color: '#E6A23C' } },
      { name: '已关灯', value: stats.value.lightsOff || 0, itemStyle: { color: '#909399' } }
    ]
  }]
}))

// -- 24h 光照趋势 --
const lightTrendOption = computed(() => ({
  tooltip: { trigger: 'axis', formatter: '{b}<br/>光照: {c} Lux' },
  grid: { left: 50, right: 20, top: 10, bottom: 30 },
  xAxis: {
    type: 'category', data: lightTrend.value.hours || [],
    axisLabel: { rotate: 45, fontSize: 11 }
  },
  yAxis: { type: 'value', name: 'Lux' },
  series: [{
    type: 'line',
    data: lightTrend.value.values || [],
    smooth: true,
    areaStyle: {
      color: {
        type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [
          { offset: 0, color: 'rgba(64,158,255,0.35)' },
          { offset: 1, color: 'rgba(64,158,255,0.02)' }
        ]
      }
    },
    lineStyle: { color: '#409EFF', width: 2 },
    itemStyle: { color: '#409EFF' }
  }]
}))

// -- 告警趋势 --
const alarmTrendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, top: 10, bottom: 30 },
  xAxis: { type: 'category', data: alarmStats.value.days || [] },
  yAxis: { type: 'value', name: '告警数', minInterval: 1 },
  series: [{
    type: 'bar',
    data: alarmStats.value.dailyCounts || [],
    itemStyle: { color: '#F56C6C', borderRadius: [4, 4, 0, 0] }
  }]
}))

// -- 告警级别饼图 --
const alarmSeverityOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: '70%',
    center: ['50%', '45%'],
    itemStyle: { borderRadius: 3, borderColor: '#fff', borderWidth: 2 },
    label: { formatter: '{b}: {c} 条' },
    data: [
      { name: '严重', value: alarmStats.value.criticalCount || 0, itemStyle: { color: '#F56C6C' } },
      { name: '警告', value: alarmStats.value.warningCount || 0, itemStyle: { color: '#E6A23C' } },
      { name: '提示', value: alarmStats.value.infoCount || 0, itemStyle: { color: '#409EFF' } }
    ]
  }]
}))

// -- 辅助方法 --
function severityTag(sev) {
  const map = { CRITICAL: 'danger', WARNING: 'warning', INFO: 'info' }
  return map[sev] || 'info'
}
function severityLabel(sev) {
  const map = { CRITICAL: '严重', WARNING: '警告', INFO: '提示' }
  return map[sev] || sev
}
function lightColor(val) {
  if (val > 150) return '#F56C6C'
  if (val > 80) return '#E6A23C'
  if (val > 30) return '#409EFF'
  return '#67C23A'
}

// -- 数据加载 --
async function loadStats() {
  try { stats.value = await dashboardApi.getStats() } catch { /* 统一错误处理 */ }
}
async function loadLatestSensorData() {
  try { latestSensorData.value = await dashboardApi.getLatestSensorData() } catch { /* */ }
}
async function loadLightTrend() {
  try { lightTrend.value = await dashboardApi.getLightTrend(lightTrendDevice.value || undefined) } catch { /* */ }
}
async function loadAlarmStats() {
  try { alarmStats.value = await dashboardApi.getAlarmStats() } catch { /* */ }
}
async function loadRecentAlarms() {
  try { recentAlarms.value = await dashboardApi.getRecentAlarms() } catch { /* */ }
}
async function loadRecentControls() {
  try { recentControls.value = await dashboardApi.getRecentControls() } catch { /* */ }
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.all([
      loadStats(), loadLatestSensorData(),
      loadLightTrend(), loadAlarmStats(),
      loadRecentAlarms(), loadRecentControls(),
      deviceStore.fetchAll()
    ])
    devices.value = deviceStore.devices
    ElMessage.success('数据已刷新')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await deviceStore.fetchAll()
  devices.value = deviceStore.devices
  await Promise.all([
    loadStats(), loadLatestSensorData(),
    loadLightTrend(), loadAlarmStats(),
    loadRecentAlarms(), loadRecentControls()
  ])
})
</script>

<style scoped>
.dashboard { padding-bottom: 24px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.page-header h2 { font-size: 20px; font-weight: 600; }

.stat-cards { margin-bottom: 0; }
.stat-card { display: flex; align-items: center; gap: 16px; }
.stat-icon {
  width: 52px; height: 52px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center; color: #fff;
  flex-shrink: 0;
}
.stat-info { flex: 1; min-width: 0; }
.stat-value { font-size: 26px; font-weight: 700; line-height: 1.2; }
.stat-label { font-size: 13px; color: #909399; margin-top: 2px; }

.chart-header { display: flex; justify-content: space-between; align-items: center; }
</style>
