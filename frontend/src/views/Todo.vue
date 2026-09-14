<template>
  <div class="page todo">
    <!-- 页头 -->
    <div class="todo-toolbar">
      <div class="section-title todo-title">
        <el-icon class="title-icon"><List /></el-icon>每日待办
      </div>
      <div class="today-hint">{{ todayText }}</div>
    </div>

    <!-- 常驻输入框：输入 + 排期日期 + 添加（Todoist 风格） -->
    <div class="todo-addbar warm-card">
      <el-date-picker
        v-model="newDueDate"
        type="date"
        class="add-date"
        placeholder="今天"
        value-format="YYYY-MM-DD"
        :clearable="false"
      />
      <el-input
        v-model="newTitle"
        class="add-input"
        placeholder="准备做点什么？回车添加"
        maxlength="200"
        clearable
        @keyup.enter="handleAdd"
      />
      <el-button type="primary" round :loading="adding" @click="handleAdd">
        ＋ 添加
      </el-button>
    </div>

    <!-- 主体：加载 / 空态 / 分组列表 -->
    <template v-if="loading">
      <el-skeleton :rows="5" animated class="warm-card skeleton-card" />
    </template>

    <EmptyState v-else-if="!hasAny" description="今天的待办是空的，添加第一条吧" icon="📝" icon-component="EditPen" />

    <template v-else>
      <!-- 分组渲染：Today → Upcoming → Completed → Overdue，全部可折叠 -->
      <section v-for="g in groups" :key="g.key" v-show="g.list.length" class="todo-group">
        <div
          class="group-header"
          :class="g.cls"
          @click="showGroup[g.key] = !showGroup[g.key]"
        >
          <el-icon class="collapse-icon" :class="{ collapsed: !showGroup[g.key] }"><ArrowDown /></el-icon>
          {{ g.label }}
          <span class="group-count">{{ g.list.length }}</span>
        </div>
        <TransitionGroup v-show="showGroup[g.key]" name="todo-list" tag="ul" class="todo-list">
          <li v-for="t in g.list" :key="t.id" class="todo-item warm-card" :class="{ dimmed: t.done }">
            <button class="todo-circle" :class="{ done: t.done }" :disabled="t.done" @click="toggleDone(t)">
              <el-icon v-if="t.done" :size="14"><Check /></el-icon>
            </button>
            <div class="todo-body">
              <div class="todo-title" :class="{ done: t.done, overdue: t.overdue && !t.done }">{{ t.title }}</div>
              <div class="todo-meta">
                <span v-if="t.priority" class="todo-tag flag">重要</span>
                <span class="todo-tag">{{ dueText(t) }}</span>
              </div>
            </div>
            <el-button v-if="!t.done" class="icon-btn star" text :class="{ active: t.priority }" @click="togglePriority(t)">
              <el-icon :size="16"><StarFilled /></el-icon>
            </el-button>
            <el-button v-if="!t.done" class="icon-btn del" text @click="handleDelete(t)">
              <el-icon :size="16"><Delete /></el-icon>
            </el-button>
          </li>
        </TransitionGroup>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import * as todoApi from '@/api/todo'
import EmptyState from '@/components/EmptyState.vue'

const view = ref({ overdue: [], today: [], upcoming: [], completed: [] })
const loading = ref(false)
const adding = ref(false)

/* 添加栏 */
const newTitle = ref('')
const newDueDate = ref(dayjs().format('YYYY-MM-DD'))

const todayText = dayjs().format('YYYY年M月D日 dddd')

/* 各分组折叠状态（默认展开，点组头收起/展开） */
const showGroup = reactive({
  today: true,
  upcoming: true,
  completed: true,
  overdue: true
})

/* 分组配置（顺序即展示顺序）：Today → Upcoming → Completed → Overdue */
const groups = computed(() => [
  { key: 'today', label: 'Today', list: view.value.today, cls: 'today' },
  { key: 'upcoming', label: 'Upcoming', list: view.value.upcoming, cls: 'upcoming' },
  { key: 'completed', label: 'Completed', list: view.value.completed, cls: 'completed' },
  { key: 'overdue', label: 'Overdue', list: view.value.overdue, cls: 'overdue' }
])

const hasAny = computed(() =>
  view.value.overdue.length || view.value.today.length || view.value.upcoming.length || view.value.completed.length
)

onMounted(fetchTodayView)

async function fetchTodayView() {
  loading.value = true
  try {
    view.value = await todoApi.todayView()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/* 新增 */
async function handleAdd() {
  const title = newTitle.value.trim()
  if (!title) {
    ElMessage.warning('写点内容再添加吧')
    return
  }
  adding.value = true
  try {
    await todoApi.create({ title, priority: false, dueDate: newDueDate.value })
    ElMessage.success('已添加到待办 🎉')
    newTitle.value = ''
    await fetchTodayView()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    adding.value = false
  }
}

/* 勾选完成 / 取消完成（提交全量当前值） */
async function toggleDone(t) {
  try {
    await todoApi.update(t.id, { title: t.title, priority: t.priority, dueDate: t.dueDate, done: !t.done })
    await fetchTodayView()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* 重要标记切换 */
async function togglePriority(t) {
  try {
    await todoApi.update(t.id, { title: t.title, priority: !t.priority, dueDate: t.dueDate, done: t.done })
    await fetchTodayView()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* 删除（二次确认） */
async function handleDelete(t) {
  try {
    await ElMessageBox.confirm(`确定删除「${t.title}」吗？`, '删除待办', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await todoApi.remove(t.id)
    ElMessage.success('已删除')
    await fetchTodayView()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/* 日期文案：已完成带年月日；未完成显示 未排期 / 今天 / 明天 / M月D日 */
function dueText(t) {
  if (t.completedAt) return `完成于 ${dayjs(t.completedAt).format('YYYY年M月D日 HH:mm')}`
  if (!t.dueDate) return '未排期'
  if (t.dueDate === dayjs().format('YYYY-MM-DD')) return '今天'
  if (t.dueDate === dayjs().add(1, 'day').format('YYYY-MM-DD')) return '明天'
  return dayjs(t.dueDate).format('M月D日')
}
</script>

<style scoped>
.todo-toolbar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.todo-title {
  margin: 0;
}

.title-icon {
  vertical-align: -2px;
  margin-right: 6px;
  color: var(--el-color-primary);
}

.today-hint {
  font-size: 14px;
  color: #a98d99;
}

/* ===== 添加栏 ===== */
.todo-addbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 20px;
}

.add-date {
  width: 130px;
}

.add-input :deep(.el-input__wrapper) {
  border-radius: 12px;
}

.skeleton-card {
  padding: 24px;
}

/* ===== 分组 ===== */
.todo-group {
  margin-bottom: 8px;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #6b5260;
  padding: 14px 4px 8px;
  cursor: pointer;
  user-select: none;
}

.group-header:hover {
  color: var(--el-color-primary);
}

.group-header.today {
  color: var(--el-color-primary);
}

.group-header.overdue {
  color: #e6435d;
}

.group-count {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(236, 106, 143, 0.12);
  color: var(--el-color-primary);
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.overdue .group-count {
  background: rgba(230, 67, 93, 0.12);
  color: #e6435d;
}

.collapse-icon {
  transition: transform 0.25s;
  color: #a98d99;
}

.collapse-icon.collapsed {
  transform: rotate(-90deg);
}

/* ===== 待办条目 ===== */
.todo-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  transition: box-shadow 0.2s;
}

.todo-item:hover {
  box-shadow: 0 10px 24px rgba(236, 106, 143, 0.18);
}

.todo-item.dimmed {
  opacity: 0.72;
}

/* 圆形勾选按钮 */
.todo-circle {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid #f5acbf;
  background: transparent;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.todo-circle:hover {
  border-color: var(--el-color-primary);
  transform: scale(1.1);
}

.todo-circle.done {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  cursor: default;
}

.todo-circle:disabled {
  cursor: default;
}

.todo-body {
  flex: 1;
  min-width: 0;
}

.todo-title {
  font-size: 15px;
  color: #5c4a52;
  word-break: break-all;
}

.todo-title.done {
  color: #c3adb8;
  text-decoration: line-through;
}

.todo-title.overdue {
  color: #e6435d;
  font-weight: 500;
}

.todo-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.todo-tag {
  font-size: 12px;
  color: #a98d99;
  background: rgba(236, 106, 143, 0.08);
  border-radius: 999px;
  padding: 1px 8px;
}

.todo-tag.flag {
  color: #e6435d;
  background: rgba(230, 67, 93, 0.1);
}

.icon-btn {
  flex-shrink: 0;
  color: #c9b3be;
  transition: color 0.2s, transform 0.2s;
}

.icon-btn:hover {
  color: var(--el-color-primary);
  transform: scale(1.15);
}

.icon-btn.star.active {
  color: #f5b342;
}

.icon-btn.del:hover {
  color: #e6435d;
}

/* TransitionGroup 动画 */
.todo-list-enter-active,
.todo-list-leave-active {
  transition: all 0.25s ease;
}

.todo-list-enter-from,
.todo-list-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>