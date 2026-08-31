<template>
  <div class="settings-page">
    <n-card class="settings-card" :bordered="true">
      <!-- 宽屏左侧导航：内容再长，导航也始终可见，不会滚动丢失方位；
           窄屏（≤767px）切回顶部，避免左侧导航挤掉内容区宽度 -->
      <n-tabs
        type="line"
        animated
        :placement="isNarrow ? 'top' : 'left'"
        class="settings-tabs"
      >
        <!-- 站点设置 -->
        <n-tab-pane name="site" tab="站点设置">
          <div class="settings-pane">
            <n-form :model="baseForm" label-placement="left" label-width="96" size="large">
              <div class="form-grid">
                <n-form-item label="站点名称" class="span-1">
                  <n-input v-model:value="baseForm.siteName" placeholder="HAgent" />
                </n-form-item>
                <n-form-item label="站点链接" class="span-1">
                  <n-input v-model:value="baseForm.siteUrl" placeholder="https://example.com" />
                </n-form-item>
                <n-form-item label="站点描述" class="span-full">
                  <n-input
                    v-model:value="baseForm.description"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                    placeholder="一句话介绍你的站点"
                  />
                </n-form-item>
              </div>
            </n-form>
            <div class="save-bar">
              <n-button type="primary" :loading="baseSaving" @click="saveBase">保存设置</n-button>
            </div>
          </div>
        </n-tab-pane>

        <!-- AI 模型 -->
        <n-tab-pane name="ai" tab="AI 模型">
          <div class="settings-pane">
            <section class="block">
              <h3 class="block__title">文本大模型（主）</h3>
              <n-form :model="aiForm" label-placement="left" label-width="110" size="large">
                <div class="form-grid">
                  <n-form-item label="模型名称" class="span-1">
                    <n-input v-model:value="aiForm.chatModel" placeholder="如 mimo-v2.5 / deepseek-chat" />
                  </n-form-item>
                  <n-form-item label="温度" class="span-1">
                    <n-slider v-model:value="aiForm.temperature" :min="0" :max="2" :step="0.1" :format-tooltip="(v: number) => v.toFixed(1)" class="slider-full" />
                  </n-form-item>
                  <n-form-item label="Max Tokens" class="span-1">
                    <n-input-number v-model:value="aiForm.maxTokens" :min="256" :max="8192" :step="256" class="num-full" />
                  </n-form-item>
                  <n-form-item label="启用 RAG" class="span-1">
                    <n-checkbox v-model:checked="aiForm.enableRag">开启知识库检索问答</n-checkbox>
                  </n-form-item>
                  <n-form-item label="API Base URL" class="span-full">
                    <n-input v-model:value="aiForm.chatBaseUrl" placeholder="https://api.example.com/v1" />
                  </n-form-item>
                  <n-form-item label="API Key" class="span-full">
                    <n-input v-model:value="aiForm.chatApiKey" type="password" show-password-on="click" placeholder="sk-****" />
                  </n-form-item>
                  <n-form-item label="系统提示词" class="span-full">
                    <n-input v-model:value="aiForm.systemPrompt" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="AI 助手的基础系统提示词" />
                  </n-form-item>
                </div>
              </n-form>
            </section>

            <section class="block">
              <h3 class="block__title">向量大模型（Embedding）</h3>
              <n-form :model="aiForm" label-placement="left" label-width="110" size="large">
                <div class="form-grid">
                  <n-form-item label="模型名称" class="span-1">
                    <n-input v-model:value="aiForm.embModel" placeholder="如 text-embedding-v4" />
                  </n-form-item>
                  <n-form-item label="向量维度" class="span-1">
                    <n-input-number
                      v-model:value="aiForm.embDimension"
                      :min="128"
                      :max="4096"
                      :step="128"
                      placeholder="如 1024"
                      class="num-full"
                    />
                  </n-form-item>
                  <n-form-item label="向量索引名" class="span-1">
                    <n-input v-model:value="aiForm.vectorIndex" placeholder="hagent_rag" />
                  </n-form-item>
                  <div class="span-1" />
                  <n-form-item label="API Base URL" class="span-full">
                    <n-input v-model:value="aiForm.embBaseUrl" placeholder="https://api.example.com/v1" />
                  </n-form-item>
                  <n-form-item label="API Key" class="span-full">
                    <n-input v-model:value="aiForm.embApiKey" type="password" show-password-on="click" placeholder="sk-****" />
                  </n-form-item>
                </div>
              </n-form>
              <n-alert v-if="aiForm.embDimension !== initialEmbDimension" type="warning" class="dim-warn">
                维度已变更（{{ initialEmbDimension }} → {{ aiForm.embDimension }}）。保存后需重建向量表，已有向量需重新向量化，否则检索会报维度不匹配。
                <n-button text type="warning" size="small" style="margin-left: 8px" @click="$router.push('/admin/knowledge')">
                  前往知识库一键重建 →
                </n-button>
              </n-alert>
            </section>

            <section class="block">
              <h3 class="block__title">多模态 / 视觉大模型</h3>
              <n-form :model="aiForm" label-placement="left" label-width="110" size="large">
                <div class="form-grid">
                  <n-form-item label="模型名称" class="span-1">
                    <n-input v-model:value="aiForm.visModel" placeholder="如 mimo-v2.5 / qwen-vl-plus" />
                  </n-form-item>
                  <n-form-item label="API Base URL" class="span-full">
                    <n-input v-model:value="aiForm.visBaseUrl" placeholder="https://api.example.com/v1" />
                  </n-form-item>
                  <n-form-item label="API Key" class="span-full">
                    <n-input v-model:value="aiForm.visApiKey" type="password" show-password-on="click" placeholder="sk-****" />
                  </n-form-item>
                </div>
              </n-form>
            </section>

            <n-alert v-if="aiTestResult" :type="aiTestStatus === 'success' ? 'success' : 'error'" class="ai-test-result">
              {{ aiTestResult }}
            </n-alert>
            <n-alert v-if="embTestResult" :type="embTestStatus === 'success' ? 'success' : 'error'" class="ai-test-result">
              {{ embTestResult }}
            </n-alert>

            <div class="save-bar">
              <n-button :loading="aiTesting" secondary @click="testTextModel">测试文本模型连接</n-button>
              <n-button :loading="embTesting" secondary @click="testEmbModel">测试向量模型</n-button>
              <n-button type="primary" :loading="aiSaving" @click="saveAi">保存 AI 配置</n-button>
            </div>
          </div>
        </n-tab-pane>

        <!-- 备用模型：独占一屏，避免与 AI 主配置挤在一起 -->
        <n-tab-pane name="fallback" tab="备用模型">
          <div class="settings-pane">
            <div class="block__head">
              <div>
                <h3 class="block__title">多级降级链</h3>
                <p class="block__desc">
                  主模型失败时按 主 → 二级 → 三级 … 顺序逐级重试。备用仅替换模型名 / Base URL / Key，温度等参数沿用主模型。
                </p>
              </div>
              <n-button size="small" secondary @click="addFallback">+ 新增备用</n-button>
            </div>

            <div v-if="aiForm.fallbackModels.length === 0" class="fb-empty">
              未配置备用，仅主模型生效
            </div>

            <div v-else class="fb-list">
              <div v-for="(m, idx) in aiForm.fallbackModels" :key="idx" class="fb-card">
                <div class="fb-card__head">
                  <n-tag :bordered="false" :type="idx === 0 ? 'success' : 'info'" size="small">
                    {{ levelLabel(idx) }}
                  </n-tag>
                  <div class="fb-card__actions">
                    <n-button size="tiny" quaternary :disabled="idx === 0" @click="moveUp(idx)">上移</n-button>
                    <n-button size="tiny" quaternary :disabled="idx === aiForm.fallbackModels.length - 1" @click="moveDown(idx)">下移</n-button>
                    <n-button size="tiny" quaternary type="error" @click="removeFallback(idx)">删除</n-button>
                  </div>
                </div>
                <n-form label-placement="left" label-width="88" size="medium">
                  <div class="form-grid">
                    <n-form-item label="模型名称" class="span-1">
                      <n-input v-model:value="m.name" placeholder="如 deepseek-chat" />
                    </n-form-item>
                    <n-form-item label="Base URL" class="span-1">
                      <n-input v-model:value="m.baseUrl" placeholder="https://api.example.com/v1" />
                    </n-form-item>
                    <n-form-item label="API Key" class="span-full">
                      <n-input v-model:value="m.apiKey" type="password" show-password-on="click" placeholder="留空则复用主模型 Key" />
                    </n-form-item>
                  </div>
                </n-form>
              </div>
            </div>

            <div class="save-bar">
              <n-button type="primary" :loading="aiSaving" @click="saveAi">保存备用模型</n-button>
            </div>
          </div>
        </n-tab-pane>

        <!-- 账号安全 -->
        <n-tab-pane name="account" tab="账号安全">
          <div class="settings-pane">
            <section class="block">
              <h3 class="block__title">个人资料</h3>
              <n-form :model="profileForm" label-placement="left" label-width="96" size="large">
                <n-form-item label="头像">
                  <div class="avatar-row">
                    <div class="avatar-placeholder">
                      <n-icon :component="UserOutlined" />
                    </div>
                    <n-upload :custom-request="mockAvatarUpload" :show-file-list="false" accept=".png,.jpg,.jpeg,.webp">
                      <n-button size="small" secondary>上传头像</n-button>
                    </n-upload>
                    <span class="avatar-hint">支持 PNG / JPG / WebP（mock）</span>
                  </div>
                </n-form-item>
                <div class="form-grid">
                  <n-form-item label="昵称" class="span-1">
                    <n-input v-model:value="profileForm.nickname" placeholder="你的昵称" />
                  </n-form-item>
                  <n-form-item label="邮箱" class="span-1">
                    <n-input v-model:value="profileForm.email" placeholder="you@example.com" />
                  </n-form-item>
                </div>
              </n-form>
              <div class="save-bar">
                <n-button type="primary" :loading="profileSaving" @click="saveProfile">保存个人资料</n-button>
              </div>
            </section>

            <section class="block">
              <h3 class="block__title">修改密码</h3>
              <n-form :model="pwdForm" label-placement="left" label-width="96" size="large">
                <div class="form-grid">
                  <n-form-item label="旧密码" class="span-1">
                    <n-input v-model:value="pwdForm.oldPassword" type="password" show-password-on="click" placeholder="请输入旧密码" />
                  </n-form-item>
                  <n-form-item label="新密码" class="span-1">
                    <n-input v-model:value="pwdForm.newPassword" type="password" show-password-on="click" placeholder="至少 6 位" />
                  </n-form-item>
                  <n-form-item label="确认密码" class="span-1">
                    <n-input v-model:value="pwdForm.confirmPassword" type="password" show-password-on="click" placeholder="再次输入新密码" />
                  </n-form-item>
                  <div class="span-1 grid-placeholder" />
                </div>
              </n-form>
              <div class="save-bar">
                <n-button type="primary" :loading="pwdSaving" @click="changePassword">更新密码</n-button>
              </div>
            </section>
          </div>
        </n-tab-pane>
      </n-tabs>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSlider,
  NTabPane,
  NTabs,
  NTag,
  NUpload,
  useMessage,
  type UploadCustomRequestOptions,
} from 'naive-ui'
import { UserOutlined } from '@vicons/antd'
import { systemApi } from '@/api/system'
import { authApi } from '@/api/auth'

const message = useMessage()

/* ---------- 响应式：窄屏把左侧导航切回顶部 ---------- */
const isNarrow = ref(false)
function syncNarrow() {
  isNarrow.value = window.matchMedia('(max-width: 767px)').matches
}

/* ---------- 设置加载 ---------- */
const settings = ref<Record<string, unknown>>({})

/** key 容错取值：按多个候选 key 依次取第一个存在的值 */
function pick(keys: string[], fallback: string): string {
  for (const k of keys) {
    const v = settings.value[k]
    if (v !== undefined && v !== null && String(v) !== '') return String(v)
  }
  return fallback
}

function pickNumber(keys: string[], fallback: number): number {
  for (const k of keys) {
    const v = settings.value[k]
    const n = typeof v === 'number' ? v : Number(v)
    if (v !== undefined && v !== null && !Number.isNaN(n)) return n
  }
  return fallback
}

function parseFallbackModels(): FallbackModel[] {
  const rawKeys = ['ai.fallback.chat.models', 'ai.fallback.chat_models', 'ai.fallback.models']
  let raw: unknown = undefined
  for (const k of rawKeys) {
    if (settings.value[k] !== undefined && settings.value[k] !== null && String(settings.value[k]) !== '') {
      raw = settings.value[k]
      break
    }
  }
  if (raw === undefined || raw === null || raw === '') return []
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw as string) : raw
    if (!Array.isArray(arr)) return []
    const list: FallbackModel[] = arr
      .map((it: any, i: number) => ({
        name: String(it?.name ?? it?.model ?? ''),
        baseUrl: String(it?.baseUrl ?? it?.base_url ?? it?.baseURL ?? ''),
        apiKey: String(it?.apiKey ?? it?.api_key ?? it?.apiKey ?? ''),
        order: typeof it?.order === 'number' ? it.order : i,
      }))
      .filter((m: FallbackModel) => m.name || m.baseUrl)
      .sort((a: FallbackModel, b: FallbackModel) => a.order - b.order)
    list.forEach((m, i) => (m.order = i))
    return list
  } catch {
    return []
  }
}

async function loadSettings() {
  try {
    const data = await systemApi.getSettings()
    settings.value = data as Record<string, unknown>
    baseForm.siteName = pick(['site.name', 'name'], 'HAgent')
    baseForm.description = pick(['site.description', 'description'], 'AI 驱动的个人数字基地')
    baseForm.siteUrl = pick(['site.url', 'siteUrl'], 'https://hagent.example.com')
    aiForm.chatModel = pick(['ai.chat.model'], 'mimo-v2.5')
    aiForm.chatBaseUrl = pick(['ai.chat.base-url'], '')
    aiForm.chatApiKey = pick(['ai.chat.api-key'], '')
    aiForm.temperature = pickNumber(['ai.default_temperature'], 0.7)
    aiForm.maxTokens = pickNumber(['ai.default_max_tokens'], 4096)
    aiForm.systemPrompt = pick(['ai.system_prompt'], '')
    aiForm.enableRag = pick(['ai.enable_rag'], 'true') === 'true'
    aiForm.embModel = pick(['ai.embedding.model'], 'text-embedding-v4')
    aiForm.embBaseUrl = pick(['ai.embedding.base-url'], '')
    aiForm.embApiKey = pick(['ai.embedding.api-key'], '')
    aiForm.embDimension = pickNumber(['ai.vector-dimension'], 1024)
    initialEmbDimension.value = aiForm.embDimension
    aiForm.vectorIndex = pick(['ai.vector-index'], 'hagent_rag')
    aiForm.visModel = pick(['ai.vision.model'], 'mimo-v2.5')
    aiForm.visBaseUrl = pick(['ai.vision.base-url'], '')
    aiForm.visApiKey = pick(['ai.vision.api-key'], '')
    aiForm.fallbackModels = parseFallbackModels()
  } catch {
    // 错误已由 request 拦截器统一 toast
  }
}

onMounted(() => {
  loadSettings()
  loadProfile()
  syncNarrow()
  window.addEventListener('resize', syncNarrow)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncNarrow)
})

/* ---------- 基础设置 ---------- */
interface BaseForm {
  siteName: string
  description: string
  siteUrl: string
}

const baseForm = reactive<BaseForm>({
  siteName: 'HAgent',
  description: 'AI 驱动的个人数字基地，集成博客、项目、AI 对话与知识库',
  siteUrl: 'https://hagent.example.com',
})

const baseSaving = ref(false)

async function saveBase() {
  baseSaving.value = true
  try {
    await systemApi.updateSettings({
      'site.name': baseForm.siteName,
      'site.description': baseForm.description,
      'site.url': baseForm.siteUrl,
    })
    message.success('基础设置已保存')
  } catch {
    // 已 toast
  } finally {
    baseSaving.value = false
  }
}

/* ---------- AI 设置 ---------- */
interface FallbackModel {
  name: string
  baseUrl: string
  apiKey: string
  order: number
}

interface AiForm {
  chatModel: string
  chatBaseUrl: string
  chatApiKey: string
  temperature: number
  maxTokens: number
  systemPrompt: string
  enableRag: boolean
  embModel: string
  embBaseUrl: string
  embApiKey: string
  embDimension: number
  vectorIndex: string
  visModel: string
  visBaseUrl: string
  visApiKey: string
  fallbackModels: FallbackModel[]
}

const aiForm = reactive<AiForm>({
  chatModel: 'mimo-v2.5',
  chatBaseUrl: '',
  chatApiKey: '',
  temperature: 0.7,
  maxTokens: 4096,
  systemPrompt: '',
  enableRag: true,
  embModel: 'text-embedding-v4',
  embBaseUrl: '',
  embApiKey: '',
  embDimension: 1024,
  vectorIndex: 'hagent_rag',
  visModel: 'mimo-v2.5',
  visBaseUrl: '',
  visApiKey: '',
  fallbackModels: [],
})

const initialEmbDimension = ref(1024)

const aiSaving = ref(false)
const aiTesting = ref(false)
const aiTestStatus = ref<string>('')
const aiTestResult = ref<string>('')
const embTesting = ref(false)
const embTestStatus = ref<string>('')
const embTestResult = ref<string>('')

function levelLabel(idx: number): string {
  const labels = ['二级', '三级', '四级', '五级', '六级', '七级', '八级', '九级', '十级']
  return labels[idx] ?? `${idx + 2}级`
}

const fallbackOrderHint = computed(() => {
  if (aiForm.fallbackModels.length === 0) return ''
  return aiForm.fallbackModels.map((_, i) => levelLabel(i)).join(' → ')
})

function normalizeOrder() {
  aiForm.fallbackModels.forEach((m, i) => (m.order = i))
}

function addFallback() {
  aiForm.fallbackModels.push({
    name: '',
    baseUrl: '',
    apiKey: '',
    order: aiForm.fallbackModels.length,
  })
}

function removeFallback(idx: number) {
  aiForm.fallbackModels.splice(idx, 1)
  normalizeOrder()
}

function moveUp(idx: number) {
  if (idx <= 0) return
  const arr = aiForm.fallbackModels
  const tmp = arr[idx - 1]
  arr[idx - 1] = arr[idx]
  arr[idx] = tmp
  normalizeOrder()
}

function moveDown(idx: number) {
  if (idx >= aiForm.fallbackModels.length - 1) return
  const arr = aiForm.fallbackModels
  const tmp = arr[idx + 1]
  arr[idx + 1] = arr[idx]
  arr[idx] = tmp
  normalizeOrder()
}

async function saveAi() {
  aiSaving.value = true
  try {
    const fallbackPayload = aiForm.fallbackModels.map((m, i) => ({
      name: m.name.trim(),
      baseUrl: m.baseUrl.trim(),
      apiKey: m.apiKey,
      order: i,
    }))
    // 过滤空行（名称与 URL 均空则忽略，保留提示由空数组兜底）
    const filtered = fallbackPayload.filter((m) => m.name || m.baseUrl)
    filtered.forEach((m, i) => (m.order = i))
    await systemApi.updateSettings({
      'ai.chat.model': aiForm.chatModel,
      'ai.chat.base-url': aiForm.chatBaseUrl,
      'ai.chat.api-key': aiForm.chatApiKey,
      'ai.default_temperature': String(aiForm.temperature),
      'ai.default_max_tokens': String(aiForm.maxTokens),
      'ai.system_prompt': aiForm.systemPrompt,
      'ai.enable_rag': aiForm.enableRag ? 'true' : 'false',
      'ai.embedding.model': aiForm.embModel,
      'ai.embedding.base-url': aiForm.embBaseUrl,
      'ai.embedding.api-key': aiForm.embApiKey,
      'ai.vector-dimension': String(aiForm.embDimension),
      'ai.vector-index': aiForm.vectorIndex,
      'ai.vision.model': aiForm.visModel,
      'ai.vision.base-url': aiForm.visBaseUrl,
      'ai.vision.api-key': aiForm.visApiKey,
      'ai.fallback.chat.enabled': filtered.length > 0 ? 'true' : 'false',
      'ai.fallback.chat.models': JSON.stringify(filtered),
    })
    await loadSettings()
    message.success('AI 配置已保存并生效')
  } catch {
    // 已 toast
  } finally {
    aiSaving.value = false
  }
}

async function testTextModel() {
  if (!aiForm.chatModel || !aiForm.chatBaseUrl || !aiForm.chatApiKey) {
    message.warning('请先填写文本模型的模型名、Base URL 与 API Key')
    return
  }
  aiTesting.value = true
  aiTestResult.value = ''
  try {
    const res = await systemApi.testModel({
      baseUrl: aiForm.chatBaseUrl,
      apiKey: aiForm.chatApiKey,
      model: aiForm.chatModel,
    })
    aiTestStatus.value = res.status
    aiTestResult.value = res.message
  } catch (e: any) {
    aiTestStatus.value = 'error'
    aiTestResult.value = e?.message || '连接测试失败'
  } finally {
    aiTesting.value = false
  }
}

async function testEmbModel() {
  if (!aiForm.embModel || !aiForm.embBaseUrl || !aiForm.embApiKey) {
    message.warning('请先填写向量模型的模型名、Base URL 与 API Key')
    return
  }
  embTesting.value = true
  embTestResult.value = ''
  try {
    const res = await systemApi.testEmbedding({
      baseUrl: aiForm.embBaseUrl,
      apiKey: aiForm.embApiKey,
      model: aiForm.embModel,
    })
    embTestStatus.value = res.status
    const dim = (res as any).dimension
    const preview = (res as any).preview
    embTestResult.value = dim
      ? `${res.message}（维度 ${dim}${preview ? `，预览 ${preview}` : ''}）`
      : res.message
  } catch (e: any) {
    embTestStatus.value = 'error'
    embTestResult.value = e?.message || '向量模型测试失败'
  } finally {
    embTesting.value = false
  }
}

/* ---------- 个人中心 ---------- */
interface ProfileForm {
  nickname: string
  email: string
}

const profileForm = reactive<ProfileForm>({
  nickname: 'Hui',
  email: '',
})

const profileSaving = ref(false)

async function loadProfile() {
  try {
    const me = await authApi.me()
    profileForm.nickname = me.username || ''
  } catch {
    // 已 toast
  }
}

async function saveProfile() {
  profileSaving.value = true
  try {
    await authApi.updateProfile({ nickname: profileForm.nickname })
    message.success('个人资料已保存')
  } catch {
    // 已 toast
  } finally {
    profileSaving.value = false
  }
}

interface PwdForm {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

const pwdForm = reactive<PwdForm>({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const pwdSaving = ref(false)

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    message.warning('请填写完整密码信息')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    message.error('两次输入的新密码不一致')
    return
  }
  pwdSaving.value = true
  try {
    await authApi.changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    message.success('密码已更新')
  } catch {
    // 已 toast
  } finally {
    pwdSaving.value = false
  }
}

/* ---------- mock 上传 ---------- */
function mockAvatarUpload({ file, onFinish }: UploadCustomRequestOptions) {
  setTimeout(() => {
    onFinish()
    message.success(`头像「${file.name}」已上传（mock）`)
  }, 500)
}
</script>

<style scoped>
.settings-page {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
}

.settings-card,
.settings-wrap {
  /* 内容多（AI / Embedding / 个人中心），原 1080px 上限让右侧大片空白，去掉 */
  width: 100%;
  margin: 0 auto;
  padding: 24px 32px;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  box-sizing: border-box;
  overflow-x: hidden;
}

.settings-pane {
  padding: var(--space-4) 0 0;
  max-width: none;
  width: 100%;
  box-sizing: border-box;
}

/* 左侧导航：给内容区一个高度下限，字段少的 tab（站点设置）不会让页面塌缩 */
.settings-tabs {
  min-height: 380px;
}

/* ---------- 网格系统 ---------- */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 24px;
  width: 100%;
  box-sizing: border-box;
}

.form-grid .span-full {
  grid-column: 1 / -1;
}

.form-grid .span-1 {
  grid-column: span 1;
}

.grid-placeholder {
  display: block;
}

.ai-main-grid {
  align-items: start;
}

/* ---------- 区块：每个 tab 内的语义分组（替代原来的 n-divider） ---------- */
.block {
  padding: var(--space-5) 0;
  border-top: 1px solid var(--border);
}
.block:first-child {
  border-top: none;
  padding-top: 0;
}
.block:last-child {
  padding-bottom: 0;
}

/* 带右侧操作按钮的区块头（如「新增备用」） */
.block__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}
.block__title {
  margin: 0 0 var(--space-4);
  font-size: var(--text-base);
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.4;
}
.block__head .block__title {
  margin-bottom: var(--space-1);
}
.block__desc {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-2);
  line-height: var(--lh-body);
  max-width: 68ch;
}

/* ---------- 备用模型卡片（替代原单行 4 输入 + 3 按钮的横排，窄屏不再挤爆） ---------- */
.fb-empty {
  padding: var(--space-6) var(--space-4);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--text-2);
  background: var(--bg-hover);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  line-height: var(--lh-body);
}

.fb-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.fb-card {
  padding: var(--space-4);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-sizing: border-box;
}
.fb-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}
.fb-card__actions {
  display: flex;
  align-items: center;
  gap: var(--space-px);
}

/* ---------- AI 动作区 / 测试结果 ---------- */
.ai-test-result {
  margin-top: var(--space-3);
  white-space: pre-wrap;
}

.dim-warn {
  margin-top: var(--space-3);
}

/* ---------- 保存行：原「吸底玻璃栏」，去掉 sticky 与外壳，与表单视觉一致 ---------- */
.save-bar {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  margin-top: var(--space-4);
}

/* ---------- 滑块 / 数字输入 ---------- */
.slider-full {
  width: 100%;
}
.num-full {
  width: 180px;
}

/* ---------- 头像 ---------- */
.avatar-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.avatar-placeholder {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  font-size: 24px;
  color: var(--text-3);
  background: var(--bg-hover);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
}
.avatar-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
}

/* ---------- 响应式 ---------- */
/* 断点沿用 480 / 767 / 1023 三档 */
@media (max-width: 1023px) {
  .block {
    padding: var(--space-4) 0;
  }
}

@media (max-width: 767px) {
  .settings-card {
    padding: 16px;
  }
  /* 单列：span-1 与 span-full 同宽，避免窄屏出现半宽输入框 */
  .form-grid {
    grid-template-columns: 1fr;
  }
  .form-grid .span-1 {
    grid-column: 1 / -1;
  }
  .block__head {
    flex-direction: column;
    align-items: stretch;
  }
  .fb-card__head {
    flex-wrap: wrap;
  }
  .fb-card__actions {
    width: 100%;
    justify-content: flex-end;
  }
  .save-bar {
    flex-wrap: wrap;
  }
}

@media (max-width: 480px) {
  .settings-card {
    padding: 12px;
  }
  .save-bar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
