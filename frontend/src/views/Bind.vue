<template>
  <div class="auth-page">
    <div class="auth-card warm-card">
      <div class="auth-logo">💞</div>
      <h1 class="auth-title">绑定你的另一半</h1>
      <p class="auth-sub">输入 Ta 注册时获得的情侣码，开启你们的专属小窝</p>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top">
        <el-form-item prop="coupleCode">
          <el-input
            v-model="form.coupleCode"
            placeholder="情侣码（如 AB12CD）"
            maxlength="8"
            clearable
            @input="onCodeInput"
          />
        </el-form-item>
        <el-form-item prop="startDate" label="在一起的日子">
          <el-date-picker
            v-model="form.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择在一起的那天"
            style="width: 100%"
            :disabled-date="disableFutureDate"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="auth-btn" :loading="loading" @click="handleBind">绑 定</el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        还没有情侣码？
        <router-link class="auth-link" to="/register">先注册一个</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  coupleCode: '',
  startDate: dayjs().format('YYYY-MM-DD') // 默认今天
})

const rules = {
  coupleCode: [{ required: true, message: '请输入情侣码', trigger: 'blur' }]
}

// 情侣码统一转为大写
function onCodeInput(value) {
  form.coupleCode = (value || '').toUpperCase()
}

// 不允许选择未来的日期
function disableFutureDate(date) {
  return date && date.getTime() > Date.now()
}

// 绑定情侣
async function handleBind() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.bind({ coupleCode: form.coupleCode, startDate: form.startDate })
    ElMessage.success('绑定成功，祝你们长长久久 💕')
    router.push('/')
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>
