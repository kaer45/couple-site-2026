<template>
  <div class="page anniversary">
    <!-- ===== 顶部汇总卡片 ===== -->
    <div class="anni-summary warm-card">
      <div class="anni-main">
        <div class="anni-label">💞 在一起已经</div>
        <div class="anni-value">{{ summary.daysTogether ?? '—' }} <span class="anni-unit">天</span></div>
      </div>
      <div class="anni-next">
        <template v-if="summary.nextAnniversary">
          <div class="anni-label">🎉 下一个纪念日</div>
          <div class="anni-next-name">{{ summary.nextAnniversary.name }}</div>
          <div class="anni-sub">还有 {{ summary.nextAnniversary.daysLeft }} 天（{{ summary.nextAnniversary.date }}）</div>
        </template>
        <template v-else>
          <div class="anni-label">🎉 下一个纪念日</div>
          <div class="anni-sub">暂时没有未来的纪念日，添加一个吧</div>
        </template>
      </div>
      <el-button type="primary" round class="anni-add-btn" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增纪念日
      </el-button>
    </div>

    <!-- ===== 即将到来的纪念日（180 天内） ===== -->
    <div v-if="summary.upcoming && summary.upcoming.length" class="anni-upcoming">
      <div class="section-title">即将到来</div>
      <el-tag
        v-for="item in summary.upcoming"
        :key="item.id"
        round
        effect="light"
        type="danger"
        class="upcoming-tag"
      >
        {{ item.name }} · {{ item.daysLeft }} 天后
      </el-tag>
    </div>

    <!-- ===== 纪念日列表 ===== -->
    <div class="section-title">全部纪念日</div>
    <template v-if="loading">
      <el-skeleton :rows="3" animated />
    </template>
    <EmptyState v-else-if="!list.length" description="还没有纪念日，添加第一个吧" icon="🎂">
      <el-button type="primary" round @click="openDialog()">新增纪念日</el-button>
    </EmptyState>
    <div v-else class="anni-list">
      <div v-for="item in list" :key="item.id" class="anni-item warm-card">
        <div class="anni-item-left">
          <div class="anni-item-name">
            {{ item.name }}
            <el-tag v-if="item.isStart" size="small" type="danger" effect="light" round class="start-tag">
              在一起
            </el-tag>
          </div>
          <div class="anni-item-date">📅 {{ item.date }}</div>
        </div>
        <div class="anni-item-days" :class="{ past: daysLeft(item) < 0, today: daysLeft(item) === 0 }">
          {{ daysText(item) }}
        </div>
        <div class="anni-item-actions">
          <!-- is_start 的纪念日不可编辑名称/删除（后端 403），只保留查看 -->
          <el-button v-if="!item.isStart" link type="primary" @click="openDialog(item)">编辑</el-button>
          <el-button v-if="!item.isStart" link type="danger" @click="handleRemove(item)">删除</el-button>
        </div>
      </div>
    </div>

    <!-- ===== 新增 / 编辑弹窗 ===== -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑纪念日' : '新增纪念日'" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="20" show-word-limit placeholder="比如：第一次旅行" />
        </el-form-item>
        <el-form-item label="日期" prop="date">
          <el-date-picker
            v-model="form.date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="提前提醒" prop="remindDays">
          <el-input-number v-model="form.remindDays" :min="0" :max="30" />
          <span class="remind-hint">天前提醒</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" round :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import * as anniversaryApi from '@/api/anniversary'
import EmptyState from '@/components/EmptyState.vue'

/* ========== 汇总 ========== */
const summary = ref({ daysTogether: null, nextAnniversary: null, upcoming: [] })

/* ========== 列表 ========== */
const list = ref([])
const loading = ref(false)

/* ========== 新增 / 编辑弹窗 ========== */
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()
const editingId = ref(null) // null 表示新增
const form = reactive({
  name: '',
  date: '',
  remindDays: 7
})

const rules = {
  name: [{ required: true, message: '请输入纪念日名称', trigger: 'blur' }],
  date: [{ required: true, message: '请选择日期', trigger: 'change' }]
}

onMounted(async () => {
  await Promise.all([fetchSummary(), fetchList()])
})

// 拉取汇总
async function fetchSummary() {
  try {
    summary.value = await anniversaryApi.summary()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

// 拉取列表
async function fetchList() {
  loading.value = true
  try {
    list.value = await anniversaryApi.list()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// 距离今天的天数（负数为已过去）
function daysLeft(item) {
  return dayjs(item.date).startOf('day').diff(dayjs().startOf('day'), 'day')
}

// 剩余天数文案
function daysText(item) {
  const d = daysLeft(item)
  if (d > 0) return `还有 ${d} 天`
  if (d === 0) return '就是今天 🎉'
  return `已过去 ${Math.abs(d)} 天`
}

// 打开弹窗；传 item 为编辑，不传为新增
function openDialog(item) {
  if (item) {
    editingId.value = item.id
    form.name = item.name
    form.date = item.date
    form.remindDays = item.remindDays ?? 7
  } else {
    editingId.value = null
    form.name = ''
    form.date = ''
    form.remindDays = 7
  }
  dialogVisible.value = true
}

// 提交新增 / 编辑
async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = { name: form.name, date: form.date, remindDays: form.remindDays }
    if (editingId.value) {
      await anniversaryApi.update(editingId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await anniversaryApi.create(payload)
      ElMessage.success('添加成功 🎉')
    }
    dialogVisible.value = false
    await Promise.all([fetchList(), fetchSummary()])
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

// 删除纪念日（is_start 的在模板中已隐藏删除按钮）
async function handleRemove(item) {
  try {
    await ElMessageBox.confirm(`确定删除纪念日「${item.name}」吗？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await anniversaryApi.remove(item.id)
    ElMessage.success('删除成功')
    await Promise.all([fetchList(), fetchSummary()])
  } catch (e) {
    /* 拦截器已提示（如 403） */
  }
}
</script>

<style scoped>
/* 汇总卡片：三栏布局 */
.anni-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 26px 32px;
  flex-wrap: wrap;
}

.anni-label {
  font-size: 13px;
  color: #a98d99;
  margin-bottom: 6px;
}

.anni-value {
  font-size: 38px;
  font-weight: 800;
  color: var(--el-color-primary);
}

.anni-unit {
  font-size: 15px;
  font-weight: 600;
}

.anni-next-name {
  font-size: 18px;
  font-weight: 700;
  color: #6b5260;
}

.anni-sub {
  margin-top: 4px;
  font-size: 13px;
  color: #a98d99;
}

.anni-upcoming {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.upcoming-tag {
  border-radius: 999px;
  padding: 0 14px;
}

/* 列表 */
.anni-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 24px;
  margin-bottom: 14px;
}

.anni-item-left {
  flex: 1;
  min-width: 0;
}

.anni-item-name {
  font-size: 16px;
  font-weight: 700;
  color: #6b5260;
}

.start-tag {
  margin-left: 8px;
}

.anni-item-date {
  margin-top: 4px;
  font-size: 13px;
  color: #a98d99;
}

.anni-item-days {
  font-size: 15px;
  font-weight: 700;
  color: var(--el-color-primary);
  white-space: nowrap;
}

.anni-item-days.past {
  color: #a98d99;
}

.anni-item-days.today {
  color: #f5a623;
}

.anni-item-actions {
  display: flex;
  gap: 4px;
  white-space: nowrap;
}

.remind-hint {
  margin-left: 10px;
  font-size: 13px;
  color: #a98d99;
}
</style>
