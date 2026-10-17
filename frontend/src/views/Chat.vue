<template>
  <div class="chat-page">
    <!-- 未绑定提示 -->
    <div v-if="!userStore.isBound" class="chat-unbound">
      <el-empty description="绑定情侣后才能开始聊天哦～">
        <el-button type="primary" round @click="$router.push('/bind')">去绑定</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div class="chat-box">
        <!-- 顶部 -->
        <div class="chat-header">
          <span class="chat-header-title">💬 和 Ta 的悄悄话</span>
          <el-tag v-if="!connected" type="warning" effect="light" size="small" round>
            {{ reconnecting ? '连接断开，重连中…' : '连接中…' }}
          </el-tag>
          <el-tag v-else type="success" effect="light" size="small" round>已连接</el-tag>
        </div>

        <!-- 消息区 -->
        <div ref="msgListRef" class="chat-body" @scroll="onScroll">
          <div v-if="loadingHistory" class="chat-loading">
            <el-icon class="is-loading"><Loading /></el-icon> 加载历史中…
          </div>
          <div v-else-if="!messages.length" class="chat-empty">
            <p>还没有消息，说句"你好"开启对话吧 💕</p>
            <p class="chat-empty-tip">点 <b>@bot</b> 或输入 <b>@</b> 召唤 AI 助手</p>
          </div>

          <div v-for="m in messages" :key="m._key" class="msg-row" :class="isMine(m) ? 'mine' : 'other'">
            <div class="msg-avatar">{{ avatarOf(m) }}</div>
            <div class="msg-main">
              <div v-if="m.type === 'AGENT'" class="msg-agent-tag">🤖 AI 助手</div>
              <div v-else-if="m.msgType === 'BOT'" class="msg-bot-tag">🤖 AI 提问</div>
              <div class="msg-bubble" :class="{ agent: m.type === 'AGENT', _failed: m._failed }">
                <template v-if="m.type === 'IMAGE'">
                  <img v-if="m.imgOk !== false" :src="m.content" class="msg-img clickable-img" @error="m.imgOk = false"
                    @click="openImageViewer(m)" />
                  <span v-else class="msg-img-broken">🖼️ 图片已删除</span>
                </template>
                <template v-else-if="m.content">
                  <template v-if="m.type === 'AGENT'">
                    <!-- AI 回答按 Markdown 渲染（标题/列表/代码/公式），流式期间光标追加在末尾 -->
                    <div class="markdown-body" v-html="renderMarkdown(m.content)" />
                    <span v-if="!m.done" class="cursor">▍</span>
                  </template>
                  <span v-else>{{ m.content }}</span>
                </template>
              </div>
              <div class="msg-time">{{ timeOf(m) }}</div>
            </div>
            <!-- 发送失败：手动重发（透传原 clientMsgId，服务端幂等） -->
            <div v-if="m._failed" class="msg-retry-box">
              <span class="msg-failed-tip">发送失败</span>
              <el-button size="small" type="warning" round @click="retrySend(m)">重发</el-button>
            </div>
          </div>
        </div>

        <!-- 回到最底部：不在底部时显示（有新消息优先提示） -->
        <transition name="el-fade-in">
          <div v-if="!atBottom" class="chat-new-float" @click="scrollToBottom()">
            {{ showNewFloat ? '有新消息 ↓' : '回到最底部 ↓' }}
          </div>
        </transition>

        <!-- 图片放大查看：支持 ←/→ 切换本次会话中的其他图片 -->
        <el-image-viewer
          v-if="imgViewerVisible"
          :url-list="chatImageUrls"
          :initial-index="imgViewerIndex"
          teleported
          @close="imgViewerVisible = false"
        />

        <!-- 输入区 -->
        <div class="chat-input-wrap">
          <!-- @ 候选：输入框内输入 @ 时弹出 -->
          <transition name="el-fade-in">
            <div v-if="atMenuVisible" class="chat-at-menu">
              <div class="chat-at-item" @click="chooseAtBot">
                <span class="chat-at-avatar">🤖</span>
                <div class="chat-at-info">
                  <span class="chat-at-name">AI 助手</span>
                  <span class="chat-at-desc">召唤 AI 回答问题</span>
                </div>
              </div>
            </div>
          </transition>
          <div class="chat-input-row">
            <el-tooltip content="召唤 AI 助手" placement="top">
              <el-button class="chat-ibot-btn" circle size="small" @click="insertAtBot">@</el-button>
            </el-tooltip>
            <!-- AI 助手 chip：标签实体，不进 draft 文本 -->
            <div v-if="botActive" class="chat-bot-chip">
              <span class="chat-bot-chip-text">🤖 AI 助手</span>
              <el-icon class="chat-bot-chip-x" @click="botActive = false"><Close /></el-icon>
            </div>
            <el-input
              v-model="draft"
              type="textarea"
              :rows="1"
              :autosize="{ minRows: 1, maxRows: 4 }"
              resize="none"
              :placeholder="botActive ? '输入你想问 AI 的问题…' : '说点什么…'"
              @input="onDraftInput"
              @keydown.enter.exact.prevent="sendText"
            />
            <el-tooltip content="发送图片" placement="top">
              <el-upload
                :action="uploadAction"
                :headers="uploadHeaders"
                :show-file-list="false"
                accept="image/*"
                :before-upload="beforeImageUpload"
                :on-success="handleImageSuccess"
                :on-error="handleImageError"
              >
                <el-button class="chat-img-btn" circle size="small">🖼️</el-button>
              </el-upload>
            </el-tooltip>
            <el-button type="primary" round :disabled="!draft.trim() && !botActive" @click="sendText">发送</el-button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, Close } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import * as chatApi from '@/api/chat'
import { renderMarkdown } from '@/utils/markdown'

const userStore = useUserStore()

const msgListRef = ref()
const messages = ref([])
const draft = ref('')
/** AI 助手 chip 开关（标签实体，不进入 draft 文本） */
const botActive = ref(false)
/** 输入框内输入 @ 时弹出的候选菜单 */
const atMenuVisible = ref(false)
const connected = ref(false)
const reconnecting = ref(false)
const loadingHistory = ref(false)
const showNewFloat = ref(false)
/** 是否在消息区底部（控制"回到最底部"按钮显隐） */
const atBottom = ref(true)
/** 图片放大查看器 */
const imgViewerVisible = ref(false)
const imgViewerIndex = ref(0)
const chatImageUrls = computed(() =>
  messages.value.filter((m) => m.type === 'IMAGE' && m.content).map((m) => m.content)
)

function openImageViewer(m) {
  const idx = chatImageUrls.value.findIndex((u) => u === m.content)
  imgViewerIndex.value = idx < 0 ? 0 : idx
  imgViewerVisible.value = true
}

let ws = null
let reconnectTimer = null
let reconnectAttempt = 0
let myId = null
const seenIds = new Set()
const seenClientIds = new Set()
/** 发送失败待重发队列（clientMsgId 不变，服务端幂等） */
const resendQueue = []
/** 本地失败占位：clientMsgId → 占位消息 _key（收到服务端同 id 广播时替换） */
const pendingByClientId = new Map()
/** 未读上报节流 */
let readTimer = null

/* ========== WS 生命周期 ========== */
function connectWs() {
  const token = localStorage.getItem('token')
  if (!token) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/chat?token=${encodeURIComponent(token)}`)

  ws.onopen = () => {
    connected.value = true
    reconnecting.value = false
    reconnectAttempt = 0
    // 重连成功后：先把失败队列重发，再拉增量
    flushResendQueue()
    pullAfterId()
  }

  ws.onmessage = (ev) => {
    let data
    try { data = JSON.parse(ev.data) } catch (e) { return }
    if (data.type === 'AGENT_STREAM') {
      handleAgentStream(data)
    } else {
      handleIncoming(data)
    }
  }

  ws.onclose = () => {
    connected.value = false
    scheduleReconnect()
  }
  ws.onerror = () => {
    ws && ws.close()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnecting.value = true
  const delay = Math.min(1000 * Math.pow(2, reconnectAttempt++), 30_000)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectWs()
  }, delay)
}

/* ========== 消息处理（REST 历史 / WS 广播统一入口） ========== */
function handleIncoming(msg) {
  // 若存在本地失败占位（同 clientMsgId）→ 服务端已确认，先移除占位再用真实消息渲染
  if (msg.clientMsgId && pendingByClientId.has(msg.clientMsgId)) {
    const pendingKey = pendingByClientId.get(msg.clientMsgId)
    const idx = messages.value.findIndex((x) => x._key === pendingKey)
    if (idx >= 0) messages.value.splice(idx, 1)
    pendingByClientId.delete(msg.clientMsgId)
  }
  if (seenIds.has(msg.id)) return
  if (msg.clientMsgId && seenClientIds.has(msg.clientMsgId)) return
  seenIds.add(msg.id)
  if (msg.clientMsgId) seenClientIds.add(msg.clientMsgId)
  const item = normalize(msg)
  // 按 id 插入保持全局有序；新消息优先走 push（id 恒增）
  if (messages.value.length && item.id < messages.value[messages.value.length - 1].id) {
    const i = messages.value.findIndex((x) => x.id > item.id)
    messages.value.splice(i < 0 ? messages.value.length : i, 0, item)
  } else {
    messages.value.push(item)
  }
  afterNewMessage(item)
}

/** AGENT 流式：同一 clientMsgId 匹配气泡追加 */
function handleAgentStream(frame) {
  let bubble = messages.value.find((x) => x.type === 'AGENT' && x.clientMsgId === frame.clientMsgId && !x.done)
  if (!bubble) {
    bubble = normalize({
      id: frame.messageId ?? null,
      type: 'AGENT',
      content: '',
      clientMsgId: frame.clientMsgId,
      done: false
    })
    messages.value.push(bubble)
  }
  if (frame.delta) {
    bubble.content += frame.delta
  }
  if (frame.error && !bubble.content) {
    bubble.content = frame.error
  }
  if (frame.finalFrame) {
    bubble.done = true
    if (frame.messageId && !seenIds.has(frame.messageId)) {
      seenIds.add(frame.messageId)
      bubble.id = frame.messageId
    }
    afterNewMessage(bubble)
    nextTick(() => scrollToBottom())
  }
}

/* ========== 历史 / 增量加载 ========== */
async function loadHistory() {
  loadingHistory.value = true
  try {
    const rows = await chatApi.history({ size: 30 })
    // 后端返回倒序 → 反转成正序插入开头
    const _items = [...rows].reverse().map(normalize)
    messages.value.splice(0, 0, ..._items)
    _items.forEach((m) => { seenIds.add(m.id); if (m.clientMsgId) seenClientIds.add(m.clientMsgId) })
    await nextTick()
    scrollToBottom()
    reportRead()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loadingHistory.value = false
  }
}

/** 滚动到顶加载更早历史 */
async function loadOlder() {
  if (loadingHistory.value || !messages.value.length) return
  const oldest = messages.value.filter((m) => m.id != null).reduce((a, b) => (b.id < a ? b.id : a), Infinity)
  if (oldest === Infinity) return
  loadingHistory.value = true
  try {
    const rows = await chatApi.history({ beforeId: oldest, size: 30 })
    const _items = [...rows].reverse().map(normalize)
    const newIds = _items.filter((m) => !seenIds.has(m.id))
    if (!newIds.length) return
    messages.value.splice(0, 0, ..._items)
    _items.forEach((m) => { seenIds.add(m.id); if (m.clientMsgId) seenClientIds.add(m.clientMsgId) })
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loadingHistory.value = false
  }
}

/** 断线重连后的增量拉取（afterId = 本地最大 id） */
async function pullAfterId() {
  const maxId = messages.value.reduce((a, b) => (b.id != null && b.id > a ? b.id : a), 0)
  if (!maxId) return
  try {
    const rows = await chatApi.after(maxId)
    rows.forEach((m) => handleIncoming(m))
  } catch (e) {
    /* 忽略 */
  }
}

/* ========== 发送 ========== */
function sendText() {
  const content = draft.value.trim()   // 纯文本；BOT 空内容也允许发送（服务端给降级提示）
  const msgType = botActive.value ? 'BOT' : 'USER'
  draft.value = ''
  botActive.value = false
  atMenuVisible.value = false
  doSend('TEXT', content, undefined, msgType)
}

/** 发送：clientMsgId 不传则新生成；重发必须透传原值（服务端靠它幂等去重） */
function doSend(type, content, clientMsgId, msgType = 'USER') {
  const cid = clientMsgId || crypto.randomUUID()
  const payload = JSON.stringify({ type, content, clientMsgId: cid, msgType })
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(payload)
      return
    } catch (e) { /* 发送抛异常 → 走失败占位 */ }
  }
  // 连接未就绪/发送失败：本地占位气泡(标记失败) + 入队，连接恢复后自动重发(同 cid)
  markFailedLocal(type, content, cid, msgType)
  resendQueue.push({ type, content, clientMsgId: cid, msgType })
  reconnecting.value = true
}

/** 失败占位消息：显示"发送失败 + 重发"按钮；服务端确认(广播回同 clientMsgId)时被替换 */
function markFailedLocal(type, content, cid, msgType) {
  if (pendingByClientId.has(cid)) return // 同一消息已在占位中，不重复添加
  const item = normalize({ id: null, senderId: myId, type, content, clientMsgId: cid, msgType })
  item._key = 'pending-' + cid // 与真实消息 _key(=clientMsgId) 区分，避免 v-for key 冲突
  item._pending = true
  item._failed = true
  messages.value.push(item)
  pendingByClientId.set(cid, item._key)
}

/** 手动重发：透传原 clientMsgId（幂等键不变 → 服务端不会重复入库） */
function retrySend(m) {
  doSend(m.type === 'IMAGE' ? 'IMAGE' : 'TEXT', m.content, m.clientMsgId, m.msgType || 'USER')
}

function flushResendQueue() {
  while (resendQueue.length) {
    const item = resendQueue.shift()
    try {
      // 重发复用入队时保存的同一个 clientMsgId —— 服务端撞 (couple_id, client_msg_id) 唯一键去重
      ws.send(JSON.stringify(item))
    } catch (e) { resendQueue.unshift(item); break }
  }
}

/* ========== 图片发送 ========== */
const uploadAction = '/api/files/upload'
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${userStore.token}` }))

function beforeImageUpload(file) {
  const ok = file.type.startsWith('image/') && file.size / 1024 / 1024 < 10
  if (!ok) ElMessage.error('仅支持图片且 ≤ 10MB')
  return ok
}

function handleImageSuccess(res) {
  if (res && res.code === 0 && res.data?.url) {
    doSend('IMAGE', res.data.url, undefined, 'USER')
  } else {
    ElMessage.error(res?.message || '图片上传失败')
  }
}

function handleImageError() {
  ElMessage.error('图片上传失败，请重试')
}

/* ========== 未读 ========== */
/** 节流上报已读位点（本地最大真实消息 id） */
function reportRead() {
  const maxId = messages.value.reduce((a, b) => (b.id != null && b.id > a ? b.id : a), 0)
  if (!maxId) return
  clearTimeout(readTimer)
  readTimer = setTimeout(async () => {
    try { await chatApi.markRead(maxId) } catch (e) { /* 忽略 */ }
  }, 600)
}

function afterNewMessage(m) {
  if (m.id == null || m.senderId === myId) return
  // 不在底部 → 显示浮条（回到底部按钮文案变"有新消息"）；在底部 → 视为已读上报
  const nearBottom = isNearBottom()
  if (nearBottom) {
    reportRead()
  } else {
    atBottom.value = false
    showNewFloat.value = true
  }
}

/* ========== 滚动 ========== */
function onScroll() {
  const el = msgListRef.value
  if (!el) return
  if (el.scrollTop < 60 && !loadingHistory.value) {
    loadOlder()
  }
  atBottom.value = isNearBottom()
  if (atBottom.value) {
    showNewFloat.value = false
  }
}

function isNearBottom() {
  const el = msgListRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom() {
  const el = msgListRef.value
  if (el) el.scrollTop = el.scrollHeight
  atBottom.value = true
  showNewFloat.value = false
}

/* ========== 工具 ========== */
function normalize(msg) {
  const _key = msg.clientMsgId || `id-${msg.id || Math.random().toString(36).slice(2)}-${Date.now()}-${Math.random().toString(36).slice(2)}`
  return {
    id: msg.id ?? null,
    senderId: msg.senderId,
    type: msg.type || 'TEXT',
    content: msg.content || '',
    clientMsgId: msg.clientMsgId || null,
    // 语义类型：AGENT 消息推导为 AGENT；BOT 提问由服务端 msgType 透出；其余 USER
    msgType: msg.msgType || (msg.type === 'AGENT' ? 'AGENT' : 'USER'),
    // AGENT 流式气泡为 false（进行中）；普通消息/已落库消息默认 true（已完成）
    done: msg.done ?? true,
    _key
  }
}

function isMine(m) {
  // AGENT 回复永远归"对方侧"（senderId 记录的是触发者，不能据此判为我）
  if (m.type === 'AGENT') return false
  return myId != null && m.senderId === myId
}

function avatarOf(m) {
  if (m.type === 'AGENT') return '🤖'
  return isMine(m) ? (userStore.nickname || '我').charAt(0) : (userStore.partner?.nickname || 'Ta').charAt(0)
}

function timeOf(m) {
  return m.createdAt ? dayjs(m.createdAt).format('HH:mm') : ''
}

/** 点击输入框前 @ 按钮：直接开启 AI 助手 chip（标签实体，不进 draft 文本） */
function insertAtBot() {
  botActive.value = true
  atMenuVisible.value = false
}

/** 输入框内容变化：以裸 @ 结尾时弹出 AI 助手候选 */
function onDraftInput() {
  atMenuVisible.value = draft.value.endsWith('@') && !botActive.value
}

/** 候选中选择 AI 助手：开启 chip 并移除刚输入的裸 @ 字符 */
function chooseAtBot() {
  botActive.value = true
  draft.value = draft.value.replace(/@\s*$/, '')
  atMenuVisible.value = false
}

/* ========== 生命周期 ========== */
onMounted(async () => {
  if (!userStore.token) return
  if (!userStore.userInfo) {
    try { await userStore.fetchMe() } catch (e) { return }
  }
  if (!userStore.isBound) return
  myId = userStore.userInfo.id
  // 进入页面先上报已读（拉到历史后也会上报）
  await loadHistory()
  connectWs()
})

onBeforeUnmount(() => {
  if (ws) {
    ws.onclose = null
    ws.close()
  }
  clearTimeout(reconnectTimer)
  clearTimeout(readTimer)
})
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 108px);
  min-height: 480px;
}

.chat-unbound {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.chat-box {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: rgba(255, 255, 255, 0.85);
  border-radius: 14px;
  box-shadow: 0 4px 20px rgba(236, 106, 143, 0.08);
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(236, 106, 143, 0.12);
  background: rgba(255, 245, 248, 0.7);
}

.chat-header-title {
  font-weight: 600;
  color: var(--text-strong);
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  position: relative;
}

.chat-loading {
  text-align: center;
  color: #aaa;
  font-size: 13px;
  padding: 8px 0;
}

.chat-empty {
  text-align: center;
  color: var(--text-weak);
  padding: 60px 0;
}

.chat-empty-tip {
  font-size: 12px;
  color: var(--text-weak);
  margin-top: 6px;
}

.msg-row {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  align-items: flex-start;
}

.msg-row.mine {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--el-color-primary-light-5), var(--el-color-primary));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgba(236, 106, 143, 0.3);
}

.msg-main {
  max-width: 68%;
  display: flex;
  flex-direction: column;
}

.msg-row.mine .msg-main {
  align-items: flex-end;
}

.msg-agent-tag {
  font-size: 12px;
  color: var(--sem-accent-strong);
  margin-bottom: 2px;
}

/* BOT 提问标识：与 AGENT 回复同紫色系，提示"这条消息发给了 AI" */
.msg-bot-tag {
  font-size: 12px;
  color: var(--sem-accent-strong);
  margin-bottom: 2px;
}

.msg-bubble {
  padding: 9px 13px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid rgba(236, 106, 143, 0.15);
  color: var(--text-strong);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.msg-row.mine .msg-bubble {
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--el-color-primary-light-5));
  border-color: transparent;
}

.msg-bubble.agent {
  background: var(--sem-accent-soft);
  border-color: rgba(162, 109, 232, 0.25);
}

.cursor {
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  0% { opacity: 1; }
  50% { opacity: 0; }
}

.msg-img {
  max-width: 240px;
  max-height: 240px;
  border-radius: 10px;
  display: block;
}

.clickable-img {
  cursor: zoom-in;
  transition: transform 0.15s;
}

.clickable-img:hover {
  transform: scale(1.02);
}

.msg-img-broken {
  color: #aaa;
  font-size: 13px;
}

.msg-time {
  font-size: 11px;
  color: var(--text-weak);
  margin-top: 2px;
  padding: 0 2px;
}

/* 发送失败占位：气泡置灰 + 重发按钮 */
.msg-retry-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.msg-row.mine .msg-retry-box {
  justify-content: flex-end;
}

.msg-failed-tip {
  font-size: 12px;
  color: var(--el-color-danger);
}

.msg-row.mine .msg-bubble._failed,
.msg-row.other .msg-bubble._failed {
  opacity: 0.6;
}

.chat-new-float {
  position: absolute;
  bottom: 70px;
  right: 50%;
  transform: translateX(50%);
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  padding: 6px 14px;
  border-radius: 999px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(236, 106, 143, 0.4);
  z-index: 10;
}

.chat-input-wrap {
  border-top: 1px solid rgba(236, 106, 143, 0.12);
  padding: 10px 16px;
  background: rgba(255, 255, 255, 0.9);
  position: relative;
}

/* @ 候选菜单：输入框内输入 @ 时弹出 */
.chat-at-menu {
  position: absolute;
  bottom: calc(100% - 6px);
  left: 16px;
  right: 16px;
  z-index: 30;
  background: #fff;
  border: 1px solid rgba(162, 109, 232, 0.25);
  border-radius: 10px;
  box-shadow: 0 6px 20px rgba(162, 109, 232, 0.15);
  padding: 4px;
}

.chat-at-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.chat-at-item:hover {
  background: var(--sem-accent-soft);
}

.chat-at-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--sem-accent-soft), var(--sem-accent));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}

.chat-at-info {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.chat-at-name {
  font-size: 14px;
  color: var(--text-strong);
  font-weight: 600;
}

.chat-at-desc {
  font-size: 12px;
  color: var(--text-weak);
}

/* AI 助手 chip：标签实体，可点 ✕ 关闭（退格可删） */
.chat-bot-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--sem-accent-soft);
  border: 1px solid rgba(162, 109, 232, 0.35);
  color: var(--sem-accent-strong);
  font-size: 12px;
  font-weight: 600;
  padding: 4px 8px;
  border-radius: 999px;
  margin-bottom: 3px;
  flex-shrink: 0;
  user-select: none;
}

.chat-bot-chip-x {
  cursor: pointer;
  font-size: 12px;
  opacity: 0.7;
}

.chat-bot-chip-x:hover {
  opacity: 1;
}

.chat-input-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.chat-ibot-btn {
  color: var(--sem-accent-strong);
  border-color: rgba(162, 109, 232, 0.4);
}

.chat-img-btn {
  font-size: 15px;
}
</style>