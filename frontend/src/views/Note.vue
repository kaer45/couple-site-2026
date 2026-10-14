<template>
  <div class="page note">
    <!-- 顶部操作栏 -->
    <div class="note-toolbar">
      <div class="section-title note-title">
        <el-icon class="title-icon"><Notebook /></el-icon>学习笔记
      </div>
      <div class="note-actions">
        <el-input
          v-model="keyword"
          class="note-search"
          placeholder="搜索标题或正文"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-upload
          :show-file-list="false"
          :http-request="doUpload"
          accept=".md,.markdown,.txt,.docx"
        >
          <el-button type="primary" round :loading="uploading">
            <el-icon><Upload /></el-icon>&nbsp;上传笔记
          </el-button>
        </el-upload>
      </div>
    </div>

    <div class="note-hint">
      支持 .md / .markdown / .txt / .docx，单个文件 ≤ 10MB。笔记<b>仅自己可见</b>，解析出的正文会用于搜索与 AI 助手检索。
    </div>

    <!-- 列表 -->
    <el-skeleton v-if="loading" :rows="4" animated />
    <EmptyState
      v-else-if="!list.length"
      :description="keyword ? '没有匹配的笔记' : '还没有笔记，上传第一篇吧'"
      icon="📒"
      icon-component="Notebook"
    >
      <el-upload :show-file-list="false" :http-request="doUpload" accept=".md,.markdown,.txt,.docx">
        <el-button type="primary" round>上传笔记</el-button>
      </el-upload>
    </EmptyState>
    <div v-else class="note-list">
      <div
        v-for="n in list"
        :key="n.id"
        class="note-item warm-card"
        @click="openDetail(n)"
      >
        <div class="note-item-head">
          <span class="note-format" :class="`fmt-${(n.format || '').toLowerCase()}`">{{ n.format }}</span>
          <span class="note-item-title">{{ n.title }}</span>
          <el-tag v-if="n.summaryAi" size="small" effect="plain" type="success">AI 摘要</el-tag>
          <el-tag v-if="n.parseStatus === 2" type="warning" size="small" effect="light">解析失败</el-tag>
        </div>
        <!-- 摘要优先展示 AI 生成的；没有则渲染正文节选（Markdown 已解析） -->
        <div
          v-if="n.summaryAi"
          class="note-item-summary note-item-ai markdown-body"
          v-html="renderMarkdown(n.summaryAi)"
        />
        <div
          v-else-if="n.summary"
          class="note-item-summary"
          v-html="renderExcerpt(n.summary)"
        />
        <div v-else class="note-item-summary note-item-empty">（无正文内容）</div>
        <div class="note-item-foot">
          <span class="note-item-meta">{{ sizeText(n.fileSize) }} · {{ n.createdAt }}</span>
          <span class="note-item-btns">
            <el-button link type="primary" @click.stop="handleRename(n)">重命名</el-button>
            <el-button link type="danger" @click.stop="handleRemove(n)">删除</el-button>
          </span>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" class="note-pager">
      <el-pagination
        layout="prev, pager, next"
        :current-page="pageNo"
        :page-size="size"
        :total="total"
        background
        @current-change="handlePageChange"
      />
    </div>

    <!-- 详情抽屉：纯文本预览 + 原文件下载 -->
    <el-drawer v-model="drawerVisible" :title="current?.title || '笔记详情'" size="62%">
      <div v-if="current" class="note-detail">
        <div class="note-detail-meta">
          <el-tag size="small" effect="light">{{ current.format }}</el-tag>
          <span>{{ current.originalName }}</span>
          <span>{{ sizeText(current.fileSize) }}</span>
          <span>{{ current.createdAt }}</span>
          <el-button link type="primary" @click="downloadOriginal(current)">
            <el-icon><Download /></el-icon>&nbsp;下载原文件
          </el-button>
        </div>

        <el-alert
          v-if="current.parseStatus === 2"
          type="warning"
          show-icon
          :closable="false"
          class="note-detail-alert"
          :title="`正文解析失败：${current.parseError || '未知原因'}`"
          description="原始文件已保存，可正常下载；该笔记不会参与关键词搜索与 AI 检索。"
        />

        <!-- AI 摘要：手动触发；未生成时卡片回落到正文节选 -->
        <div class="note-ai">
          <div class="note-ai-head">
            <span class="note-ai-title">
              <el-icon><MagicStick /></el-icon>&nbsp;AI 摘要
            </span>
            <el-button
              link
              type="primary"
              :loading="summaryLoading"
              :disabled="!current.content"
              @click="handleGenerateSummary(current)"
            >
              {{ current.summaryAi ? '重新生成' : '生成摘要' }}
            </el-button>
          </div>
          <div v-if="current.summaryAi" class="note-ai-body markdown-body" v-html="renderMarkdown(current.summaryAi)" />
          <div v-else class="note-ai-empty">
            {{ current.content ? '还没有 AI 摘要。未生成时列表卡片显示正文节选。' : '该笔记没有可用的正文，无法生成摘要。' }}
          </div>
          <div v-if="current.summaryAiAt" class="note-ai-time">生成于 {{ current.summaryAiAt }}</div>
        </div>

        <!-- 渲染 / 纯文本 切换：纯文本便于核对抽取结果，渲染用于正常阅读 -->
        <div class="note-view-bar">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="render">渲染</el-radio-button>
            <el-radio-button value="text">纯文本</el-radio-button>
          </el-radio-group>
          <span v-if="current.format === 'DOCX'" class="note-view-hint">
            渲染视图直接解析原始 .docx，保留段落/表格/标题层级
          </span>
        </div>

        <!-- 纯文本视图：用 v-show 保持挂载，避免来回切换时重建 DOM -->
        <pre v-show="viewMode === 'text'" class="note-content">{{ current.content || '（无正文）' }}</pre>

        <!-- 渲染视图：同样必须保持挂载 ——
             docx 的渲染结果是直接写进这个容器的，若用 v-if，切到纯文本时容器会被销毁，
             切回来只剩一个空 div（表现为大片空白）。 -->
        <div v-show="viewMode === 'render'">
          <!-- Markdown：排版 + 代码高亮 + LaTeX -->
          <div
            v-if="current.format === 'MD'"
            class="markdown-body"
            v-html="renderedHtml"
          />

          <!-- DOCX：用 docx-preview 渲染原文件，保留排版 -->
          <div v-else-if="current.format === 'DOCX'">
            <el-alert
              v-if="docxError"
              type="warning"
              show-icon
              :closable="false"
              class="note-detail-alert"
              :title="`docx 渲染失败：${docxError}`"
              description="已退回显示抽取出的纯文本；原始文件仍可在上方下载。"
            />
            <el-skeleton v-if="docxLoading" :rows="6" animated />
            <div ref="docxContainer" class="docx-view" />
            <pre v-if="docxError && current.content" class="note-content">{{ current.content }}</pre>
          </div>

          <!-- TXT：本就没有排版，保持纯文本 -->
          <pre v-else class="note-content">{{ current.content || '（无正文）' }}</pre>
        </div>
      </div>
      <template #footer>
        <el-button round @click="drawerVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { renderAsync } from 'docx-preview'
import * as noteApi from '@/api/note'
import { renderMarkdown, renderExcerpt } from '@/utils/markdown'
import EmptyState from '@/components/EmptyState.vue'

const list = ref([])
const loading = ref(false)
const uploading = ref(false)
const keyword = ref('')

/* 详情视图模式：render=渲染 / text=纯文本 */
const viewMode = ref('render')
/* docx 渲染状态 */
const docxContainer = ref(null)
const docxLoading = ref(false)
const docxError = ref('')

/* AI 摘要生成中 */
const summaryLoading = ref(false)

const renderedHtml = computed(() =>
  current.value?.format === 'MD' ? renderMarkdown(current.value.content) : ''
)

/* 分页 */
const pageNo = ref(1)
const size = ref(10)
const total = ref(0)

/* 详情抽屉 */
const drawerVisible = ref(false)
const current = ref(null)

onMounted(fetchList)

// 拉取列表
async function fetchList() {
  loading.value = true
  try {
    const data = await noteApi.page({
      current: pageNo.value,
      size: size.value,
      keyword: keyword.value || undefined
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNo.value = 1
  fetchList()
}

function handlePageChange(page) {
  pageNo.value = page
  fetchList()
}

// 上传（el-upload 自定义请求：拿到 File 直接调我们自己的接口）
async function doUpload({ file }) {
  uploading.value = true
  try {
    const vo = await noteApi.upload(file)
    if (vo.parseStatus === 2) {
      ElMessage.warning('上传成功，但正文解析失败，可下载原文件查看')
    } else {
      ElMessage.success('笔记上传成功 📒')
    }
    pageNo.value = 1
    await fetchList()
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    uploading.value = false
  }
}

// 打开详情
async function openDetail(n) {
  try {
    current.value = await noteApi.detail(n.id)
    viewMode.value = 'render'
    docxError.value = ''
    drawerVisible.value = true
    // docx 需要拿到原始文件才能渲染，等容器挂载后再做
    if (current.value.format === 'DOCX') {
      await nextTick()
      await renderDocx(current.value)
    }
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

/**
 * 用 docx-preview 渲染原始 .docx（保留段落 / 表格 / 标题层级）
 * 走 /uploads/** 静态地址取原文件，失败则退回显示抽取出的纯文本
 */
async function renderDocx(note) {
  if (!docxContainer.value) return
  docxContainer.value.innerHTML = ''
  docxError.value = ''
  docxLoading.value = true
  try {
    const resp = await fetch(note.originalUrl)
    if (!resp.ok) throw new Error(`取原文件失败 HTTP ${resp.status}`)
    const blob = await resp.blob()
    await renderAsync(blob, docxContainer.value, null, {
      inWrapper: true,
      ignoreWidth: false,
      ignoreHeight: true,
      breakPages: false,
      experimental: true
    })
  } catch (e) {
    docxError.value = e?.message || '未知错误'
  } finally {
    docxLoading.value = false
  }
}

// 兜底：切回「渲染」时若 docx 容器是空的（例如首次渲染失败过），补渲染一次。
// 正常路径下容器一直挂载、内容还在，这里不会触发。
watch(viewMode, async (mode) => {
  if (mode !== 'render' || current.value?.format !== 'DOCX') return
  await nextTick()
  if (docxContainer.value && !docxContainer.value.hasChildNodes()) {
    await renderDocx(current.value)
  }
})

/**
 * 手动生成 / 重新生成 AI 摘要
 * - 后端同步等待 Python，最长约 60s，期间按钮显示 loading
 * - 失败（503）时后端**不写库**，这里只提示原因，摘要保持原样
 */
async function handleGenerateSummary(note) {
  if (!note) return
  summaryLoading.value = true
  try {
    const updated = await noteApi.generateSummary(note.id)
    current.value = updated
    // 同步列表里的这条，避免关掉抽屉后卡片还是旧的
    const idx = list.value.findIndex((x) => x.id === note.id)
    if (idx >= 0) {
      list.value[idx].summaryAi = updated.summaryAi
      list.value[idx].summaryAiAt = updated.summaryAiAt
    }
    ElMessage.success('AI 摘要已生成 ✨')
  } catch (e) {
    /* 错误提示已由拦截器统一处理（503 的 message 会带上具体原因） */
  } finally {
    summaryLoading.value = false
  }
}

// 重命名
async function handleRename(n) {
  let value
  try {
    const res = await ElMessageBox.prompt('输入新的标题', '重命名笔记', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: n.title,
      inputValidator: (v) => (v && v.trim() ? true : '标题不能为空')
    })
    value = res.value
  } catch (e) {
    return // 用户取消
  }
  try {
    await noteApi.update(n.id, { title: value.trim() })
    ElMessage.success('已重命名')
    await fetchList()
    if (drawerVisible.value && current.value?.id === n.id) {
      current.value.title = value.trim()
    }
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

// 删除
async function handleRemove(n) {
  try {
    await ElMessageBox.confirm(`确定删除笔记「${n.title}」吗？原文件也会一并删除`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await noteApi.remove(n.id)
    ElMessage.success('删除成功')
    if (current.value?.id === n.id) {
      drawerVisible.value = false
    }
    await fetchList()
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

// 下载原文件（带原始文件名）
function downloadOriginal(n) {
  const a = document.createElement('a')
  a.href = n.originalUrl
  a.download = n.originalName || ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function sizeText(bytes) {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}
</script>

<style scoped>
.note-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.note-title {
  margin: 0;
}

.title-icon {
  vertical-align: -2px;
  margin-right: 6px;
  color: var(--el-color-primary);
}

.note-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.note-search {
  width: 220px;
}

.note-hint {
  margin-bottom: 16px;
  font-size: 12px;
  line-height: 1.7;
  color: #a98d99;
}

.note-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.note-item {
  padding: 14px 18px;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

.note-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 26px rgba(236, 106, 143, 0.18);
}

.note-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.note-item-title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 700;
  color: #6b5260;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 格式徽标 */
.note-format {
  flex-shrink: 0;
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.4px;
  border-radius: 6px;
  color: #fff;
  background: var(--el-color-primary-light-3);
}

/* 格式徽标：三档收敛到暖色家族内
   （原本是三个冷色：亮蓝 / 中蓝 / 冷灰蓝，与粉色基调冲突；
     具体旧值见 docs/color-refactor-plan.md §1.4 的映射表）
   白字对比度：md 3.57 / docx 5.22 / txt 4.25。md 那档偏弱，
   若要严格达 AA，需把徽章改成「浅底 + 深字」——留待逐页替换时处理。 */
.fmt-md {
  background: var(--sem-accent);
}

.fmt-txt {
  background: var(--el-color-info);
}

.fmt-docx {
  background: var(--sem-accent-strong);
}

.note-item-summary {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: #8a7580;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 没有正文时的占位提示 */
.note-item-empty {
  color: #c9b6bf;
}

/* AI 摘要样式（纯文本，区别于 Markdown 渲染的节选） */
.note-item-ai {
  color: #7a6470;
}

.note-item-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.note-item-meta {
  font-size: 12px;
  color: #bca7b1;
}

.note-item-btns {
  opacity: 0;
  transition: opacity 0.2s;
}

.note-item:hover .note-item-btns {
  opacity: 1;
}

.note-pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

/* ===== 详情抽屉 ===== */
.note-view-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.note-view-hint {
  font-size: 12px;
  color: #bca7b1;
}

.note-detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  margin-bottom: 12px;
  font-size: 12px;
  color: #a98d99;
  border-bottom: 1px solid rgba(236, 106, 143, 0.12);
}

.note-detail-alert {
  margin-bottom: 12px;
}

/* ===== AI 摘要 ===== */
.note-ai {
  margin-bottom: 14px;
  padding: 12px 14px;
  background: rgba(236, 106, 143, 0.05);
  border: 1px solid rgba(236, 106, 143, 0.14);
  border-radius: 12px;
}

.note-ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.note-ai-title {
  display: flex;
  align-items: center;
  font-size: 13px;
  font-weight: 700;
  color: #6b5260;
}

.note-ai-body {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.8;
  color: #5b4750;
  white-space: pre-wrap;
  word-break: break-word;
}

/* AI 摘要渲染成 markdown 后，去掉 pre-wrap（否则 HTML 源码里的换行会变成额外空行） */
.note-ai-body.markdown-body {
  white-space: normal;
}

.note-ai-empty {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #bca7b1;
}

.note-ai-time {
  margin-top: 6px;
  font-size: 11px;
  color: #c9b6bf;
}

/* 纯文本预览：保留换行、长词换行 */
.note-content {
  margin: 0;
  padding: 14px 16px;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.8;
  color: #5b4750;
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(236, 106, 143, 0.04);
  border-radius: 12px;
}

/* docx-preview 渲染容器 */
.docx-view {
  border-radius: 12px;
  overflow-x: auto;
}
</style>

<!--
  以下是**非 scoped** 样式：v-html 渲染出来的元素拿不到 scoped 的 data 属性，
  因此 Markdown 正文排版必须放在全局样式里（类名已加前缀，冲突风险可控）。
-->
<style>
/* Markdown 排版样式已抽到全局 styles/index.css（.markdown-body 系列），
   Chat/AiChat 等页面共用，此处不再重复定义 */

/* 卡片摘要里的行内元素（v-html 渲染出来，拿不到 scoped 属性，故放全局） */
.note-item-summary strong {
  font-weight: 700;
  color: #6b5260;
}

.note-item-summary em {
  font-style: italic;
}

.note-item-summary code {
  padding: 0 4px;
  font-size: 12px;
  color: #c7254e;
  background: rgba(236, 106, 143, 0.08);
  border-radius: 4px;
}

.note-item-summary a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.note-item-summary .katex {
  font-size: 1em;
}
</style>
