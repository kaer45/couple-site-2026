<template>
  <el-container class="layout">
    <!-- 左侧菜单栏 -->
    <el-aside width="220px" class="layout-aside">
      <div class="logo">
        <span class="logo-icon">💕</span>
        <span>我们的小窝</span>
      </div>
      <el-menu :default-active="activeMenu" router class="layout-menu">
        <el-menu-item index="/">
          <el-icon>
            <House />
          </el-icon>
          <span>首页时间轴</span>
        </el-menu-item>
        <el-menu-item index="/anniversaries">
          <el-icon>
            <Calendar />
          </el-icon>
          <span>纪念日</span>
        </el-menu-item>
        <el-menu-item index="/albums">
          <el-icon>
            <Camera />
          </el-icon>
          <span>相册</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="layout-right">
      <!-- 顶部栏：昵称 + 头像 + 退出 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-tag v-if="!userStore.isBound" type="warning" effect="light" class="bind-tip"
            @click="router.push('/bind')">
            🎀 还未绑定情侣，点我去绑定
          </el-tag>
        </div>
        <div class="header-right">
          <!-- 通知铃铛：未读红点 + 下拉面板 -->
          <el-popover v-model:visible="notifVisible" placement="bottom-end" :width="360" trigger="manual"
            popper-class="notif-popper">
            <template #reference>
              <el-badge :value="notifStore.unread" :hidden="notifStore.unread === 0" :max="99" class="notif-bell">
                <el-button class="bell-btn" circle text @click="toggleNotifPanel">
                  <el-icon :size="20">
                    <Bell />
                  </el-icon>
                </el-button>
              </el-badge>
            </template>
            <div class="notif-panel">
              <div class="notif-panel-title">通知</div>
              <template v-if="notifLoading">
                <el-skeleton :rows="3" animated />
              </template>
              <template v-else-if="recent.length">
                <div v-for="n in recent" :key="n.id" class="notif-item" :class="{ unread: !n.isRead }"
                  @click="markOneRead(n)">
                  <span v-if="!n.isRead" class="notif-dot"></span>
                  <div class="notif-body">
                    <div class="notif-item-title">{{ n.title }}</div>
                    <div class="notif-item-content">{{ n.content }}</div>
                  </div>
                  <span class="notif-item-time">{{ dayjs(n.createdAt).fromNow() }}</span>
                </div>
              </template>
              <EmptyState v-else description="暂无通知" icon="🔔" icon-component="Bell" />
              <div class="notif-footer">
                <el-button link type="primary" @click="goNotifications">查看全部</el-button>
              </div>
            </div>
          </el-popover>

          <el-tooltip content="点击更换头像" placement="bottom">
            <el-avatar :size="36" :src="userStore.userInfo?.avatar || undefined" class="header-avatar clickable"
              @click="avatarDialogVisible = true">
              {{ avatarText }}
            </el-avatar>
          </el-tooltip>
          <span class="header-nickname">{{ userStore.nickname }}</span>
          <el-button link type="danger" @click="handleLogout">
            <el-icon>
              <SwitchButton />
            </el-icon>&nbsp;退出
          </el-button>
        </div>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 更换头像弹窗：先经 /api/files/upload 上传，再调用 PUT /api/auth/avatar 保存 -->
    <el-dialog v-model="avatarDialogVisible" title="更换头像" width="420px" align-center>
      <div class="avatar-upload-wrap">
        <el-upload :action="uploadAction" :headers="uploadHeaders" :show-file-list="false" accept="image/*"
          :before-upload="beforeAvatarUpload" :on-success="handleAvatarSuccess" :on-error="handleAvatarError">
          <el-avatar :size="120" :src="avatarPreview || userStore.userInfo?.avatar || undefined" class="avatar-preview">
            {{ avatarText }}
          </el-avatar>
          <div class="avatar-upload-tip">点击头像选择新图片<br />支持 jpg / png / gif / webp，≤ 10MB</div>
        </el-upload>
      </div>
      <template #footer>
        <el-button round @click="avatarDialogVisible = false">关 闭</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import * as authApi from '@/api/auth'
import * as notificationApi from '@/api/notification'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const notifStore = useNotificationStore()

// 菜单高亮跟随路由（相册详情页 /albums/:id 也高亮"相册"）
const activeMenu = computed(() => {
  if (route.path.startsWith('/albums')) return '/albums'
  return route.path
})

// 头像兜底：昵称首字符
const avatarText = computed(() => (userStore.nickname || '亲').charAt(0))

/* ========== 通知铃铛 ========== */
const notifVisible = ref(false)
const notifLoading = ref(false)
const recent = ref([])

// 加载最近 5 条（打开面板时）
async function loadRecent() {
  notifLoading.value = true
  try {
    const data = await notificationApi.page({ current: 1, size: 5 })
    recent.value = data.records || []
    await notifStore.refresh()
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    notifLoading.value = false
  }
}

function toggleNotifPanel() {
  notifVisible.value = !notifVisible.value
  if (notifVisible.value && !recent.value.length) {
    loadRecent()
  }
}

// 点击单条：仅标记已读，不跳转
async function markOneRead(n) {
  if (n.isRead) return
  try {
    await notificationApi.markRead(n.id)
    n.isRead = true
    notifStore.decrement()
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

// 查看全部 → 跳通知页
function goNotifications() {
  notifVisible.value = false
  router.push('/notifications')
}

/* ========== 更换头像 ========== */
const avatarDialogVisible = ref(false)
const avatarPreview = ref('')
const uploadAction = '/api/files/upload'
// el-upload 直连上传，手动携带 Authorization 头
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${userStore.token}` }))

// 上传前校验：仅图片且 ≤ 10MB（与后端一致）
function beforeAvatarUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isLt10M = file.size / 1024 / 1024 < 10
  if (!isImage) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (!isLt10M) {
    ElMessage.error('图片大小不能超过 10MB')
    return false
  }
  return true
}

// 上传成功 → 调用更新头像接口并刷新用户信息
async function handleAvatarSuccess(response) {
  if (response && response.code === 0) {
    avatarPreview.value = response.data.url
    try {
      await authApi.updateAvatar({ avatarUrl: response.data.url })
      await userStore.fetchMe() // 刷新用户信息（头像、昵称等）
      ElMessage.success('头像更新成功 🎉')
      avatarDialogVisible.value = false
    } catch (e) {
      /* 错误提示已由拦截器统一处理 */
    }
  } else {
    ElMessage.error(response?.message || '头像上传失败')
  }
}

function handleAvatarError() {
  ElMessage.error('头像上传失败，请重试')
}

// 刷新页面后恢复用户信息（token 存在但 userInfo 为空时 + 拉取未读数）
onMounted(async () => {
  if (userStore.token && !userStore.userInfo) {
    try {
      await userStore.fetchMe()
    } catch (e) {
      /* 401 已由拦截器统一处理 */
    }
  }
  if (userStore.token) {
    notifStore.refresh()
  }
})

// 退出登录
function handleLogout() {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '退出',
    cancelButtonText: '再想想',
    type: 'warning'
  })
    .then(() => {
      userStore.logout()
      notifStore.reset()
      ElMessage.success('已退出登录，期待下次见面～')
      router.push('/login')
    })
    .catch(() => { })
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(8px);
  border-right: 1px solid rgba(236, 106, 143, 0.15);
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 60px;
  font-size: 18px;
  font-weight: 700;
  color: #ec6a8f;
}

.logo-icon {
  font-size: 22px;
}

.layout-menu {
  flex: 1;
  border-right: none;
  background: transparent;
}

.layout-menu :deep(.el-menu-item) {
  height: 46px;
  margin: 4px 10px;
  border-radius: 12px;
}

.layout-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.layout-right {
  background: transparent;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(236, 106, 143, 0.12);
}

.header-left {
  display: flex;
  align-items: center;
}

.bind-tip {
  cursor: pointer;
  border-radius: 999px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-avatar {
  background: var(--el-color-primary-light-5);
  color: #fff;
  font-weight: 600;
}

.clickable {
  cursor: pointer;
}

.avatar-upload-wrap {
  text-align: center;
}

.avatar-preview {
  border: 3px dashed var(--el-color-primary-light-5);
  cursor: pointer;
  transition: transform 0.2s;
}

.avatar-preview:hover {
  transform: scale(1.03);
}

.avatar-upload-tip {
  margin-top: 14px;
  font-size: 13px;
  line-height: 1.7;
  color: #a98d99;
}

.header-nickname {
  font-weight: 600;
  color: #6b5260;
}

.layout-main {
  padding: 24px;
  overflow-y: auto;
}

/* ========== 通知铃铛 ========== */
.notif-bell {
  margin-right: 8px;
}

.bell-btn {
  border: none;
  background: transparent;
  color: #ec6a8f;
}

.bell-btn:hover {
  background: rgba(236, 106, 143, 0.1);
}

.notif-popper {
  padding: 0;
}

.notif-panel {
  width: 340px;
  max-height: 420px;
  overflow-y: auto;
}

.notif-panel-title {
  font-weight: 600;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: #6b5260;
}

.notif-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  cursor: pointer;
  border-bottom: 1px solid rgba(236, 106, 143, 0.06);
  transition: background 0.2s;
}

.notif-item:hover {
  background: rgba(236, 106, 143, 0.04);
}

.notif-item.unread {
  background: rgba(236, 106, 143, 0.08);
  font-weight: 600;
}

.notif-dot {
  display: block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ec6a8f;
  margin-top: 8px;
  flex-shrink: 0;
}

.notif-body {
  flex: 1;
  min-width: 0;
}

.notif-item-title {
  color: #2c2c2c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unread .notif-item-title {
  color: #ec6a8f;
}

.notif-item-content {
  font-size: 13px;
  color: #888;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 2px;
}

.notif-item-time {
  font-size: 12px;
  color: #aaa;
  flex-shrink: 0;
  white-space: nowrap;
}

.notif-footer {
  text-align: center;
  padding: 6px 0;
}
</style>
