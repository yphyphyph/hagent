import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { nextTick } from 'vue'
import { isViewTransitionSupported, startViewTransition } from '@/composables/useViewTransition'
import { isLoggedIn } from '@/utils/auth'

/**
 * HAgent 路由：
 * - `/` 公开门户：对话即产品，访客免登录直接聊（临时会话）
 * - `/admin/*` 管理后台：AI 工具 / 知识库 / 设置（旧对话入口已下线，重定向到门户首页）
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/PortalLayout.vue'),
    meta: { public: true },
    children: [
      {
        path: '',
        name: 'PortalChat',
        component: () => import('@/views/portal/PortalChatView.vue'),
        meta: { title: 'HAgent · AI 智能助手' },
      },
    ],
  },
  { path: '/login', name: 'Login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录', public: true } },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/admin/tools',
    children: [
      /* 旧入口兜底：管理端 AI 对话已并入门户首页，老链接重定向避免 404 */
      { path: 'chat', redirect: { path: '/' } },
      { path: 'tools', name: 'AdminAiTools', component: () => import('@/views/admin/AiToolsView.vue'), meta: { title: 'AI 工具' } },
      { path: 'tools/execute/:key', name: 'AdminAiToolExecute', component: () => import('@/views/admin/AiToolExecuteView.vue'), meta: { title: '工具执行' } },
      { path: 'prompts', name: 'AdminPrompts', component: () => import('@/views/admin/PromptView.vue'), meta: { title: '提示词库' } },
      { path: 'mcp', name: 'AdminMcp', component: () => import('@/views/admin/McpView.vue'), meta: { title: 'MCP 工具' } },
      { path: 'knowledge', name: 'AdminKnowledge', component: () => import('@/views/admin/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'sql-policy', name: 'AdminSqlPolicy', component: () => import('@/views/admin/SqlPolicyView.vue'), meta: { title: 'SQL 表策略' } },
      {
        path: 'logs',
        name: 'SystemLog',
        component: () => import('@/views/admin/SystemLogView.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '操作日志' },
      },
      { path: 'settings', name: 'AdminSettings', component: () => import('@/views/admin/SettingsView.vue'), meta: { title: '设置' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const ADMIN_HOME = '/admin/tools'

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

/* 导航守卫：未登录访问管理端跳转登录页，已登录访问登录页跳转管理台首页 */
router.beforeEach((to, _from, next) => {
  const title = to.meta.title as string | undefined
  if (title) document.title = `${title}`
  const publicPage = to.meta.public === true
  if (!publicPage && !isLoggedIn()) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.path === '/login' && isLoggedIn()) {
    next({ path: ADMIN_HOME })
    return
  }
  window.dispatchEvent(new Event('hs:route-start'))
  next()
})

router.afterEach(() => {
  window.dispatchEvent(new Event('hs:route-end'))
})

router.onError(() => {
  window.dispatchEvent(new Event('hs:route-end'))
})

/* ---------- View Transitions API：支持时用原生跨页过渡，否则回退 Vue <Transition> ----------
 * startViewTransition 回调同步执行，旧状态在 DOM 变更前被捕获；
 * await push + nextTick 确保新状态截图发生在 Vue 渲染提交之后。
 */
if (isViewTransitionSupported()) {
  const originalPush = router.push.bind(router)
  router.push = ((to: Parameters<typeof router.push>[0]) => {
    let result!: ReturnType<typeof router.push>
    startViewTransition(async () => {
      result = originalPush(to) as ReturnType<typeof router.push>
      await result
      await nextTick()
    })
    return result
  }) as typeof router.push

  const originalReplace = router.replace.bind(router)
  router.replace = ((to: Parameters<typeof router.replace>[0]) => {
    let result!: ReturnType<typeof router.replace>
    startViewTransition(async () => {
      result = originalReplace(to) as ReturnType<typeof router.replace>
      await result
      await nextTick()
    })
    return result
  }) as typeof router.replace
}

export default router
