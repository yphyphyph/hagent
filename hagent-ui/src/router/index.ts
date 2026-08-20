import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { nextTick } from 'vue'
import { isViewTransitionSupported, startViewTransition } from '@/composables/useViewTransition'
import { isLoggedIn } from '@/utils/auth'

/**
 * HAgent 独立 AI 管理端路由：
 * 仅保留登录页与 AI 管理后台（AI 对话 / AI 工具 / 工具执行 / 知识库 / 设置）。
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'Login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录', public: true } },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/ai/chat',
    children: [
      { path: 'ai/chat', name: 'AdminAiChat', component: () => import('@/views/admin/AiChatView.vue'), meta: { title: 'AI 对话' } },
      { path: 'ai/tools', name: 'AdminAiTools', component: () => import('@/views/admin/AiToolsView.vue'), meta: { title: 'AI 工具' } },
      { path: 'ai/tools/execute/:key', name: 'AdminAiToolExecute', component: () => import('@/views/admin/AiToolExecuteView.vue'), meta: { title: '工具执行' } },
      { path: 'ai/knowledge', name: 'AdminKnowledge', component: () => import('@/views/admin/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'ai/settings', name: 'AdminSettings', component: () => import('@/views/admin/SettingsView.vue'), meta: { title: '设置' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/ai/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

/* 导航守卫：未登录访问管理端跳转登录页，已登录访问登录页跳转 AI 对话 */
router.beforeEach((to, _from, next) => {
  const title = to.meta.title as string
  if (title) document.title = `${title} · HAgent`
  const publicPage = to.meta.public === true
  if (!publicPage && !isLoggedIn()) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.path === '/login' && isLoggedIn()) {
    next({ path: '/ai/chat' })
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
