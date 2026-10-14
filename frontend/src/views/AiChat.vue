<template>
  <div class="ai-page">
    <div class="ai-box">
      <!-- 顶部 -->
      <div class="ai-header">
        <span class="ai-title">🤖 AI 助手</span>
        <span class="ai-sub">个人学习/生活问答 · 仅自己可见</span>
        <div class="ai-header-actions">
          <el-button v-if="messages.length" link type="danger" @click="clearAll">清空会话</el-button>
        </div>
      </div>

      <!-- 对话区 -->
      <div ref="msgListRef" class="ai-body">
        <div v-if="!messages.length" class="ai-welcome">
          <div class="ai-welcome-icon">💡</div>
          <p class="ai-welcome-title">有什么想问的？</p>
          <p class="ai-welcome-sub">我可以帮你总结学习笔记、回忆共同的时光</p>
          <div class="ai-suggests">
            <button v-for="s in suggests" :key="s" class="ai-suggest" @click="quickAsk(s)">{{ s }}</button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="ai-msg" :class="m.role">
          <div class="ai-msg-avatar">{{ m.role === 'user' ? '我' : '🤖' }}</div>
          <div class="ai-msg-content">
            <div class="ai-bubble">
              <!-- 用户输入与 AI 输出统一按 Markdown 渲染 -->
              <div class="markdown-body" v-html="renderMarkdown(m.content)" />
              <span v-if="m.role === 'assistant' && m.loading" class="cursor">▍</span>
            </div>
            <!-- 引用出处 -->
            <div v-if="m.refs && m.refs.length" class="ai-refs">
              <el-tag v-for="(r, ri) in m.refs" :key="ri" size="small" type="info" effect="plain" round
                class="ai-ref-tag" :title="JSON.stringify(r)">
                📎 {{ refLabel(r) }}
              </el-tag>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="ai-input-wrap">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 4 }"
          resize="none"
          placeholder="输入问题，Enter 发送（Shift+Enter 换行）"
          :disabled="generating"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" round :loading="generating" :disabled="!draft.trim() || generating" @click="send">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import * as agentApi from '@/api/agent'
import { renderMarkdown } from '@/utils/markdown'

const userStore = useUserStore()
const msgListRef = ref()
const messages = ref([])
const draft = ref('')
const generating = ref(false)

const suggests = [
  '帮我总结一下最近的学习笔记',
  '我们上次一起去旅行的地方有什么回忆？',
  '我最近的待办完成得怎么样？'
]

// 会话历史本地持久化（个人域，按用户隔离）
const HISTORY_KEY = () => `ai-chat-${userStore.userInfo?.id ?? ''}`

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY())
    if (raw) messages.value = JSON.parse(raw)
  } catch (e) { /* 忽略 */ }
}

function saveHistory() {
  try {
    localStorage.setItem(HISTORY_KEY(), JSON.stringify(messages.value.filter((m) => !m.loading)))
  } catch (e) { /* 忽略 */ }
}

function scrollToBottom() {
  nextTick(() => {
    const el = msgListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function send() {
  const text = draft.value.trim()
  if (!text || generating.value) return
  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistant = { role: 'assistant', content: '', loading: true, refs: [] }
  messages.value.push(assistant)
  scrollToBottom()
  generating.value = true

  await agentApi.streamChat(text, {
    onDelta: (d) => {
      assistant.content += d
      scrollToBottom()
    },
    onRefs: (refs) => {
      assistant.refs = refs
    },
    onError: (msg) => {
      if (!assistant.content) assistant.content = msg
    },
    onDone: () => {
      assistant.loading = false
      if (!assistant.content) assistant.content = '（无回复）'
      generating.value = false
      saveHistory()
      scrollToBottom()
    }
  })
  // onDone 在流结束时触发；若异常提前 return 也兜底
  if (assistant.loading) {
    assistant.loading = false
    generating.value = false
    saveHistory()
  }
}

function quickAsk(s) {
  draft.value = s
  send()
}

function refLabel(r) {
  const type = (r && r.biz_type) || ''
  const map = { moment: '动态', photo: '相册照片', note: '学习笔记' }
  return `${map[type] || '资料'}${r && r.title ? '·' + r.title : ''}`
}

function clearAll() {
  ElMessageBox.confirm('确定清空本次会话记录吗？', '提示', {
    confirmButtonText: '清空',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      messages.value = []
      saveHistory()
      ElMessage.success('会话已清空')
    })
    .catch(() => { })
}

onMounted(() => {
  if (!userStore.token) return
  if (!userStore.userInfo) {
    userStore.fetchMe().catch(() => {})
  }
  loadHistory()
})
</script>

<style scoped>
.ai-page {
  height: calc(100vh - 108px);
  min-height: 480px;
}

.ai-box {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: rgba(255, 255, 255, 0.85);
  border-radius: 14px;
  box-shadow: 0 4px 20px rgba(162, 109, 232, 0.08);
  overflow: hidden;
}

.ai-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(162, 109, 232, 0.15);
  background: rgba(245, 240, 255, 0.7);
}

.ai-title {
  font-weight: 700;
  color: var(--sem-accent-strong);
}

.ai-sub {
  font-size: 12px;
  color: var(--text-weak);
  flex: 1;
}

.ai-header-actions {
  flex-shrink: 0;
}

.ai-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.ai-welcome {
  text-align: center;
  padding: 60px 0 40px;
}

.ai-welcome-icon {
  font-size: 44px;
}

.ai-welcome-title {
  font-size: 17px;
  font-weight: 600;
  margin: 12px 0 4px;
  color: var(--text-strong);
}

.ai-welcome-sub {
  font-size: 13px;
  color: var(--text-weak);
  margin-bottom: 18px;
}

.ai-suggests {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}

.ai-suggest {
  border: 1px solid rgba(162, 109, 232, 0.35);
  background: #fff;
  color: var(--sem-accent-strong);
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.ai-suggest:hover {
  background: var(--sem-accent-soft);
  border-color: var(--sem-accent);
}

.ai-msg {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.ai-msg.user {
  flex-direction: row-reverse;
}

.ai-msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
  background: linear-gradient(135deg, var(--sem-accent-soft), var(--sem-accent));
  color: #fff;
}

.ai-msg.user .ai-msg-avatar {
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--el-color-primary));
}

.ai-msg-content {
  max-width: 72%;
}

.ai-msg.user .ai-msg-content {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.ai-bubble {
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
  color: var(--text-strong);
  background: #fff;
  border: 1px solid rgba(162, 109, 232, 0.15);
}

.ai-msg.user .ai-bubble {
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--el-color-primary-light-5));
  border-color: transparent;
}

/* AI 输出渲染成 markdown 后去掉 pre-wrap（否则 HTML 源码里的换行会变成额外空行） */
.ai-bubble .markdown-body {
  white-space: normal;
}

.cursor {
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.ai-refs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.ai-ref-tag {
  cursor: pointer;
}

.ai-input-wrap {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid rgba(162, 109, 232, 0.12);
  background: rgba(255, 255, 255, 0.95);
}
</style>