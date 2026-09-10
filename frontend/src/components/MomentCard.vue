<template>
  <div class="moment-card warm-card">
    <!-- 头部：头像 + 昵称 + 相对时间 -->
    <div class="moment-header">
      <el-avatar :size="42" :src="moment.avatar || undefined" class="moment-avatar">
        {{ (moment.nickname || '亲').charAt(0) }}
      </el-avatar>
      <div class="moment-meta">
        <div class="moment-nickname">{{ moment.nickname }}</div>
        <div class="moment-time">{{ relativeTime }}</div>
      </div>
      <!-- 只有自己发布的动态才显示删除按钮 -->
      <el-button v-if="isMine" class="moment-delete" link type="danger" @click="handleDelete">
        删除
      </el-button>
    </div>

    <!-- 内容文本 -->
    <div class="moment-content">{{ moment.content }}</div>

    <!-- 图片九宫格（点击放大预览） -->
    <div
      v-if="moment.images && moment.images.length"
      class="moment-images"
      :class="{ single: moment.images.length === 1 }"
    >
      <el-image
        v-for="(img, index) in moment.images"
        :key="img"
        :src="img"
        :preview-src-list="moment.images"
        :initial-index="index"
        fit="cover"
        class="moment-img"
        lazy
      />
    </div>

    <!-- 地点 -->
    <div v-if="moment.location" class="moment-location">📍 {{ moment.location }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import * as momentApi from '@/api/moment'

const props = defineProps({
  moment: { type: Object, required: true }
})
const emit = defineEmits(['deleted'])

const userStore = useUserStore()

// 相对时间：配合 dayjs 中文 locale 显示如 "3 天前"、"刚刚"
const relativeTime = computed(() => dayjs(props.moment.createdAt).fromNow())

// 是否自己发布的动态（按 userId 判断）
const isMine = computed(() => props.moment.userId === userStore.userInfo?.id)

// 删除动态
async function handleDelete() {
  // 二次确认
  try {
    await ElMessageBox.confirm('确定删除这条动态吗？删除后无法恢复哦', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await momentApi.remove(props.moment.id)
    ElMessage.success('删除成功')
    emit('deleted', props.moment.id)
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  }
}
</script>

<style scoped>
.moment-card {
  padding: 20px 22px;
}

.moment-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.moment-avatar {
  flex-shrink: 0;
  background: var(--el-color-primary-light-5);
  color: #fff;
  font-weight: 600;
}

.moment-meta {
  flex: 1;
  min-width: 0;
}

.moment-nickname {
  font-size: 15px;
  font-weight: 700;
  color: #6b5260;
}

.moment-time {
  margin-top: 2px;
  font-size: 12px;
  color: #b7a0aa;
}

.moment-delete {
  flex-shrink: 0;
}

.moment-content {
  margin-top: 12px;
  font-size: 15px;
  line-height: 1.7;
  color: #5c4a52;
  white-space: pre-wrap;
  word-break: break-word;
}

.moment-images {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 12px;
}

/* 单张图片时放大展示 */
.moment-images.single {
  grid-template-columns: 1fr;
  max-width: 65%;
}

.moment-img {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 10px;
}

.moment-location {
  margin-top: 10px;
  font-size: 13px;
  color: #8a7380;
}
</style>
