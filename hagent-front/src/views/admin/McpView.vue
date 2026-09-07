<template>
  <div class="mcp-page">
    <PageHeader title="MCP 工具" description="查看已注册的 MCP 工具，在线调试调用并获取客户端接入配置">
      <template #actions>
        <n-button quaternary :loading="toolsLoading" @click="loadTools">刷新</n-button>
      </template>
    </PageHeader>

    <!-- 工具总览 -->
    <n-card class="mcp-table-card" :bordered="true" title="工具总览">
      <template #header-extra>
        <span class="mcp-total">共 {{ tools.length }} 个工具</span>
      </template>
      <n-data-table
        :columns="toolColumns"
        :data="tools"
        :loading="toolsLoading"
        :row-key="(row: McpToolItem) => row.name"
        :bordered="false"
        :pagination="pagination"
        :row-class-name="rowClassName"
      >
        <template #empty>
          <n-empty description="暂无已注册的 MCP 工具" style="padding: 24px 0" />
        </template>
      </n-data-table>
    </n-card>

    <!-- 在线调试 -->
    <n-card v-if="selectedTool" class="mcp-debug-card" :bordered="true" :title="`在线调试 · ${selectedTool.name}`">
      <template #header-extra>
        <n-button size="small" quaternary @click="selectedTool = null">收起</n-button>
      </template>

      <div class="debug-meta">
        <n-tag size="small" :bordered="false">来源：{{ selectedTool.owner || '—' }}</n-tag>
        <n-tag size="small" :bordered="false">返回：{{ selectedTool.returnType || '—' }}</n-tag>
        <span class="debug-meta__desc">{{ selectedTool.description || '—' }}</span>
      </div>

      <n-data-table
        class="debug-params-table"
        size="small"
        :columns="paramColumns"
        :data="selectedTool.params ?? []"
        :row-key="(row: McpToolParam) => row.name"
        :bordered="false"
        :pagination="false"
      >
        <template #empty>
          <n-empty description="该工具无入参，可直接执行" size="small" style="padding: 12px 0" />
        </template>
      </n-data-table>

      <div class="debug-editor">
        <div class="debug-editor__label">参数 JSON</div>
        <n-input
          v-model:value="paramsText"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 12 }"
          placeholder='例如：{"query":"AI","topK":5}'
          class="mono"
        />
        <div class="debug-editor__actions">
          <n-button size="small" @click="resetParams">生成参数模板</n-button>
          <n-button type="primary" size="small" :loading="invoking" @click="onInvoke">执行</n-button>
        </div>
      </div>

      <div class="debug-result">
        <div class="debug-result__label">执行结果</div>
        <n-code v-if="hasResult && !resultIsString" :code="resultText" language="json" word-wrap />
        <pre v-else-if="hasResult" class="debug-result__pre mono">{{ resultText }}</pre>
        <n-empty v-else description="尚未执行，点击上方按钮调用工具" size="small" style="padding: 16px 0" />
      </div>
    </n-card>

    <!-- 接入配置 -->
    <n-card class="mcp-config-card" :bordered="true" title="接入配置">
      <n-code v-if="configText" :code="configText" language="json" word-wrap />
      <n-empty v-else description="暂无接入配置" size="small" style="padding: 24px 0" />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NCode,
  NDataTable,
  NEmpty,
  NInput,
  NTag,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { mcpApi, type McpToolItem, type McpToolParam } from '@/api/mcp'
import PageHeader from '@/components/ui/PageHeader.vue'

const message = useMessage()

/* ---------- 工具总览 ---------- */
const tools = ref<McpToolItem[]>([])
const toolsLoading = ref(false)
const pagination = { pageSize: 10 }

async function loadTools() {
  toolsLoading.value = true
  try {
    tools.value = await mcpApi.listTools()
  } catch {
    // 错误已由 request 拦截器统一 toast
    tools.value = []
  } finally {
    toolsLoading.value = false
  }
}

onMounted(() => {
  loadTools()
  loadConfig()
})

const toolColumns: DataTableColumns<McpToolItem> = [
  { title: '名称', key: 'name', width: 180, className: 'mono' },
  {
    title: '描述',
    key: 'description',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) => row.description || '—',
  },
  { title: '来源', key: 'owner', width: 170, render: (row) => row.owner || '—' },
  { title: '返回类型', key: 'returnType', width: 110, className: 'mono', render: (row) => row.returnType || '—' },
  { title: '参数数', key: 'params', width: 80, render: (row) => String(row.params?.length ?? 0) },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (row) =>
      h(
        NButton,
        {
          size: 'small',
          type: 'primary',
          secondary: selectedTool.value?.name !== row.name,
          onClick: () => selectTool(row),
        },
        { default: () => '调试' }
      ),
  },
]

function rowClassName(row: McpToolItem) {
  return selectedTool.value?.name === row.name ? 'row-selected' : ''
}

/* ---------- 在线调试 ---------- */
const selectedTool = ref<McpToolItem | null>(null)
const paramsText = ref('{}')
const invoking = ref(false)
const resultText = ref('')
const resultIsString = ref(false)
const hasResult = ref(false)

const paramColumns: DataTableColumns<McpToolParam> = [
  { title: '参数名', key: 'name', width: 140, className: 'mono' },
  { title: '类型', key: 'type', width: 110, className: 'mono', render: (row) => row.type || '—' },
  {
    title: '必填',
    key: 'required',
    width: 70,
    render: (row) =>
      row.required
        ? h(NTag, { size: 'tiny', type: 'error', bordered: false }, { default: () => '是' })
        : h(NTag, { size: 'tiny', bordered: false }, { default: () => '否' }),
  },
  {
    title: '说明',
    key: 'description',
    minWidth: 160,
    ellipsis: { tooltip: true },
    render: (row) => row.description || '—',
  },
]

/** 按参数类型生成调用模板：数值型补 0、布尔补 false、集合补 []/{}、其余补空串 */
function buildParamsTemplate(tool: McpToolItem): string {
  const obj: Record<string, unknown> = {}
  for (const p of tool.params ?? []) {
    const t = (p.type ?? '').toLowerCase()
    if (t.includes('int') || t.includes('long') || t.includes('double') || t.includes('float')) {
      obj[p.name] = 0
    } else if (t.includes('bool')) {
      obj[p.name] = false
    } else if (t.includes('list')) {
      obj[p.name] = []
    } else if (t.includes('map')) {
      obj[p.name] = {}
    } else {
      obj[p.name] = ''
    }
  }
  return JSON.stringify(obj, null, 2)
}

function selectTool(row: McpToolItem) {
  selectedTool.value = row
  paramsText.value = buildParamsTemplate(row)
  resultText.value = ''
  resultIsString.value = false
  hasResult.value = false
}

function resetParams() {
  if (selectedTool.value) {
    paramsText.value = buildParamsTemplate(selectedTool.value)
  }
}

async function onInvoke() {
  if (!selectedTool.value) return
  const raw = paramsText.value.trim()
  let params: Record<string, unknown> = {}
  if (raw) {
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      message.error('参数 JSON 格式不正确')
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      message.error('参数必须是 JSON 对象，例如 {"query":"AI"}')
      return
    }
    params = parsed as Record<string, unknown>
  }
  invoking.value = true
  hasResult.value = false
  try {
    const res = await mcpApi.invoke(selectedTool.value.name, params)
    if (typeof res === 'string') {
      resultText.value = res
      resultIsString.value = true
    } else {
      resultText.value = JSON.stringify(res, null, 2)
      resultIsString.value = false
    }
    hasResult.value = true
    message.success('调用成功')
  } catch {
    // 错误已由 request 拦截器统一 toast
  } finally {
    invoking.value = false
  }
}

/* ---------- 接入配置 ---------- */
const configText = ref('')

async function loadConfig() {
  try {
    const cfg = await mcpApi.config()
    configText.value = JSON.stringify(cfg, null, 2)
  } catch {
    // 错误已由 request 拦截器统一 toast
    configText.value = ''
  }
}
</script>

<style scoped>
.mcp-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.mcp-table-card,
.mcp-debug-card,
.mcp-config-card {
  width: 100%;
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
}

.mcp-total {
  font-size: 12px;
  color: var(--text-3);
}

/* 选中行高亮（n-data-table 的 tr/td 由组件渲染，需 :deep 穿透） */
.mcp-table-card :deep(tr.row-selected td) {
  background-color: var(--primary-soft, rgba(24, 160, 88, 0.08));
}

.debug-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.debug-meta__desc {
  color: var(--text-2);
  font-size: var(--text-sm);
}

.debug-params-table {
  margin-bottom: 12px;
}

.debug-editor__label,
.debug-result__label {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}

.debug-editor__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.debug-result {
  margin-top: 16px;
}

.debug-result :deep(.n-code) {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
  background: var(--bg-soft);
  max-height: 360px;
  overflow: auto;
}

.debug-result__pre {
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
  background: var(--bg-soft);
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}

.mcp-config-card :deep(.n-code) {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
  background: var(--bg-soft);
  max-height: 420px;
  overflow: auto;
}

.mono {
  font-family: var(--font-mono, monospace);
}
</style>
