<template>
  <div class="settings-page">
    <n-card class="settings-card" :bordered="true">
      <n-tabs type="line" animated>
        <!-- 基础设置 -->
        <n-tab-pane name="base" tab="基础设置">
          <div class="settings-pane">
            <n-form :model="baseForm" label-placement="left" label-width="96" size="large">
              <n-form-item label="站点名称">
                <n-input v-model:value="baseForm.siteName" placeholder="HAgent" />
              </n-form-item>
              <n-form-item label="站点描述">
                <n-input
                  v-model:value="baseForm.description"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 4 }"
                  placeholder="一句话介绍你的站点"
                />
              </n-form-item>
              <n-form-item label="站点链接">
                <n-input v-model:value="baseForm.siteUrl" placeholder="https://example.com" />
              </n-form-item>
              <n-form-item label=" ">
                <n-button type="primary" :loading="baseSaving" @click="saveBase">保存设置</n-button>
              </n-form-item>
            </n-form>
          </div>
        </n-tab-pane>

        <!-- AI 设置 -->
        <n-tab-pane name="ai" tab="AI 设置">
          <div class="settings-pane">
            <n-divider title-placement="left">文本大模型（对话 / 工具）</n-divider>
            <n-form :model="aiForm" label-placement="left" label-width="120" size="large">
              <n-form-item label="模型名称">
                <n-input v-model:value="aiForm.chatModel" placeholder="如 mimo-v2.5 / deepseek-chat" />
              </n-form-item>
              <n-form-item label="API Base URL">
                <n-input v-model:value="aiForm.chatBaseUrl" placeholder="https://api.example.com/v1" />
              </n-form-item>
              <n-form-item label="API Key">
                <n-input v-model:value="aiForm.chatApiKey" type="password" show-password-on="click" placeholder="sk-****" />
              </n-form-item>
              <n-form-item label="温度">
                <n-slider v-model:value="aiForm.temperature" :min="0" :max="2" :step="0.1" :format-tooltip="(v: number) => v.toFixed(1)" class="slider-full" />
              </n-form-item>
              <n-form-item label="Max Tokens">
                <n-input-number v-model:value="aiForm.maxTokens" :min="256" :max="8192" :step="256" class="num-full" />
              </n-form-item>
              <n-form-item label="系统提示词">
                <n-input v-model:value="aiForm.systemPrompt" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="AI 助手的基础系统提示词" />
              </n-form-item>
              <n-form-item label="启用 RAG">
                <n-checkbox v-model:checked="aiForm.enableRag">开启知识库检索问答</n-checkbox>
              </n-form-item>
            </n-form>

            <n-divider title-placement="left">向量大模型（Embedding）</n-divider>
            <n-form :model="aiForm" label-placement="left" label-width="120" size="large">
              <n-form-item label="模型名称">
                <n-input v-model:value="aiForm.embModel" placeholder="如 text-embedding-v4" />
              </n-form-item>
              <n-form-item label="API Base URL">
                <n-input v-model:value="aiForm.embBaseUrl" placeholder="https://api.example.com/v1" />
              </n-form-item>
              <n-form-item label="API Key">
                <n-input v-model:value="aiForm.embApiKey" type="password" show-password-on="click" placeholder="sk-****" />
              </n-form-item>
              <n-form-item label="向量索引名">
                <n-input v-model:value="aiForm.vectorIndex" placeholder="hagent_rag" />
              </n-form-item>
            </n-form>

            <n-divider title-placement="left">多模态 / 视觉大模型（图像分析）</n-divider>
            <n-form :model="aiForm" label-placement="left" label-width="120" size="large">
              <n-form-item label="模型名称">
                <n-input v-model:value="aiForm.visModel" placeholder="如 mimo-v2.5 / qwen-vl-plus" />
              </n-form-item>
              <n-form-item label="API Base URL">
                <n-input v-model:value="aiForm.visBaseUrl" placeholder="https://api.example.com/v1" />
              </n-form-item>
              <n-form-item label="API Key">
                <n-input v-model:value="aiForm.visApiKey" type="password" show-password-on="click" placeholder="sk-****" />
              </n-form-item>
            </n-form>

            <n-alert v-if="aiTestResult" :type="aiTestStatus === 'success' ? 'success' : 'error'" class="ai-test-result">
              {{ aiTestResult }}
            </n-alert>

            <div class="ai-actions">
              <n-button :loading="aiTesting" secondary @click="testTextModel">测试文本模型连接</n-button>
              <n-button type="primary" :loading="aiSaving" @click="saveAi">保存 AI 配置</n-button>
            </div>
          </div>
        </n-tab-pane>

        <!-- 个人中心 -->
        <n-tab-pane name="profile" tab="个人中心">
          <div class="settings-pane">
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
              <n-form-item label="昵称">
                <n-input v-model:value="profileForm.nickname" placeholder="你的昵称" />
              </n-form-item>
              <n-form-item label="邮箱">
                <n-input v-model:value="profileForm.email" placeholder="you@example.com" />
              </n-form-item>
              <n-form-item label=" ">
                <n-button type="primary" :loading="profileSaving" @click="saveProfile">保存个人资料</n-button>
              </n-form-item>
            </n-form>

            <n-divider title-placement="left">修改密码</n-divider>
            <n-form :model="pwdForm" label-placement="left" label-width="96" size="large">
              <n-form-item label="旧密码">
                <n-input v-model:value="pwdForm.oldPassword" type="password" show-password-on="click" placeholder="请输入旧密码" />
              </n-form-item>
              <n-form-item label="新密码">
                <n-input v-model:value="pwdForm.newPassword" type="password" show-password-on="click" placeholder="至少 6 位" />
              </n-form-item>
              <n-form-item label="确认密码">
                <n-input v-model:value="pwdForm.confirmPassword" type="password" show-password-on="click" placeholder="再次输入新密码" />
              </n-form-item>
              <n-form-item label=" ">
                <n-button type="primary" :loading="pwdSaving" @click="changePassword">更新密码</n-button>
              </n-form-item>
            </n-form>
          </div>
        </n-tab-pane>
      </n-tabs>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NDivider,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSlider,
  NTabPane,
  NTabs,
  NUpload,
  useMessage,
  type UploadCustomRequestOptions,
} from 'naive-ui'
import { UserOutlined } from '@vicons/antd'
import { systemApi } from '@/api/system'
import { authApi } from '@/api/auth'

const message = useMessage()

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
    aiForm.vectorIndex = pick(['ai.vector-index'], 'hagent_rag')
    aiForm.visModel = pick(['ai.vision.model'], 'mimo-v2.5')
    aiForm.visBaseUrl = pick(['ai.vision.base-url'], '')
    aiForm.visApiKey = pick(['ai.vision.api-key'], '')
  } catch {
    // 错误已由 request 拦截器统一 toast
  }
}

onMounted(() => {
  loadSettings()
  loadProfile()
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
  vectorIndex: string
  visModel: string
  visBaseUrl: string
  visApiKey: string
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
  vectorIndex: 'hagent_rag',
  visModel: 'mimo-v2.5',
  visBaseUrl: '',
  visApiKey: '',
})

const aiSaving = ref(false)
const aiTesting = ref(false)
const aiTestStatus = ref<string>('')
const aiTestResult = ref<string>('')

async function saveAi() {
  aiSaving.value = true
  try {
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
      'ai.vector-index': aiForm.vectorIndex,
      'ai.vision.model': aiForm.visModel,
      'ai.vision.base-url': aiForm.visBaseUrl,
      'ai.vision.api-key': aiForm.visApiKey,
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
    profileForm.nickname = me.username || ""
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
.settings-card {
  max-width: 760px;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
}

.settings-pane {
  padding: var(--space-4) 0;
  max-width: 560px;
}

/* ---------- AI 动作区 / 测试结果 ---------- */
.ai-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
.ai-test-result {
  margin-top: var(--space-3);
  white-space: pre-wrap;
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
</style>
