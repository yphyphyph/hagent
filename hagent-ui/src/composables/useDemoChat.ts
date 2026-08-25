import { ref, type Ref } from 'vue'
import type { UiChatMessage } from '@/composables/useChat'

/** 待确认操作卡片类型（复用共享消息模型，不改动 useChat 的导出） */
type DemoPendingAction = NonNullable<UiChatMessage['pendingAction']>

/** 演示剧本：思考阶段时长（毫秒） */
const THINKING_MS = 600
/** 演示剧本：逐字流式输出间隔（毫秒/字符） */
const STREAM_TICK_MS = 25

/** 演示剧本：知识库问答的预置回复（标题 + 要点列表 + 代码块，用于展示 Markdown 高亮渲染） */
const DEMO_ANSWER: string = `### 什么是 HAgent？

HAgent 是一个企业级 AI 智能体平台，核心能力包括：

- **知识库问答**：基于私有文档的检索增强回答（RAG）
- **数据分析**：自然语言转 SQL，先确认后执行
- **图片识别**：上传图片即可提取信息并参与对话

下面是一条统计查询的示例：

\`\`\`sql
SELECT COUNT(*) AS user_count
FROM hagent_user;
\`\`\`

> 提示：登录后可绑定真实数据源执行以上查询。`

/** 演示剧本：待确认操作卡片的引导语 */
const DEMO_INTRO: string = '我已生成一条统计查询，请确认后执行：'

/** 演示剧本：结尾登录引导语 */
const DEMO_GUIDE: string =
  '以上为本地演示数据，登录后可体验真实 AI 能力（知识库问答 / 数据分析 / 图片识别）'

/** 演示剧本：预置待确认操作（id=-1 表示本地演示数据，不与真实记录冲突） */
const DEMO_PENDING_ACTION: DemoPendingAction = {
  id: -1,
  sql: 'SELECT COUNT(*) AS user_count FROM hagent_user',
  question: '统计用户数量（演示数据）',
  status: 'pending',
}

/** 时间格式化：HH:mm（补零） */
function nowTime(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 访客演示播放器（纯本地剧本，无任何网络请求）：
 * - play() 重放固定对话脚本：用户提问 → 思考 → 知识库回答（逐字流式）→ 待确认操作卡片 → 登录引导
 * - confirmDemo()/cancelDemo() 仅本地翻转卡片状态
 * - stop() 统一清理全部定时器；play() 会先停止上一轮播放，重复调用安全（无定时器叠加）
 */
export function useDemoChat() {
  const messages: Ref<UiChatMessage[]> = ref([])
  const thinking = ref(false)
  const streaming = ref(false)

  /** 全部未触发的定时器 ID（stop 时统一清理，防止双播重叠） */
  const timers = new Set<ReturnType<typeof setTimeout>>()

  /** 注册定时器，触发后自动移除记录 */
  function schedule(fn: () => void, ms: number): void {
    const id = setTimeout(() => {
      timers.delete(id)
      fn()
    }, ms)
    timers.add(id)
  }

  /** 停止播放：清理全部定时器并复位状态 */
  function stop(): void {
    for (const id of timers) clearTimeout(id)
    timers.clear()
    thinking.value = false
    streaming.value = false
  }

  /** 向目标消息逐字流式写入文本，写完后回调 onDone */
  function streamInto(msg: UiChatMessage, full: string, onDone?: () => void): void {
    let i = 0
    const tick = (): void => {
      if (i >= full.length) {
        onDone?.()
        return
      }
      msg.content += full.charAt(i)
      i += 1
      schedule(tick, STREAM_TICK_MS)
    }
    tick()
  }

  /**
   * 播放演示对话（重复调用会先停止上一轮，保证状态完全复位）
   * @param userText 用户侧展示的提问文本
   */
  function play(userText: string): void {
    // 先终止可能存在的上一轮播放，避免定时器叠加
    stop()

    messages.value = [{ id: 'm1', role: 'user', content: userText, time: nowTime() }]

    // 阶段一：思考 600ms 后开始流式输出知识库回答（带工具徽章）
    thinking.value = true
    schedule(() => {
      thinking.value = false
      streaming.value = true

      const answerMsg: UiChatMessage = {
        id: 'm2',
        role: 'ai',
        content: '',
        time: nowTime(),
        tools: ['searchKnowledge'],
      }
      messages.value.push(answerMsg)

      streamInto(answerMsg, DEMO_ANSWER, () => {
        // 阶段二：追加携带待确认操作卡片的消息并输出引导语
        const actionMsg: UiChatMessage = {
          id: 'm3',
          role: 'ai',
          content: '',
          time: nowTime(),
          pendingAction: { ...DEMO_PENDING_ACTION },
        }
        messages.value.push(actionMsg)

        streamInto(actionMsg, DEMO_INTRO, () => {
          // 阶段三：追加登录引导消息，播放结束
          messages.value.push({ id: 'm4', role: 'ai', content: DEMO_GUIDE, time: nowTime() })
          streaming.value = false
        })
      })
    }, THINKING_MS)
  }

  /** 确认演示操作：本地翻转状态并回填结果文本（无网络请求） */
  function confirmDemo(action: DemoPendingAction): void {
    action.status = 'confirmed'
    action.resultText = '执行完成：返回 1 行'
  }

  /** 取消演示操作：本地翻转状态（无网络请求） */
  function cancelDemo(action: DemoPendingAction): void {
    action.status = 'cancelled'
  }

  return { messages, thinking, streaming, play, stop, confirmDemo, cancelDemo }
}
