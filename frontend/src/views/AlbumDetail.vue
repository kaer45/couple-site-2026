<template>
  <div class="page album-detail">
    <!-- 返回按钮 -->
    <el-button class="back-btn" round @click="router.push('/albums')">
      <el-icon><ArrowLeft /></el-icon>&nbsp;返回相册
    </el-button>

    <!-- 相册信息 -->
    <div class="detail-header">
      <div>
        <div class="detail-name">📷 {{ album.name || '加载中…' }}</div>
        <div class="detail-desc">{{ album.description || '这个相册还没有描述' }}</div>
      </div>
      <div class="detail-actions">
        <!-- 上传照片（触发 el-upload 选择文件，自动上传到 /api/files/upload） -->
        <el-upload
          ref="uploadRef"
          :action="uploadAction"
          :headers="uploadHeaders"
          multiple
          accept="image/*"
          :show-file-list="false"
          :on-success="handleUploadSuccess"
          :on-error="handleUploadError"
          :before-upload="beforeUpload"
        >
          <el-button type="primary" round>
            <el-icon><UploadFilled /></el-icon>&nbsp;上传照片
          </el-button>
        </el-upload>
      </div>
    </div>

    <!-- 待保存照片的临时操作条 -->
    <div v-if="pendingUrls.length" class="pending-bar warm-card">
      <span class="pending-text">已选择 <b>{{ pendingUrls.length }}</b> 张照片</span>
      <el-input
        v-model="pendingDescription"
        placeholder="给这批照片写点描述（选填）"
        class="pending-desc-input"
        clearable
      />
      <el-button type="primary" round :loading="saving" @click="handleSavePhotos">保存到相册</el-button>
      <el-button round @click="cancelPending">取消</el-button>
    </div>

    <!-- 照片网格 -->
    <template v-if="loading">
      <el-skeleton :rows="4" animated />
    </template>
    <EmptyState
      v-else-if="!album.photos || !album.photos.length"
      description="相册还是空的，上传第一张照片吧"
      icon="🖼️"
    >
      <el-button type="primary" round @click="triggerUpload">上传照片</el-button>
    </EmptyState>
    <div v-else class="photo-months">
      <div v-for="group in monthGroups" :key="group.month" class="photo-month">
        <div class="month-header">
          <span class="month-badge">{{ formatMonth(group.month) }}</span>
          <span class="month-count">{{ group.photos.length }} 张</span>
        </div>
        <div class="photo-grid">
          <div v-for="photo in group.photos" :key="photo.id" class="photo-item warm-card" @click="openViewer(photo)">
            <el-image :src="photo.url" fit="cover" class="photo-img" lazy />
            <!-- hover 遮罩：查看大图 +（自己上传的）删除 -->
            <div class="photo-mask">
              <el-button circle size="small" type="primary" plain @click.stop="openViewer(photo)">
                <el-icon><ZoomIn /></el-icon>
              </el-button>
              <el-button
                v-if="photo.userId === userStore.userInfo?.id"
                circle
                type="danger"
                size="small"
                @click.stop="handleRemovePhoto(photo)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <div class="photo-caption">
              <div class="photo-desc">{{ photo.description || '📷' }}</div>
              <div class="photo-meta">
                {{ photo.uploaderNickname }} · {{ dayjs(photo.createdAt).format('YYYY-MM-DD') }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 幻灯片查看器（点击照片打开，支持 ←/→ 键切换、下载、删除） ===== -->
    <el-dialog v-model="viewerVisible" width="min(92vw, 960px)" top="4vh" align-center class="viewer-dialog" destroy-on-close>
      <div class="viewer-wrap">
        <div class="viewer-stage">
          <div class="viewer-counter">{{ viewerIndex + 1 }} / {{ allPhotos.length }}</div>
          <el-button class="viewer-nav prev" circle @click="viewerPrev" :disabled="allPhotos.length <= 1">
            <el-icon><ArrowLeft /></el-icon>
          </el-button>
          <el-image v-if="viewerPhoto" :src="viewerPhoto.url" fit="contain" class="viewer-img" />
          <el-button class="viewer-nav next" circle @click="viewerNext" :disabled="allPhotos.length <= 1">
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
        <div v-if="viewerPhoto" class="viewer-info">
          <div class="viewer-desc">{{ viewerPhoto.description || '没有描述' }}</div>
          <div class="viewer-meta">
            {{ viewerPhoto.uploaderNickname }} · {{ dayjs(viewerPhoto.createdAt).format('YYYY-MM-DD HH:mm') }}
          </div>
          <div class="viewer-actions">
            <el-button link @click="downloadViewerPhoto">⬇ 下载原图</el-button>
            <el-button
              v-if="viewerPhoto.userId === userStore.userInfo?.id"
              link
              type="danger"
              @click="removeViewerPhoto"
            >删除</el-button>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import * as albumApi from '@/api/album'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const albumId = route.params.id

const album = ref({}) // 相册详情 { id, name, description, coverUrl, photos }
const loading = ref(false)

/* 上传相关 */
const uploadRef = ref()
const uploadAction = '/api/files/upload'
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${userStore.token}` }))
const pendingUrls = ref([]) // 已上传成功、待保存到相册的 url
const pendingDescription = ref('')
const saving = ref(false)

onMounted(fetchDetail)

/* ========== 月份分组（按 createdAt 的 yyyy-MM） ========== */
const allPhotos = computed(() => album.value.photos || [])

const monthGroups = computed(() => {
  const map = new Map()
  for (const p of allPhotos.value) {
    const m = (p.createdAt || '').slice(0, 7) // yyyy-MM
    if (!map.has(m)) map.set(m, [])
    map.get(m).push(p)
  }
  return [...map.entries()].map(([month, photos]) => ({ month, photos }))
})

function formatMonth(month) {
  return `${month.slice(0, 4)}年${Number(month.slice(5))}月`
}

/* ========== 幻灯片查看器 ========== */
const viewerVisible = ref(false)
const viewerIndex = ref(0)
const viewerPhoto = computed(() => allPhotos.value[viewerIndex.value] || null)

// 打开查看器：从点击的那张开始
function openViewer(photo) {
  viewerIndex.value = allPhotos.value.findIndex((p) => p.id === photo.id)
  viewerVisible.value = true
}

// 上一张 / 下一张（首尾循环）
function viewerPrev() {
  if (allPhotos.value.length <= 1) return
  viewerIndex.value = (viewerIndex.value - 1 + allPhotos.value.length) % allPhotos.value.length
}

function viewerNext() {
  if (allPhotos.value.length <= 1) return
  viewerIndex.value = (viewerIndex.value + 1) % allPhotos.value.length
}

// 键盘 ←/→ 切换
function onViewerKeydown(e) {
  if (e.key === 'ArrowLeft') viewerPrev()
  else if (e.key === 'ArrowRight') viewerNext()
}

watch(viewerVisible, (v) => {
  if (v) window.addEventListener('keydown', onViewerKeydown)
  else window.removeEventListener('keydown', onViewerKeydown)
})

onUnmounted(() => window.removeEventListener('keydown', onViewerKeydown))

// 下载原图（浏览器默认下载，文件名取 URL 末尾）
function downloadViewerPhoto() {
  const url = viewerPhoto.value?.url
  if (!url) return
  const a = document.createElement('a')
  a.href = url
  a.download = url.split('/').pop() || 'photo'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

// 幻灯片内删除：先关弹窗，再走原有删除确认流程
async function removeViewerPhoto() {
  viewerVisible.value = false
  await handleRemovePhoto(viewerPhoto.value)
}

// 拉取相册详情
async function fetchDetail() {
  loading.value = true
  try {
    album.value = await albumApi.detail(albumId)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// 选择文件前校验：仅图片且 ≤ 10MB（契约 §5.1）
function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isLt10M = file.size / 1024 / 1024 < 10
  if (!isImage) {
    ElMessage.warning('只能上传图片文件')
    return false
  }
  if (!isLt10M) {
    ElMessage.warning('单张图片不能超过 10MB')
    return false
  }
  return true
}

// 上传成功：收集 url 到待保存列表
function handleUploadSuccess(response) {
  if (response && response.code === 0) {
    pendingUrls.value.push(response.data.url)
  } else {
    ElMessage.error(response?.message || '图片上传失败')
  }
}

function handleUploadError() {
  ElMessage.error('图片上传失败，请重试')
}

// 触发文件选择（空状态下使用）
function triggerUpload() {
  uploadRef.value?.$el.querySelector('input')?.click()
}

// 保存照片到相册：uploadPhotos(id, urls, description)
async function handleSavePhotos() {
  if (!pendingUrls.value.length) return
  saving.value = true
  try {
    await albumApi.uploadPhotos(albumId, [...pendingUrls.value], pendingDescription.value || null)
    ElMessage.success('照片已保存 🎉')
    cancelPending()
    await fetchDetail() // 刷新照片列表
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// 取消待保存状态
function cancelPending() {
  pendingUrls.value = []
  pendingDescription.value = ''
  uploadRef.value?.clearFiles()
}

// 删除照片（只能删自己上传的，后端越权会返回 403）
async function handleRemovePhoto(photo) {
  try {
    await ElMessageBox.confirm('确定删除这张照片吗？', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await albumApi.removePhoto(albumId, photo.id)
    ElMessage.success('删除成功')
    await fetchDetail()
  } catch (e) {
    /* 拦截器已提示（含 403） */
  }
}
</script>

<style scoped>
.back-btn {
  margin-bottom: 18px;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.detail-name {
  font-size: 22px;
  font-weight: 800;
  color: #6b5260;
}

.detail-desc {
  margin-top: 6px;
  font-size: 13px;
  color: #a98d99;
}

.detail-actions {
  display: flex;
  gap: 10px;
}

/* 待保存操作条 */
.pending-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.pending-text {
  font-size: 14px;
  color: #6b5260;
}

.pending-desc-input {
  flex: 1;
  min-width: 200px;
}

/* 月份分组 */
.photo-month {
  margin-bottom: 28px;
}

.month-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.month-badge {
  font-size: 15px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.month-count {
  font-size: 12px;
  color: #b7a0aa;
}

/* 照片网格 */
.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 18px;
}

.photo-item {
  position: relative;
  overflow: hidden;
}

.photo-img {
  display: block;
  width: 100%;
  aspect-ratio: 1;
}

/* hover 遮罩：显示删除按钮 */
.photo-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.35);
  opacity: 0;
  transition: opacity 0.2s;
}

.photo-item:hover .photo-mask {
  opacity: 1;
}

.photo-caption {
  padding: 10px 12px;
}

.photo-desc {
  font-size: 13px;
  color: #6b5260;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-meta {
  margin-top: 3px;
  font-size: 11px;
  color: #b7a0aa;
}

/* 幻灯片查看器 */
.viewer-dialog :deep(.el-dialog__body) {
  padding: 0;
}

.viewer-wrap {
  background: #1f1b1e;
  border-radius: 8px;
  overflow: hidden;
}

.viewer-stage {
  position: relative;
  height: min(72vh, 720px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.viewer-img {
  width: 100%;
  height: 100%;
}

.viewer-counter {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  padding: 3px 12px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  background: rgba(0, 0, 0, 0.45);
  border-radius: 999px;
  z-index: 2;
}

.viewer-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  background: rgba(255, 255, 255, 0.85) !important;
  border: none !important;
}

.viewer-nav.prev {
  left: 14px;
}

.viewer-nav.next {
  right: 14px;
}

.viewer-info {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 12px 18px;
  background: #fff;
}

.viewer-desc {
  font-size: 14px;
  color: #6b5260;
  font-weight: 600;
}

.viewer-meta {
  font-size: 12px;
  color: #a98d99;
}

.viewer-actions {
  margin-left: auto;
  display: flex;
  gap: 4px;
}
</style>
