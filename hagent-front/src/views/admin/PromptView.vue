<template>
  <div class="prompt-page">
    <div class="prompt-head">
      <div>
        <h2 class="prompt-title">提示词库</h2>
        <p class="prompt-desc">维护可复用的 AI 提示词，支持分类筛选、复制使用与使用次数统计</p>
      </div>
      <n-button type="primary" @click="openCreate">
        <template #icon>
          <n-icon :component="PlusOutlined" />
        </template>
        新建提示词
      </n-button>
    </div>

    <!-- 筛选区 -->
    <n-card class="prompt-filter-card" :bordered="true">
      <n-form :model="filters" inline label-placement="left" :show-feedback="false">
        <n-form-item label="分类">
          <n-input
            v-model:value="filters.category"
            placeholder="例如：writing"
            clearable
            style="width: 150px"
            @keydown.enter="onSearch"
          />
        </n-form-item>
        <n-form-item label="关键词">
          <n-input
            v-model:value="filters.keyword"
            placeholder="标题关键词"
            clearable
            style="width: 180px"
            @keydown.enter="onSearch"
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

    <!-- 列表区 -->
    <n-card class="prompt-table-card" :bordered="true" title="提示词列表">
      <template #header-extra>
        <span class="prompt-total">共 {{ prompts.length }} 条</span>
      </template>
      <n-data-table
        :columns="columns"
        :data="prompts"
        :loading="loading"
        :row-key="(row: AiPrompt) => row.id ?? 0"
        :bordered="false"
        :pagination="pagination"
      >
        <template #empty>
          <n-empty description="暂无提示词，点击右上角新建" style="padding: 24px 0" />
        </template>
      </n-data-table>
    </n-card>

    <!-- 新建/编辑 modal -->
    <n-modal
      v-model:show="modalVisible"
      preset="card"
      :title="editingId ? '编辑提示词' : '新建提示词'"
      :style="{ width: '640px', maxWidth: '92vw' }"
    >
      <n-form :model="form" label-placement="top" size="medium">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" :disabled="isBuiltinEditing" placeholder="提示词标题" />
        </n-form-item>
        <n-form-item label="分类">
          <n-input
            v-model:value="form.category"
            :disabled="isBuiltinEditing"
            placeholder="例如：writing / seo / analysis / general"
          />
        </n-form-item>
        <n-form-item label="描述">
          <n-input
            v-model:value="form.description"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="提示词的用途与适用场景"
          />
        </n-form-item>
        <n-form-item label="提示词内容" required>
          <n-input
            v-model:value="form.content"
            type="textarea"
            :autosize="{ minRows: 6, maxRows: 14 }"
            placeholder="支持 {变量} 占位符"
          />
        </n-form-item>
        <n-form-item label="变量占位符">
          <n-input v-model:value="form.variables" :disabled="isBuiltinEditing" placeholder="逗号分隔，例如：keyword,topK" />
        </n-form-item>
        <div class="form-row">
          <n-form-item label="排序">
            <n-input-number v-model:value="form.sortOrder" :disabled="isBuiltinEditing" class="form-row__num" />
          </n-form-item>
          <n-form-item label="状态">
            <n-switch v-model:value="statusEnabled" :disabled="isBuiltinEditing">
              <template #checked>启用</template>
              <template #unchecked>禁用</template>
            </n-switch>
          </n-form-item>
        </div>
        <n-alert v-if="isBuiltinEditing" type="info" :bordered="false">
          内置提示词仅允许修改内容、描述、变量、状态与排序，标题与分类不可更改
        </n-alert>
      </n-form>
      <div class="modal-actions">
        <n-button quaternary @click="modalVisible = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="confirmSave">保存</n-button>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSpace,
  NSwitch,
  NTag,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { PlusOutlined } from '@vicons/antd'
import { promptApi, type AiPrompt } from '@/api/prompt'

const message = useMessage()

/* ---------- 列表与筛选 ---------- */
const prompts = ref<AiPrompt[]>([])
const loading = ref(false)
const filters = ref<{ category: string; keyword: string }>({ category: '', keyword: '' })
const pagination = { pageSize: 10 }

async function loadPrompts() {
  loading.value = true
  try {
    const params: { category?: string; keyword?: string } = {}
    if (filters.value.category.trim()) params.category = filters.value.category.trim()
    if (filters.value.keyword.trim()) params.keyword = filters.value.keyword.trim()
    prompts.value = await promptApi.list(params)
  } catch {
    // 错误已由 request 拦截器统一 toast
    prompts.value = []
  } finally {
    loading.value = false
  }
}

function onSearch() {
  loadPrompts()
}

function onReset() {
  filters.value = { category: '', keyword: '' }
  loadPrompts()
}

onMounted(loadPrompts)

/* ---------- 表格列 ---------- */
const columns: DataTableColumns<AiPrompt> = [
  {
    title: '标题',
    key: 'title',
    width: 200,
    ellipsis: { tooltip: true },
    render: (row) =>
      h('div', { class: 'title-cell' }, [
        h('span', { class: 'title-cell__text' }, row.title || '—'),
        row.builtin === 1
          ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, { default: () => '内置' })
          : null,
      ]),
  },
  { title: '分类', key: 'category', width: 110, render: (row) => row.category || '—' },
  {
    title: '内容摘要',
    key: 'content',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) => row.content || '—',
  },
  { title: '使用次数', key: 'useCount', width: 90, render: (row) => String(row.useCount ?? 0) },
  {
    title: '状态',
    key: 'status',
    width: 80,
    render: (row) =>
      row.status === 0
        ? h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => '禁用' })
        : h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '启用' }),
  },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'primary', onClick: () => onCopy(row) },
          { default: () => '复制内容' }
        ),
        h(
          NButton,
          { size: 'small', quaternary: true, onClick: () => openEdit(row) },
          { default: () => '编辑' }
        ),
        row.builtin === 1
          ? h(NButton, { size: 'small', quaternary: true, type: 'error', disabled: true }, { default: () => '删除' })
          : h(
              NPopconfirm,
              { onPositiveClick: () => onRemove(row) },
              {
                trigger: () =>
                  h(NButton, { size: 'small', quaternary: true, type: 'error' }, { default: () => '删除' }),
                default: () => '确认删除该提示词吗？删除后不可恢复。',
              }
            ),
      ]),
  },
]

/* ---------- 复制内容（复制即视为使用，调用 use 自增次数） ---------- */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 降级到 execCommand
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

function onCopy(row: AiPrompt) {
  if (!row.content) {
    message.warning('该提示词暂无内容')
    return
  }
  copyText(row.content).then((ok) => {
    if (!ok) {
      message.error('复制失败，请手动复制')
      return
    }
    message.success('已复制提示词内容')
    if (row.id) {
      promptApi
        .use(row.id)
        .then(() => {
          // 本地乐观更新使用次数，避免整表刷新
          row.useCount = (row.useCount ?? 0) + 1
        })
        .catch(() => {
          // 自增失败不影响复制结果
        })
    }
  })
}

/* ---------- 删除 ---------- */
async function onRemove(row: AiPrompt) {
  if (!row.id) return
  try {
    await promptApi.remove(row.id)
    message.success('已删除')
    await loadPrompts()
  } catch {
    // 错误已由 request 拦截器统一 toast
  }
}

/* ---------- 新建/编辑 ---------- */
interface PromptForm {
  title: string
  category: string
  description: string
  content: string
  variables: string
  sortOrder: number | null
  status: number
}

const modalVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
/** 编辑中的行（用于判断内置保护） */
const editingRow = ref<AiPrompt | null>(null)
const isBuiltinEditing = computed(() => editingRow.value?.builtin === 1)

const form = reactive<PromptForm>({
  title: '',
  category: '',
  description: '',
  content: '',
  variables: '',
  sortOrder: 0,
  status: 1,
})

const statusEnabled = computed({
  get: () => form.status === 1,
  set: (v: boolean) => {
    form.status = v ? 1 : 0
  },
})

function openCreate() {
  editingId.value = null
  editingRow.value = null
  form.title = ''
  form.category = ''
  form.description = ''
  form.content = ''
  form.variables = ''
  form.sortOrder = 0
  form.status = 1
  modalVisible.value = true
}

function openEdit(row: AiPrompt) {
  editingId.value = row.id ?? null
  editingRow.value = row
  form.title = row.title ?? ''
  form.category = row.category ?? ''
  form.description = row.description ?? ''
  form.content = row.content ?? ''
  form.variables = row.variables ?? ''
  form.sortOrder = row.sortOrder ?? 0
  form.status = row.status === 0 ? 0 : 1
  modalVisible.value = true
}

async function confirmSave() {
  if (!form.title.trim()) {
    message.warning('请填写标题')
    return
  }
  if (!form.content.trim()) {
    message.warning('请填写提示词内容')
    return
  }
  saving.value = true
  try {
    const payload: AiPrompt = {
      title: form.title.trim(),
      category: form.category.trim() || undefined,
      description: form.description,
      content: form.content,
      variables: form.variables,
      sortOrder: form.sortOrder ?? 0,
      status: form.status,
    }
    if (editingId.value) {
      await promptApi.update(editingId.value, payload)
      message.success('提示词已更新')
    } else {
      await promptApi.create(payload)
      message.success('提示词已创建')
    }
    modalVisible.value = false
    await loadPrompts()
  } catch {
    // 错误已由 request 拦截器统一 toast
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.prompt-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.prompt-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.prompt-title {
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: -0.02em;
}
.prompt-desc {
  color: var(--text-3);
  font-size: var(--text-sm);
  margin-top: 2px;
}

.prompt-filter-card,
.prompt-table-card {
  width: 100%;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

.prompt-total {
  font-size: 12px;
  color: var(--text-3);
}

.title-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.title-cell__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.form-row {
  display: flex;
  gap: 16px;
}
.form-row__num {
  width: 120px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
