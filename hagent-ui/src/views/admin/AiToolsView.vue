<template>
  <div class="tools-page">
    <div class="tools-head">
      <div>
        <h2 class="tools-title">AI 工具</h2>
        <p class="tools-desc">注册与维护可被 AI 调用的工具，测试其入参与输出</p>
      </div>
      <n-button type="primary" @click="openCreate">
        <template #icon>
          <n-icon :component="PlusOutlined" />
        </template>
        新建工具
      </n-button>
    </div>

    <n-card class="tools-table-card" :bordered="true">
      <n-data-table
        :columns="columns"
        :data="tools"
        :loading="toolsLoading"
        :row-key="(row: ToolItem) => row.toolKey"
        :pagination="pagination"
        :bordered="false"
      />
    </n-card>

    <!-- 新建工具 modal（暂无新增接口） -->
    <n-modal
      v-model:show="createModalVisible"
      preset="card"
      title="新建工具"
      :style="{ width: '560px', maxWidth: '92vw' }"
      class="tool-modal"
    >
      <n-form :model="createForm" label-placement="top" size="medium">
        <n-form-item label="工具名称">
          <n-input v-model:value="createForm.name" placeholder="例如：文章摘要" />
        </n-form-item>
        <n-form-item label="工具标识 toolKey">
          <n-input v-model:value="createForm.toolKey" placeholder="例如：article_summary" />
        </n-form-item>
        <n-form-item label="分类">
          <n-input v-model:value="createForm.category" placeholder="例如：文本处理" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input
            v-model:value="createForm.description"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="工具的用途与适用场景"
          />
        </n-form-item>
      </n-form>
      <div class="modal-actions">
        <n-button quaternary @click="createModalVisible = false">取消</n-button>
        <n-button type="primary" @click="confirmCreate">创建</n-button>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, reactive, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { PlusOutlined, PlayCircleOutlined } from '@vicons/antd'
import { aiToolApi, type AiToolItem } from '@/api/aiTool'

const router = useRouter()
const message = useMessage()

/* ---------- 工具数据 ---------- */
interface ToolParam {
  key: string
  label?: string
  type?: string
  required?: boolean
  placeholder?: string
}

interface ToolItem {
  toolKey: string
  name?: string
  description?: string
  category?: string
  params?: ToolParam[]
}

const tools = ref<ToolItem[]>([])
const toolsLoading = ref(false)

async function loadTools() {
  toolsLoading.value = true
  try {
    const list = await aiToolApi.list()
    tools.value = list.map((t) => ({
      toolKey: t.key,
      name: t.name,
      description: t.description,
      category: t.category,
      params: (t.inputs ?? []).map((p) => ({
        key: p.name,
        label: p.label,
        type: p.type,
        required: p.required,
        placeholder: p.placeholder,
      })),
    }))
  } catch {
    // 错误已由 request 拦截器统一 toast
    tools.value = []
  } finally {
    toolsLoading.value = false
  }
}

onMounted(loadTools)

const pagination = { pageSize: 10 }

function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

const columns: DataTableColumns<ToolItem> = [
  { title: '名称', key: 'name', width: 160, render: (row) => row.name || row.toolKey },
  { title: '标识', key: 'toolKey', width: 200, className: 'mono' },
  {
    title: '描述',
    key: 'description',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) => row.description || '—',
  },
  {
    title: '参数',
    key: 'params',
    width: 80,
    render: (row) => String(row.params?.length ?? 0),
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    render: (row) =>
      h(
        NButton,
        {
          size: 'small',
          type: 'primary',
          secondary: true,
          onClick: () => router.push(`/ai/tools/execute/${row.toolKey}`),
        },
        { icon: renderIcon(PlayCircleOutlined), default: () => '测试执行' },
      ),
  },
]

/* ---------- 新建工具（暂无新增接口） ---------- */
interface CreateForm {
  name: string
  toolKey: string
  category: string
  description: string
}

const createModalVisible = ref(false)
const createForm = reactive<CreateForm>({ name: '', toolKey: '', category: '', description: '' })

function openCreate() {
  createForm.name = ''
  createForm.toolKey = ''
  createForm.category = ''
  createForm.description = ''
  createModalVisible.value = true
}

function confirmCreate() {
  if (!createForm.name.trim() || !createForm.toolKey.trim()) {
    message.warning('请填写工具名称与标识')
    return
  }
  createModalVisible.value = false
  message.info('暂无新增工具接口，敬请期待')
}
</script>

<style scoped>
.tools-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.tools-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.tools-title {
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: -0.02em;
}
.tools-desc {
  color: var(--text-3);
  font-size: var(--text-sm);
  margin-top: 2px;
}

.tools-table-card {
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

</style>
