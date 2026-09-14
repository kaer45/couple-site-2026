<template>
  <div class="page album-detail">
    <!-- 返回按钮 -->
    <el-button class="back-btn" round @click="router.push('/albums')">
      <el-icon><ArrowLeft /></el-icon>&nbsp;返回相册
    </el-button>

    <!-- 相册信息 -->
    <div class="detail-header">
      <div>
        <div class="detail-name"><el-icon class="title-icon"><Camera /></el-icon>{{ album.name || '加载中…' }}</div>
        <div class="detail-desc">{{ album.description || '这个相册还没有描述' }}</div>
      </div>
      <div class="detail-actions">
        <!-- 上传照片/视频（触发 el-upload 选择文件，自动上传到 /api/files/upload） -->
        <el-upload
          ref="uploadRef"
          :action="uploadAction"
          :headers="uploadHeaders"
          multiple
          accept="image/*,video/*"
          :show-file-list="false"
          :on-success="handleUploadSuccess"
          :on-error="handleUploadError"
          :before-upload="beforeUpload"
        >
          <el-button type="primary" round>
            <el-icon><UploadFilled /></el-icon>&nbsp;上传照片/视频
          </el-button>
        </el-upload>
        <!-- 设置封面：从相册选一张图，或上传新图片 -->
        <el-dropdown trigger="click" @command="handleCoverCommand">
          <el-button round>
            <el-icon><PictureFilled /></el-icon>&nbsp;设置封面
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="pick" :disabled="!imagePhotos.length">从相册选择</el-dropdown-item>
              <el-dropdown-item command="upload">上传新图片</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <!-- 待保存媒体的临时操作条 -->
    <div v-if="pendingUrls.length" class="pending-bar warm-card">
      <span class="pending-text">已选择 <b>{{ pendingUrls.length }}</b> 个文件</span>
      <el-input
        v-model="pendingDescription"
        placeholder="给这批照片/视频写点描述（选填）"
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
      description="相册还是空的，上传第一张照片或视频吧"
      icon="🖼️"
      icon-component="Picture"
    >
      <el-button type="primary" round @click="triggerUpload">上传照片/视频</el-button>
    </EmptyState>
    <div v-else class="photo-months">
      <div v-for="group in monthGroups" :key="group.month" class="photo-month">
        <div class="month-header">
          <span class="month-badge">{{ formatMonth(group.month) }}</span>
          <span class="month-count">{{ group.photos.length }} 个</span>
        </div>
        <div class="photo-grid">
          <div v-for="photo in group.photos" :key="photo.id" class="photo-item warm-card" @click="openViewer(photo)">
            <template v-if="photo.type === 'VIDEO'">
              <!-- 视频缩略：preload=metadata 只取首帧，不拉全量数据 -->
              <video :src="photo.url" muted preload="metadata" playsinline class="photo-img" />
              <div class="video-badge"><el-icon><VideoPlay /></el-icon></div>
            </template>
            <el-image v-else :src="photo.url" fit="cover" class="photo-img" lazy />
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
              <div class="photo-desc"><el-icon v-if="!photo.description" class="photo-desc-icon"><Camera /></el-icon>{{ photo.description }}</div>
              <div class="photo-meta">
                {{ photo.uploaderNickname }} · {{ dayjs(photo.createdAt).format('YYYY-MM-DD') }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 选择封面图片弹窗（仅图片可作为封面） ===== -->
    <el-dialog v-model="coverVisible" title="选择封面图片" width="min(92vw, 720px)" align-center>
      <div class="cover-grid">
        <div
          v-for="photo in imagePhotos"
          :key="photo.id"
          class="cover-item"
          :class="{ 'is-active': photo.url === album.coverUrl }"
          @click="pickCover(photo)"
        >
          <el-image :src="photo.url" fit="cover" lazy />
          <el-icon v-if="photo.url === album.coverUrl" class="cover-check"><Check /></el-icon>
        </div>
      </div>
      <template #footer>
        <el-button round @click="coverVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 隐藏的上传控件：上传新封面图 -->
    <el-upload
      ref="coverUploadRef"
      :action="uploadAction"
      :headers="uploadHeaders"
      accept="image/*"
      :show-file-list="false"
      :on-success="handleCoverUploadSuccess"
      :on-error="handleUploadError"
      :before-upload="beforeCoverUpload"
      class="cover-upload-hidden"
    />

    <!-- ===== 幻灯片查看器（点击照片打开，支持 ←/→ 键切换、下载、删除） ===== -->
    <el-dialog v-model="viewerVisible" width="min(92vw, 960px)" top="4vh" align-center class="viewer-dialog" destroy-on-close>
      <div class="viewer-wrap">
        <div class="viewer-stage">
          <div class="viewer-counter">{{ viewerIndex + 1 }} / {{ allPhotos.length }}</div>
          <el-button class="viewer-nav prev" circle @click="viewerPrev" :disabled="allPhotos.length <= 1">
            <el-icon><ArrowLeft /></el-icon>
          </el-button>
          <!-- 不带 autoplay：浏览器会拦截带声音的自动播放（画面动声音被吞），用户主动点播放才有声音 -->
          <video v-if="viewerPhoto?.type === 'VIDEO'" :src="viewerPhoto.url" controls playsinline class="viewer-img" />
          <el-image v-else-if="viewerPhoto" :src="viewerPhoto.url" fit="contain" class="viewer-img" />
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
            <el-button link @click="downloadViewerPhoto">⬇ {{ viewerPhoto.type === 'VIDEO' ? '下载视频' : '下载原图' }}</el-button>
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
const pendingTypes = ref([]) // 与 pendingUrls 一一对应的媒体类型（IMAGE/VIDEO）
const pendingDescription = ref('')
const saving = ref(false)

/* 封面相关 */
const coverVisible = ref(false)
const coverUploadRef = ref()

onMounted(fetchDetail)

/* ========== 月份分组（按 createdAt 的 yyyy-MM） ========== */
const allPhotos = computed(() => album.value.photos || [])

// 可作封面的图片（视频不能当封面）
const imagePhotos = computed(() => allPhotos.value.filter((p) => p.type !== 'VIDEO'))

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

// 选择文件前校验：图片 ≤ 10MB，视频 ≤ 200MB（对应后端 multipart 上限）
function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isVideo = file.type.startsWith('video/')
  if (!isImage && !isVideo) {
    ElMessage.warning('只能上传图片或视频文件')
    return false
  }
  const maxMB = isVideo ? 200 : 10
  const maxSize = maxMB * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.warning(isVideo ? '单个视频不能超过 200MB' : '单张图片不能超过 10MB')
    return false
  }
  return true
}

// 上传成功：收集 url 与类型到待保存列表（类型从上传文件推断，缺省按 IMAGE）
function handleUploadSuccess(response, uploadFile) {
  if (response && response.code === 0) {
    pendingUrls.value.push(response.data.url)
    const type = uploadFile?.raw?.type?.startsWith('video/') ? 'VIDEO' : 'IMAGE'
    pendingTypes.value.push(type)
  } else {
    ElMessage.error(response?.message || '上传失败')
  }
}

function handleUploadError() {
  ElMessage.error('上传失败，请重试')
}

// 触发文件选择（空状态下使用）
function triggerUpload() {
  uploadRef.value?.$el.querySelector('input')?.click()
}

/* ========== 封面设置 ========== */

// 下拉命令：pick=从相册选，upload=上传新图片
function handleCoverCommand(cmd) {
  if (cmd === 'pick') coverVisible.value = true
  else if (cmd === 'upload') coverUploadRef.value?.$el.querySelector('input')?.click()
}

// 从相册选中一张作为封面
async function pickCover(photo) {
  if (photo.url === album.value.coverUrl) {
    coverVisible.value = false
    return
  }
  try {
    await albumApi.setCover(albumId, photo.url)
    album.value.coverUrl = photo.url
    coverVisible.value = false
    ElMessage.success('封面已更新')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

// 封面图上传前校验：仅图片且 ≤ 10MB
function beforeCoverUpload(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('封面只能使用图片')
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('单张图片不能超过 10MB')
    return false
  }
  return true
}

// 新封面图上传成功：直接设为封面
async function handleCoverUploadSuccess(response) {
  if (response && response.code === 0) {
    try {
      await albumApi.setCover(albumId, response.data.url)
      album.value.coverUrl = response.data.url
      ElMessage.success('封面已更新')
    } catch (e) {
      /* 拦截器已提示 */
    }
  } else {
    ElMessage.error(response?.message || '封面图片上传失败')
  }
}

// 保存到相册：uploadPhotos(id, urls, types, description)
async function handleSavePhotos() {
  if (!pendingUrls.value.length) return
  saving.value = true
  try {
    await albumApi.uploadPhotos(albumId, [...pendingUrls.value], [...pendingTypes.value], pendingDescription.value || null)
    ElMessage.success('已保存 🎉')
    cancelPending()
    await fetchDetail() // 刷新媒体列表
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

// 取消待保存状态
function cancelPending() {
  pendingUrls.value = []
  pendingTypes.value = []
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

.title-icon {
  vertical-align: -3px;
  margin-right: 6px;
  color: var(--el-color-primary);
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

/* 视频角标：网格中标识视频，点击进入查看器播放 */
.video-badge {
  position: absolute;
  right: 10px;
  bottom: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  pointer-events: none;
  z-index: 1;
}

.video-badge .el-icon {
  font-size: 16px;
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

.photo-desc-icon {
  vertical-align: -2px;
  margin-right: 4px;
  color: #a98d99;
}

.photo-meta {
  margin-top: 3px;
  font-size: 11px;
  color: #b7a0aa;
}

/* 选择封面弹窗 */
.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  max-height: 55vh;
  overflow-y: auto;
}

.cover-item {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  border: 2px solid transparent;
  aspect-ratio: 1;
  transition: border-color 0.2s;
}

.cover-item:hover {
  border-color: var(--el-color-primary-light-5);
}

.cover-item.is-active {
  border-color: var(--el-color-primary);
}

.cover-item .el-image {
  display: block;
  width: 100%;
  height: 100%;
}

.cover-check {
  position: absolute;
  right: 6px;
  top: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  background: var(--el-color-primary);
}

.cover-upload-hidden {
  display: none;
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
  object-fit: contain; /* 视频元素直接应用，图片由 el-image fit 控制 */
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
