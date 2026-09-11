<template>
  <div class="page notifications">
    <!-- 头部：标题 + 全部已读 -->
    <div class="notif-head">
      <div class="page-title">通知</div>
      <el-button
        v-if="list.some((n) => !n.isRead)"
        round
        type="primary"
        plain
        @click="handleReadAll"
      >
        <el-icon><Finished /></el-icon>&nbsp;全部已读
      </el-button>
    </div>

    <!-- 加载中 -->
    <template v-if="loading && !list.length">
      <el-skeleton :rows="5" animated />
    </template>

    <!-- 空态 -->
    <EmptyState v-else-if="!list.length" description="还没有通知" icon="🔔" />

    <!-- 列表 -->
    <div v-else class="notif-list">
      <div
        v-for="n in list"
        :key="n.id"
        class="notif-item warm-card"
        :class="{ unread: !n.isRead }"
        @click="handleMarkRead(n)"
      >
        <span v-if="!n.isRead" class="notif-dot"></span>
        <div class="notif-main">
          <div class="notif-title">{{ n.title }}</div>
          <div class="notif-content">{{ n.content }}</div>
          <div class="notif-time">{{ dayjs(n.createdAt).fromNow() }}</div>
        </div>
        <el-tag v-if="n.isRead" size="small" type="info" effect="plain" round>已读</el-tag>
      </div>

      <!-- 加载更多 -->
      <div v-if="hasMore" class="notif-more">
        <el-button :loading="loading" round @click="loadMore">加载更多</el-button>
      </div>
      <div v-else class="notif-more notif-end">没有更多啦～</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import * as notificationApi from '@/api/notification'
import { useNotificationStore } from '@/stores/notification'
import EmptyState from '@/components/EmptyState.vue'

const notifStore = useNotificationStore()

const list = ref([])
const loading = ref(false)
const current = ref(1)
const size = 10
const hasMore = ref(false)

async function fetchPage(page) {
  loading.value = true
  try {
    const data = await notificationApi.page({ current: page, size })
    const records = data.records || []
    if (page === 1) {
      list.value = records
    } else {
      list.value = [...list.value, ...records]
    }
    current.value = page
    hasMore.value = current.value < data.pages
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function loadMore() {
  fetchPage(current.value + 1)
}

// 点击单条：仅标记已读，不跳转
async function handleMarkRead(n) {
  if (n.isRead) return
  try {
    await notificationApi.markRead(n.id)
    n.isRead = true
    notifStore.decrement()
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

// 全部已读
async function handleReadAll() {
  try {
    await notificationApi.readAll()
    list.value.forEach((n) => (n.isRead = true))
    await notifStore.refresh()
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}

onMounted(() => {
  fetchPage(1)
})
</script>

<style scoped>
.notifications {
  max-width: 720px;
  margin: 0 auto;
}

.notif-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: #6b5260;
}

.notif-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.notif-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  cursor: pointer;
}

.notif-item:hover {
  border-color: rgba(236, 106, 143, 0.4);
}

.notif-item.unread {
  background: rgba(236, 106, 143, 0.05);
}

.notif-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #ec6a8f;
  flex-shrink: 0;
}

.notif-main {
  flex: 1;
  min-width: 0;
}

.notif-title {
  color: #2c2c2c;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unread .notif-title {
  color: #ec6a8f;
}

.notif-content {
  font-size: 14px;
  color: #666;
  margin-top: 4px;
  line-height: 1.6;
}

.notif-time {
  font-size: 12px;
  color: #aaa;
  margin-top: 6px;
}

.notif-more {
  text-align: center;
  padding: 16px 0;
}

.notif-end {
  color: #bbb;
  font-size: 13px;
}
</style>