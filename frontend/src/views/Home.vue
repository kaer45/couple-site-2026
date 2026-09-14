<template>
  <div class="page home">
    <!-- ===== 顶部数据卡：在一起天数 + 下一个纪念日 ===== -->
    <div class="stat-cards">
      <div class="stat-card warm-card">
        <div class="stat-label">💞 在一起已经</div>
        <div class="stat-value">
          {{ summary.daysTogether ?? '—' }}
          <span class="stat-unit">天</span>
        </div>
        <div class="stat-sub" v-if="userStore.couple?.startDate">从 {{ userStore.couple.startDate }} 开始</div>
      </div>
      <div class="stat-card warm-card">
        <div class="stat-label">🎉 下一个纪念日</div>
        <template v-if="summary.nextAnniversary">
          <div class="stat-value stat-value-sm">{{ summary.nextAnniversary.name }}</div>
          <div class="stat-sub">
            还有 <b>{{ summary.nextAnniversary.daysLeft }}</b> 天（{{ summary.nextAnniversary.date }}）
          </div>
        </template>
        <template v-else>
          <div class="stat-empty">还没有设置纪念日</div>
          <el-button link type="primary" @click="router.push('/anniversaries')">去添加纪念日 →</el-button>
        </template>
      </div>
    </div>

    <!-- ===== 发布动态入口 ===== -->
    <div class="publish-bar">
      <el-button type="primary" round size="large" @click="openPublishDialog">
        <el-icon><EditPen /></el-icon>&nbsp;发布动态
      </el-button>
    </div>

    <!-- ===== 时间轴（全量渲染，分页加载数据量小，无需虚拟滚动） ===== -->
    <template v-if="loading && !moments.length">
      <el-skeleton :rows="4" animated class="skeleton" />
    </template>

    <div v-else-if="moments.length" ref="timelineRef" class="timeline">
      <div
        v-for="g in dateGroups"
        :key="g.date"
        :id="'date-' + g.date"
        :data-date="g.date"
        class="date-group"
      >
        <div class="date-header">
          <span class="date-badge">{{ formatGroupDate(g.date) }}</span>
        </div>
        <div v-for="m in g.items" :key="m.id" class="timeline-item">
          <div class="timeline-dot"></div>
          <MomentCard class="timeline-card" :moment="m" @deleted="handleMomentDeleted" />
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState v-else description="还没有动态，记录下你们的第一条故事吧" icon="🌷">
      <el-button type="primary" round @click="openPublishDialog">发布第一条动态</el-button>
    </EmptyState>

    <!-- 加载更多 -->
    <div v-if="hasMore" class="load-more">
      <el-button :loading="loading" round @click="loadMore">加载更多</el-button>
    </div>

    <!-- ===== 日期快速跳转栏：常驻，支持点击跳转 + 按住滑动定位（类似手机相册） ===== -->
    <div class="date-rail">
      <div class="date-rail-header">
        <el-icon class="rail-calendar"><Calendar /></el-icon>
        <span>按日期</span>
      </div>
      <div
        ref="railListRef"
        class="date-rail-list"
        @pointerdown="onRailPointerDown"
        @pointermove="onRailPointerMove"
        @pointerup="onRailPointerUp"
        @pointercancel="onRailPointerUp"
      >
        <template v-for="g in railGroups" :key="g.month">
          <div class="rail-month" :data-date="g.dates[0]" @click="jumpToDate(g.dates[0])">
            {{ g.month.slice(0, 4) }}年{{ Number(g.month.slice(5)) }}月
          </div>
          <div
            v-for="d in g.dates"
            :key="d"
            class="date-rail-item"
            :class="{ active: d === activeDate }"
            :data-date="d"
            :title="formatGroupDate(d)"
            @click="jumpToDate(d)"
          >
            {{ railLabel(d) }}
          </div>
        </template>
      </div>
    </div>
    <!-- 滑动时的日期预览浮标 -->
    <div v-if="sliding && slidePreview" class="rail-preview">{{ formatGroupDate(slidePreview) }}</div>
  </div>

  <!-- ===== 发布动态弹窗 ===== -->
  <el-dialog v-model="dialogVisible" title="发布动态" width="560px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="这一刻的想法">
        <el-input
          v-model="publishForm.content"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          placeholder="记录下此刻的心情～"
        />
      </el-form-item>

      <el-form-item label="照片（最多 9 张）">
        <el-upload
          ref="uploadRef"
          :action="uploadAction"
          :headers="uploadHeaders"
          list-type="picture-card"
          accept="image/*"
          :limit="9"
          :on-success="handleUploadSuccess"
          :on-error="handleUploadError"
          :on-remove="handleUploadRemove"
          :on-exceed="handleUploadExceed"
        >
          <el-icon><Plus /></el-icon>
        </el-upload>
      </el-form-item>

      <el-form-item label="地点（选填）">
        <el-input v-model="publishForm.location" placeholder="比如：杭州西湖" clearable />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button round @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" round :loading="submitting" @click="handlePublish">发布</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import * as momentApi from '@/api/moment'
import * as anniversaryApi from '@/api/anniversary'
import MomentCard from '@/components/MomentCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()

/* ========== 汇总数据 ========== */
const summary = ref({ daysTogether: null, nextAnniversary: null, upcoming: [] })

/* ========== 时间轴分页 ========== */
const moments = ref([])
const current = ref(1)
const size = ref(10) // 每页条数（契约分页参数）
const total = ref(0)
const loading = ref(false)

// 是否还有更多（已加载条数 < 总数）
const hasMore = computed(() => moments.value.length < total.value)

/* ========== 按日期分组 ========== */
const dateGroups = computed(() => {
  const map = new Map()
  for (const m of moments.value) {
    const d = (m.createdAt || '').slice(0, 10)
    if (!d) continue
    if (!map.has(d)) map.set(d, { date: d, items: [] })
    map.get(d).items.push(m)
  }
  return [...map.values()]
})

/* ========== 滚动容器（用于日期跳转定位） ========== */
let scrollContainer = null
const timelineRef = ref(null)

/* ========== activeDate 跟随滚动高亮 ========== */
const activeDate = ref('')
let dateObserver = null

// 观察已渲染的分组：进入"高亮带"（视口上 15%~40% 区域）的分组设为当前激活
function setupDateObserver() {
  dateObserver?.disconnect()
  dateObserver = new IntersectionObserver(
    (entries) => {
      const entered = entries.filter((e) => e.isIntersecting)
      if (!entered.length) return
      const topmost = entered.reduce((a, b) =>
        a.target.getBoundingClientRect().top <= b.target.getBoundingClientRect().top ? a : b
      )
      activeDate.value = topmost.target.dataset.date
    },
    { rootMargin: '-15% 0px -60% 0px', threshold: 0 }
  )
  document.querySelectorAll('.date-group').forEach((el) => dateObserver.observe(el))
}

// 分组变化后：重建观察
watch(
  dateGroups,
  () => {
    setupDateObserver()
    if (!dateGroups.value.some((g) => g.date === activeDate.value)) {
      activeDate.value = dateGroups.value[0]?.date || ''
    }
  },
  { flush: 'post' }
)

/* ========== 跳转栏数据与滑动定位 ========== */
const railDates = ref([])
const railListRef = ref()

// 跳转栏按 年月 分组（组内日期倒序），今天/昨天 直接显示文字
const railGroups = computed(() => {
  const groups = []
  for (const d of railDates.value) {
    const monthKey = d.slice(0, 7) // yyyy-MM
    const last = groups[groups.length - 1]
    if (!last || last.month !== monthKey) {
      groups.push({ month: monthKey, dates: [d] })
    } else {
      last.dates.push(d)
    }
  }
  return groups
})

function railLabel(d) {
  const today = dayjs().format('YYYY-MM-DD')
  const yesterday = dayjs().subtract(1, 'day').format('YYYY-MM-DD')
  if (d === today) return '今天'
  if (d === yesterday) return '昨天'
  return d.slice(5)
}

function formatGroupDate(date) {
  const today = dayjs().format('YYYY-MM-DD')
  const yesterday = dayjs().subtract(1, 'day').format('YYYY-MM-DD')
  if (date === today) return '今天'
  if (date === yesterday) return '昨天'
  return dayjs(date).format('YYYY年M月D日 dddd')
}

// 滑动定位状态：按住索引栏滑动 → 实时预览 + 即时定位，松手结束
const sliding = ref(false)
const slidePreview = ref('')
let anchorFetching = false // 锚点分页请求进行中，避免滑动时并发重复请求

function onRailPointerDown(e) {
  sliding.value = true
  railListRef.value?.setPointerCapture?.(e.pointerId)
  slideToPoint(e)
}

function onRailPointerMove(e) {
  if (sliding.value) slideToPoint(e)
}

function onRailPointerUp(e) {
  sliding.value = false
  slidePreview.value = ''
  railListRef.value?.releasePointerCapture?.(e.pointerId)
}

// 根据指针位置找到日期并定位（elementFromPoint 兼容指针捕获）
function slideToPoint(e) {
  const el = document.elementFromPoint(e.clientX, e.clientY)
  const date = el?.closest?.('[data-date]')?.dataset?.date
  if (!date || date === slidePreview.value) return
  slidePreview.value = date
  jumpToDate(date)
}

// 跳到指定日期：未加载则从该日期锚点分页拉取，再基于测量偏移精确定位
async function jumpToDate(date) {
  if (!dateGroups.value.some((g) => g.date === date)) {
    if (anchorFetching) return
    anchorFetching = true
    loading.value = true
    try {
      const page = await momentApi.list({ current: 1, size: size.value, anchorDate: date })
      moments.value = page.records
      total.value = page.total
      current.value = 2 // 下一页从第 2 页继续
    } catch (e) {
      /* 拦截器已提示错误 */
    } finally {
      loading.value = false
      anchorFetching = false
    }
    await nextTick()
  }
  scrollToDate(date)
}

// 滚动到指定日期分组（基于 DOM 元素定位，全量渲染后直接 scrollIntoView）
// 同时主动点亮侧边栏，避免最底部分组因无法滚进高亮带而永远不高亮
function scrollToDate(date) {
  const el = document.getElementById('date-' + date)
  if (!el) return
  activeDate.value = date
  el.scrollIntoView({ behavior: 'auto', block: 'start' })
}

/* ========== 发布弹窗 ========== */
const dialogVisible = ref(false)
const submitting = ref(false)
const uploadRef = ref()
const uploadAction = '/api/files/upload'
// el-upload 直连上传，手动携带 Authorization 头
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${userStore.token}` }))
const publishForm = reactive({
  content: '',
  images: [], // 上传成功的图片 url 数组
  location: ''
})

onMounted(async () => {
  // 滚动容器 = 布局中的主内容区（整个页面滚动）
  scrollContainer = document.querySelector('.layout-main')
  // 并行加载：汇总数据 + 第一页动态 + 日期跳转栏
  await Promise.all([fetchSummary(), fetchMoments(true), loadDateRail()])
})

onUnmounted(() => {
  dateObserver?.disconnect()
})

// 拉取日期跳转栏数据
async function loadDateRail() {
  try {
    railDates.value = await momentApi.dates()
  } catch (e) {
    /* 拦截器已提示错误 */
  }
}

// 拉取首页汇总（在一起天数 + 下一个纪念日）
async function fetchSummary() {
  try {
    summary.value = await anniversaryApi.summary()
  } catch (e) {
    /* 拦截器已提示错误 */
  }
}

// 拉取动态列表；reset=true 时回到第一页
async function fetchMoments(reset = false) {
  if (reset) {
    current.value = 1
    moments.value = []
  }
  loading.value = true
  try {
    const page = await momentApi.list({ current: current.value, size: size.value })
    moments.value = reset ? page.records : [...moments.value, ...page.records]
    total.value = page.total
    current.value += 1
  } catch (e) {
    /* 拦截器已提示错误 */
  } finally {
    loading.value = false
  }
}

// 加载更多（追加下一页）
function loadMore() {
  fetchMoments(false)
}

// 删除动态后从本地列表移除
function handleMomentDeleted(id) {
  moments.value = moments.value.filter((m) => m.id !== id)
  total.value -= 1
}

/* ========== 发布动态 ========== */

// 打开发布弹窗并重置表单
function openPublishDialog() {
  publishForm.content = ''
  publishForm.images = []
  publishForm.location = ''
  uploadRef.value?.clearFiles()
  dialogVisible.value = true
}

// 图片上传成功：收集 url（契约 §5.1 响应 { code, message, data: { url } }）
function handleUploadSuccess(response, file) {
  if (response && response.code === 0) {
    publishForm.images.push(response.data.url)
  } else {
    ElMessage.error(response?.message || '图片上传失败')
    // 移除失败的文件
    try {
      uploadRef.value?.handleRemove(file)
    } catch (e) {
      /* 忽略 */
    }
  }
}

// 图片上传失败
function handleUploadError(err) {
  ElMessage.error('图片上传失败，请重试')
}

// 移除已上传的图片：同步从 images 数组删除对应 url
function handleUploadRemove(file) {
  const url = file.response?.data?.url
  if (url) {
    publishForm.images = publishForm.images.filter((u) => u !== url)
  }
}

// 超过 9 张限制
function handleUploadExceed() {
  ElMessage.warning('最多只能上传 9 张照片哦')
}

// 提交发布
async function handlePublish() {
  if (!publishForm.content.trim()) {
    ElMessage.warning('写点内容再发布吧～')
    return
  }
  submitting.value = true
  try {
    await momentApi.create({
      content: publishForm.content.trim(),
      images: publishForm.images,
      location: publishForm.location || null
    })
    ElMessage.success('发布成功 🎉')
    dialogVisible.value = false
    await fetchMoments(true) // 刷新时间轴
  } catch (e) {
    /* 拦截器已提示错误 */
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* 数据卡布局 */
.stat-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-bottom: 28px;
}

.stat-card {
  padding: 24px 28px;
  text-align: center;
}

.stat-label {
  font-size: 14px;
  color: #a98d99;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 42px;
  font-weight: 800;
  color: var(--el-color-primary);
  line-height: 1.2;
}

.stat-value-sm {
  font-size: 24px;
  margin-top: 8px;
}

.stat-unit {
  font-size: 16px;
  font-weight: 600;
  margin-left: 2px;
}

.stat-sub {
  margin-top: 8px;
  font-size: 13px;
  color: #a98d99;
}

.stat-empty {
  margin: 14px 0 4px;
  font-size: 14px;
  color: #a98d99;
}

.publish-bar {
  margin-bottom: 24px;
}

/* 时间轴：绝对定位的分组撑起总高度，左侧竖线贯穿 */
/* 底部留出 40vh 空间，保证最后一个日期分组能被滚进高亮带（否则侧边栏永不点亮） */
.timeline {
  position: relative;
  padding-bottom: 40vh;
}

.timeline::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 10px;
  bottom: 10px;
  width: 2px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--el-color-primary-light-5), var(--el-color-primary-light-8));
}

/* 日期分组：左缩进 + 底部间隙 */
.date-group {
  padding-left: 28px;
  padding-bottom: 10px;
}

.timeline-item {
  position: relative;
  margin-bottom: 20px;
}

.timeline-dot {
  position: absolute;
  left: -26px;
  top: 22px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 3px solid var(--el-color-primary);
  box-shadow: 0 0 0 4px var(--el-color-primary-light-9);
}

.load-more {
  text-align: center;
  margin: 16px 0 40px;
}

.skeleton {
  margin-top: 8px;
}

.date-header {
  position: relative;
  margin: 0 0 14px;
}

.date-badge {
  display: inline-block;
  padding: 3px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 999px;
}

/* 日期快速跳转栏（固定在右侧，可按住滑动定位） */
.date-rail {
  position: fixed;
  right: 20px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 20;
  width: 96px;
  max-height: 60vh;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(236, 106, 143, 0.2);
  border-radius: 14px;
  box-shadow: 0 6px 24px rgba(236, 106, 143, 0.12);
  overflow: hidden;
}

.date-rail-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 10px 12px 6px;
  font-size: 13px;
  font-weight: 700;
  color: #6b5260;
}

.rail-calendar {
  font-size: 15px;
  color: var(--el-color-primary);
}

.date-rail-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 10px;
  user-select: none;
  touch-action: none;
}

/* 年月分组标题（也可点击/滑动定位到该月最新一天） */
.rail-month {
  padding: 8px 8px 2px;
  font-size: 11px;
  font-weight: 600;
  color: #c9a7b5;
  cursor: pointer;
  border-radius: 6px;
}

.rail-month:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.date-rail-item {
  padding: 5px 8px;
  margin: 2px 0;
  font-size: 12px;
  text-align: center;
  color: #8a6f7c;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.date-rail-item:hover {
  background: var(--el-color-primary-light-9);
}

.date-rail-item.active {
  background: var(--el-color-primary);
  color: #fff;
  font-weight: 600;
}

/* 滑动预览浮标 */
.rail-preview {
  position: fixed;
  right: 130px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 25;
  padding: 12px 20px;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  background: rgba(31, 27, 30, 0.85);
  border-radius: 12px;
  white-space: nowrap;
  pointer-events: none;
}

/* 小屏适配 */
@media (max-width: 640px) {
  .stat-cards {
    grid-template-columns: 1fr;
  }
}
</style>
