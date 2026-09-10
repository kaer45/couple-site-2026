<template>
  <div class="page album">
    <!-- 顶部操作栏 -->
    <div class="album-toolbar">
      <div class="section-title album-title">📷 我们的相册</div>
      <el-button type="primary" round @click="dialogVisible = true">
        <el-icon><FolderAdd /></el-icon>&nbsp;新建相册
      </el-button>
    </div>

    <!-- 相册卡片网格 -->
    <template v-if="loading">
      <el-skeleton :rows="4" animated />
    </template>
    <EmptyState v-else-if="!list.length" description="还没有相册，创建第一个吧" icon="📸">
      <el-button type="primary" round @click="dialogVisible = true">新建相册</el-button>
    </EmptyState>
    <div v-else class="album-grid">
      <div
        v-for="album in list"
        :key="album.id"
        class="album-card warm-card"
        @click="router.push(`/albums/${album.id}`)"
      >
        <!-- 封面：有封面图显示图片，否则显示占位 -->
        <div class="album-cover">
          <el-image v-if="album.coverUrl" :src="album.coverUrl" fit="cover" class="album-cover-img" lazy />
          <div v-else class="album-cover-placeholder">
            <el-icon :size="40"><Picture /></el-icon>
          </div>
          <div class="album-photo-count">{{ album.photoCount }} 张</div>
          <!-- 删除相册（阻止冒泡，避免触发跳转） -->
          <el-button
            class="album-delete"
            circle
            size="small"
            type="danger"
            plain
            @click.stop="handleRemove(album)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <div class="album-info">
          <div class="album-name">{{ album.name }}</div>
          <div class="album-desc">{{ album.description || '没有描述' }}</div>
        </div>
      </div>
    </div>

    <!-- 新建相册弹窗 -->
    <el-dialog v-model="dialogVisible" title="新建相册" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="相册名" prop="name">
          <el-input v-model="form.name" maxlength="30" show-word-limit placeholder="比如：2024 大理旅行" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="100" show-word-limit placeholder="写点备注（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" round :loading="submitting" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as albumApi from '@/api/album'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()

const list = ref([])
const loading = ref(false)

/* 新建弹窗 */
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()
const form = reactive({ name: '', description: '' })

const rules = {
  name: [{ required: true, message: '请输入相册名', trigger: 'blur' }]
}

onMounted(fetchList)

// 拉取相册列表
async function fetchList() {
  loading.value = true
  try {
    list.value = await albumApi.list()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// 创建相册
async function handleCreate() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await albumApi.create({ name: form.name, description: form.description || null })
    ElMessage.success('相册创建成功 🎉')
    dialogVisible.value = false
    form.name = ''
    form.description = ''
    await fetchList()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

// 删除相册
async function handleRemove(album) {
  try {
    await ElMessageBox.confirm(`确定删除相册「${album.name}」吗？相册内照片也会一并删除`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await albumApi.remove(album.id)
    ElMessage.success('删除成功')
    await fetchList()
  } catch (e) {
    /* 拦截器已提示 */
  }
}
</script>

<style scoped>
.album-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.album-title {
  margin: 0;
}

.album-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 20px;
}

.album-card {
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

.album-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 30px rgba(236, 106, 143, 0.22);
}

.album-cover {
  position: relative;
  aspect-ratio: 4 / 3;
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--el-color-primary-light-5));
}

.album-cover-img {
  width: 100%;
  height: 100%;
  display: block;
}

.album-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #fff;
}

.album-photo-count {
  position: absolute;
  left: 10px;
  bottom: 10px;
  padding: 2px 10px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.35);
  border-radius: 999px;
  backdrop-filter: blur(4px);
}

.album-delete {
  position: absolute;
  right: 10px;
  top: 10px;
  opacity: 0;
  transition: opacity 0.2s;
}

.album-card:hover .album-delete {
  opacity: 1;
}

.album-info {
  padding: 14px 16px 16px;
}

.album-name {
  font-size: 15px;
  font-weight: 700;
  color: #6b5260;
}

.album-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #a98d99;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
