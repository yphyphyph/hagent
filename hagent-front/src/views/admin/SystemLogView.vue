<template>
  <div class="system-log-page">
    <!-- Trace 看板：轻量聚合（不建重型 Run/Node 双表）— 按 trace_id 分组展示 latency / channel / 条数，前端对 system_log.trace_id 分组；点击 traceId 可经 GET /admin/system-log/page?traceId=... 下钻 -->
    <n-card class="trace-board-card" :bordered="true" size="small">
      <template #header>
        <span class="trace-board-title">Trace 看板</span>
        <span class="trace-board-desc">轻量聚合 · 按 trace_id 分组 · latency 来自 system_log.cost_time_ms · channel/topScore 来自 RagTraceAspect 日志（traceId/channel/latency/topScore）按 traceId 关联</span>
      </template>
      <template #header-extra>
        <n-space :size="8" align="center">
          <span class="trace-board-count">当前页 {{ traceBoardGroups.length }} 个 Trace</span>
          <n-button v-if="filters.traceId" size="tiny" quaternary @click="clearTraceFilter">清除 Trace 筛选</n-button>
        </n-space>
      </template>
      <div v-if="traceBoardGroups.length === 0" class="trace-board-empty">
        <n-empty description="暂无 Trace 聚合（当前页无 trace_id 或未检索）" size="small" style="padding: 12px 0" />
      </div>
      <div v-else class="trace-board-grid">
        <div
          v-for="g in traceBoardGroups"
          :key="g.traceId"
          class="trace-card"
          :class="{ 'is-active': filters.traceId === g.traceId }"
          @click="filterByTrace(g.traceId)"
        >
          <div class="trace-card__head">
            <span class="trace-card__id mono" :title="g.traceId">{{ g.shortId }}</span>
            <n-tag size="tiny" :bordered="false" :type="g.successRate === 1 ? 'success' : g.successRate === 0 ? 'error' : 'default'">{{ g.count }} 条</n-tag>
          </div>
          <div class="trace-card__meta">
            <span>latency: <b>{{ g.maxLatency }}ms</b> / avg {{ g.avgLatency }}ms</span>
            <span>channel: <b>{{ g.channel || '—' }}</b></span>
          </div>
          <div class="trace-card__meta">
            <span>topScore: <b>{{ g.topScoreText }}</b></span>
            <span class="trace-card__time">{{ g.timeRange }}</span>
          </div>
        </div>
      </div>
      <div v-if="traceBoardGroups.length > 0" class="trace-board-hint">点击卡片按 traceId 下钻（GET /admin/system-log/page?traceId=...），RagTraceAspect 的 channel/latency/topScore 日志按 traceId 关联观测</div>
    </n-card>

    <!-- 筛选区 -->
    <n-card class="log-filter-card" :bordered="true">
      <n-form :model="filters" inline label-placement="left" :show-feedback="false" class="log-filter-form">
        <n-form-item label="模块">
          <n-input
            v-model:value="filters.module"
            placeholder="模块名"
            clearable
            style="width: 140px"
            @keydown.enter="onSearch"
          />
        </n-form-item>
        <n-form-item label="操作类型">
          <n-select
            v-model:value="filters.operationType"
            placeholder="全部"
            clearable
            :options="operationTypeOptions"
            style="width: 140px"
          />
        </n-form-item>
        <n-form-item label="用户名">
          <n-input
            v-model:value="filters.username"
            placeholder="用户名"
            clearable
            style="width: 130px"
            @keydown.enter="onSearch"
          />
        </n-form-item>
        <n-form-item label="结果">
          <n-select
            v-model:value="filters.success"
            placeholder="全部"
            clearable
            :options="successOptions"
            style="width: 120px"
          />
        </n-form-item>
        <n-form-item label="traceId">
          <n-input
            v-model:value="filters.traceId"
            placeholder="traceId 精确"
            clearable
            style="width: 200px"
            @keydown.enter="onSearch"
          />
        </n-form-item>
        <n-form-item label="时间范围">
          <n-date-picker
            v-model:value="filters.dateRange"
            type="daterange"
            clearable
            style="width: 260px"
            :actions="['clear', 'confirm']"
          />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="onSearch">查询</n-button>
            <n-button @click="onReset">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>
    </n-card>

    <!-- 表格区 -->
    <n-card class="log-table-card" :bordered="true" title="操作日志">
      <template #header-extra>
        <n-popconfirm @positive-click="onClean">
          <template #trigger>
            <n-button size="small" type="error" :loading="cleaning">清理</n-button>
          </template>
          确认清理 30 天前的日志吗？此操作不可恢复。
        </n-popconfirm>
      </template>

      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :row-key="(row: SystemLog) => row.id"
        :bordered="false"
        :pagination="false"
      >
        <template #empty>
          <n-empty description="暂无日志数据" style="padding: 24px 0" />
        </template>
      </n-data-table>

      <!-- 分页 -->
      <div class="log-pagination">
        <n-pagination
          v-model:page="pagination.page"
          v-model:page-size="pagination.pageSize"
          :item-count="pagination.total"
          :page-sizes="[10, 20, 50]"
          show-size-picker
          show-quick-jumper
          @update:page="loadData"
          @update:page-size="onPageSizeChange"
        />
        <span class="log-pagination__total">共 {{ pagination.total }} 条</span>
      </div>
    </n-card>

    <!-- 详情抽屉 -->
    <n-drawer v-model:show="showDrawer" :width="560" placement="right">
      <n-drawer-content title="日志详情" closable>
        <div v-if="detailLoading" class="log-drawer-loading">
          <n-spin />
        </div>
        <template v-else-if="detail">
          <n-descriptions label-placement="left" bordered :column="1" size="small">
            <n-descriptions-item label="ID">{{ detail.id }}</n-descriptions-item>
            <n-descriptions-item label="trace_id">{{ detail.traceId || '—' }}</n-descriptions-item>
            <n-descriptions-item label="模块">{{ detail.module || '—' }}</n-descriptions-item>
            <n-descriptions-item label="业务ID">{{ detail.businessId || '—' }}</n-descriptions-item>
            <n-descriptions-item label="操作方法">{{ detail.method || '—' }}</n-descriptions-item>
            <n-descriptions-item label="请求方法">
              <n-tag v-if="detail.requestMethod" size="small" type="info">{{ detail.requestMethod }}</n-tag>
              <span v-else>—</span>
            </n-descriptions-item>
            <n-descriptions-item label="请求地址">
              <span class="mono break-all">{{ detail.requestUrl || '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="IP">{{ detail.ip || '—' }}</n-descriptions-item>
            <n-descriptions-item label="User-Agent">
              <span class="break-all">{{ detail.userAgent || '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="耗时">
              {{ detail.costTimeMs != null ? `${detail.costTimeMs} ms` : '—' }}
            </n-descriptions-item>
            <n-descriptions-item label="结果">
              <n-tag :type="detail.success === 1 ? 'success' : detail.success === 0 ? 'error' : 'default'" size="small" bordered>
                {{ detail.success === 1 ? '成功' : detail.success === 0 ? '失败' : '—' }}
              </n-tag>
              <span v-if="detail.errorMessage" class="log-error-msg">{{ detail.errorMessage }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">{{ detail.createdAt || '—' }}</n-descriptions-item>
          </n-descriptions>

          <!-- 请求参数 -->
          <div class="log-json-block">
            <div class="log-json-block__title">请求参数</div>
            <div class="log-json-block__content">
              <n-code v-if="formattedRequestParams" :code="formattedRequestParams" language="json" word-wrap />
              <span v-else class="log-empty">—</span>
            </div>
            <span v-if="isRequestParamsTruncated" class="log-truncate-hint">内容超长已截断，仅展示前 8000 字符</span>
          </div>

          <!-- 响应结果 -->
          <div class="log-json-block">
            <div class="log-json-block__title">响应结果</div>
            <div class="log-json-block__content">
              <n-code v-if="formattedResponseResult" :code="formattedResponseResult" language="json" word-wrap />
              <span v-else class="log-empty">—</span>
            </div>
            <span v-if="isResponseResultTruncated" class="log-truncate-hint">内容超长已截断，仅展示前 8000 字符</span>
          </div>
        </template>
        <n-empty v-else description="未找到日志详情" />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDatePicker,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NPopconfirm,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  NPagination,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { systemLogApi, type SystemLog } from '@/api/systemLog'

const message = useMessage()

/* ---------- 筛选 ---------- */
const filters = ref<{
  module: string
  operationType: string | null
  username: string
  success: number | null
  traceId: string
  dateRange: [number, number] | null
}>({
  module: '',
  operationType: null,
  username: '',
  success: null,
  traceId: '',
  dateRange: null,
})

const operationTypeOptions = [
  { label: 'CREATE', value: 'CREATE' },
  { label: 'UPDATE', value: 'UPDATE' },
  { label: 'DELETE', value: 'DELETE' },
  { label: 'QUERY', value: 'QUERY' },
  { label: 'LOGIN', value: 'LOGIN' },
  { label: 'EXPORT', value: 'EXPORT' },
  { label: 'OTHER', value: 'OTHER' },
]

const successOptions = [
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
]

/** 将时间戳转为后端期望的 yyyy-MM-dd HH:mm:ss */
function formatDateTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/* ---------- 表格与分页 ---------- */
const tableData = ref<SystemLog[]>([])
const loading = ref(false)
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
})

async function loadData() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page,
      pageSize: pagination.value.pageSize,
    }
    if (filters.value.module?.trim()) params.module = filters.value.module.trim()
    if (filters.value.operationType) params.operationType = filters.value.operationType
    if (filters.value.username?.trim()) params.username = filters.value.username.trim()
    if (filters.value.success !== null && filters.value.success !== undefined) params.success = filters.value.success
    if (filters.value.traceId?.trim()) params.traceId = filters.value.traceId.trim()
    if (filters.value.dateRange && filters.value.dateRange[0] && filters.value.dateRange[1]) {
      // n-date-picker daterange 返回当天 00:00 时间戳，结束时间补到当天 23:59:59
      const start = filters.value.dateRange[0]
      const end = filters.value.dateRange[1]
      params.startTime = formatDateTime(start)
      // 结束时间取当天最后一秒
      const endDate = new Date(end)
      endDate.setHours(23, 59, 59, 999)
      params.endTime = formatDateTime(endDate.getTime())
    }

    const res = await systemLogApi.page(params as never)
    // 兼容两种分页响应形：{ list, total } 或 { records, total } 或 { data: ... }
    const raw = res as unknown as Record<string, unknown>
    const list = (raw.list ?? raw.records ?? raw.data ?? []) as SystemLog[]
    const total = (raw.total ?? raw.totalCount ?? 0) as number
    tableData.value = Array.isArray(list) ? list : []
    pagination.value.total = Number(total) || 0
  } catch {
    // 错误已由 request 拦截器 toast
    tableData.value = []
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.value.page = 1
  loadData()
}

function onReset() {
  filters.value = {
    module: '',
    operationType: null,
    username: '',
    success: null,
    traceId: '',
    dateRange: null,
  }
  pagination.value.page = 1
  loadData()
}

/* ---------- Trace 看板：轻量聚合（前端对 system_log.trace_id 分组，无重型 Run/Node 双表） ---------- */
interface TraceGroup {
  traceId: string
  shortId: string
  count: number
  maxLatency: number
  avgLatency: number
  channel: string
  topScoreText: string
  timeRange: string
  successRate: number
}

const traceBoardGroups = computed<TraceGroup[]>(() => {
  const map = new Map<string, SystemLog[]>()
  for (const row of tableData.value) {
    const tid = row.traceId?.trim() || '—'
    if (!map.has(tid)) map.set(tid, [])
    map.get(tid)!.push(row)
  }
  const groups: TraceGroup[] = []
  for (const [tid, rows] of map.entries()) {
    const count = rows.length
    const latencies = rows.map((r) => r.costTimeMs ?? 0)
    const maxLatency = Math.max(...latencies)
    const avgLatency = Math.round(latencies.reduce((a, b) => a + b, 0) / count)
    // channel：取出现最多的 module 作为通道代理（RagTraceAspect 的 channel 按 traceId 关联日志）
    const moduleCount = new Map<string, number>()
    for (const r of rows) {
      const m = r.module || '—'
      moduleCount.set(m, (moduleCount.get(m) || 0) + 1)
    }
    let channel = ''
    let maxC = 0
    for (const [m, c] of moduleCount.entries()) {
      if (c > maxC) { maxC = c; channel = m }
    }
    // topScore：system_log 无向量分数，尝试从 responseResult 解析，否则显示 —（RagTraceAspect 日志的 topScore 按 traceId 关联）
    let topScoreText = '—'
    for (const r of rows) {
      const txt = r.responseResult
      if (typeof txt === 'string' && txt.includes('topScore')) {
        try {
          const obj = JSON.parse(txt)
          const v = (obj as Record<string, unknown>)?.topScore ?? (obj as Record<string, unknown>)?.score
          if (typeof v === 'number') { topScoreText = v.toFixed(3); break }
          if (typeof v === 'string' && v) { topScoreText = v; break }
        } catch { /* ignore */ }
      }
    }
    const times = rows.map((r) => r.createdAt || '').filter(Boolean).sort()
    const timeRange = times.length ? `${String(times[0]).slice(11, 19)} → ${String(times[times.length - 1]).slice(11, 19)}` : '—'
    const successCount = rows.filter((r) => r.success === 1).length
    const successRate = successCount === count ? 1 : successCount === 0 ? 0 : 0.5
    const shortId = tid === '—' ? '—' : tid.length > 12 ? `${tid.slice(0, 8)}…${tid.slice(-4)}` : tid
    groups.push({ traceId: tid, shortId, count, maxLatency, avgLatency, channel, topScoreText, timeRange, successRate })
  }
  // 按条数/最大延迟综合排序，无 traceId 的排最后
  groups.sort((a, b) => {
    if (a.traceId === '—') return 1
    if (b.traceId === '—') return -1
    return b.maxLatency - a.maxLatency || b.count - a.count
  })
  return groups.slice(0, 12)
})

function filterByTrace(traceId: string) {
  if (traceId === '—') return
  if (filters.value.traceId === traceId) {
    filters.value.traceId = ''
  } else {
    filters.value.traceId = traceId
  }
  pagination.value.page = 1
  loadData()
}

function clearTraceFilter() {
  filters.value.traceId = ''
  pagination.value.page = 1
  loadData()
}

function onPageSizeChange(pageSize: number) {
  pagination.value.pageSize = pageSize
  pagination.value.page = 1
  loadData()
}

onMounted(loadData)

/* ---------- 表格列 ---------- */
const columns: DataTableColumns<SystemLog> = [
  { title: 'ID', key: 'id', width: 90, className: 'mono' },
  { title: '模块', key: 'module', width: 120, ellipsis: { tooltip: true } },
  { title: '操作类型', key: 'operationType', width: 110, render: (row) => (row.operationType ? h(NTag, { size: 'small', bordered: false }, { default: () => row.operationType }) : '—') },
  { title: '用户名', key: 'username', width: 120, ellipsis: { tooltip: true } },
  {
    title: '结果',
    key: 'success',
    width: 80,
    render: (row) => {
      if (row.success === 1) return h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '成功' })
      if (row.success === 0) return h(NTag, { size: 'small', type: 'error', bordered: false }, { default: () => '失败' })
      return h('span', '—')
    },
  },
  { title: 'IP', key: 'ip', width: 130, className: 'mono', ellipsis: { tooltip: true } },
  { title: '耗时', key: 'costTimeMs', width: 90, render: (row) => (row.costTimeMs != null ? `${row.costTimeMs}ms` : '—') },
  { title: '时间', key: 'createdAt', width: 180, className: 'mono', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (row) =>
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: () => openDetail(row) },
        { default: () => '详情' },
      ),
  },
]

/* ---------- 详情抽屉 ---------- */
const showDrawer = ref(false)
const detail = ref<SystemLog | null>(null)
const detailLoading = ref(false)

// JSON 预览截断阈值
const JSON_MAX_LEN = 8000

function formatJson(value: unknown): { text: string; truncated: boolean } {
  if (value == null || value === '') return { text: '', truncated: false }
  let raw: string
  if (typeof value === 'string') {
    // 尝试格式化 JSON 字符串
    try {
      const parsed = JSON.parse(value)
      raw = JSON.stringify(parsed, null, 2)
    } catch {
      raw = value
    }
  } else {
    try {
      raw = JSON.stringify(value, null, 2)
    } catch {
      raw = String(value)
    }
  }
  if (raw.length > JSON_MAX_LEN) {
    return { text: raw.slice(0, JSON_MAX_LEN), truncated: true }
  }
  return { text: raw, truncated: false }
}

const formattedRequestParams = computed(() => formatJson(detail.value?.requestParams).text)
const isRequestParamsTruncated = computed(() => formatJson(detail.value?.requestParams).truncated)
const formattedResponseResult = computed(() => formatJson(detail.value?.responseResult).text)
const isResponseResultTruncated = computed(() => formatJson(detail.value?.responseResult).truncated)

async function openDetail(row: SystemLog) {
  showDrawer.value = true
  detail.value = row
  // 尝试拉取最新详情（后端可能返回更完整的 JSON）
  detailLoading.value = true
  try {
    const data = await systemLogApi.detail(row.id)
    if (data) detail.value = data
  } catch {
    // 保持表格行数据作为兜底
  } finally {
    detailLoading.value = false
  }
}

/* ---------- 清理 ---------- */
const cleaning = ref(false)
const CLEAN_DAYS = 30

async function onClean() {
  cleaning.value = true
  try {
    await systemLogApi.clean(CLEAN_DAYS)
    message.success(`已清理 ${CLEAN_DAYS} 天前的日志`)
    await loadData()
  } catch {
    // 已 toast
  } finally {
    cleaning.value = false
  }
}
</script>

<style scoped>
.system-log-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Trace 看板：轻量聚合，无重型双表 */
.trace-board-card {
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}
.trace-board-title {
  font-weight: 700;
  font-size: 14px;
}
.trace-board-desc {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-3, #999);
  font-weight: 400;
}
.trace-board-count {
  font-size: 12px;
  color: var(--text-3);
}
.trace-board-empty {
  padding: 4px 0;
}
.trace-board-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
}
.trace-card {
  border: 1px solid var(--border, #eee);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--bg-soft, #fafafa);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.trace-card:hover {
  border-color: var(--primary, #18a058);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.trace-card.is-active {
  border-color: var(--primary, #18a058);
  background: var(--primary-soft, #e8f5e9);
}
.trace-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.trace-card__id {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
}
.trace-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: var(--text-2, #666);
  line-height: 1.5;
}
.trace-card__time {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  color: var(--text-3);
}
.trace-board-hint {
  margin-top: 10px;
  font-size: 11px;
  color: var(--text-3, #999);
}

.log-filter-card {
  width: 100%;
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}

.log-filter-form {
  gap: 8px 12px;
}

.log-table-card {
  width: 100%;
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}

.log-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.log-pagination__total {
  font-size: 12px;
  color: var(--text-3);
}

.mono {
  font-family: var(--font-mono, monospace);
}

.break-all {
  word-break: break-all;
}

.log-drawer-loading {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.log-json-block {
  margin-top: 16px;
}

.log-json-block__title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}

.log-json-block__content {
  border: 1px solid var(--border, #eee);
  border-radius: 6px;
  padding: 8px;
  background: var(--bg-soft, #fafafa);
  max-height: 320px;
  overflow: auto;
}

.log-json-block__content :deep(pre) {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}

.log-truncate-hint {
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3, #999);
}

.log-empty {
  color: var(--text-3, #999);
  font-size: 12px;
}

.log-error-msg {
  margin-left: 8px;
  font-size: 12px;
  color: var(--error, #d03050);
  word-break: break-all;
}
</style>
