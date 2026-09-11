<template>
  <div class="auth-page">
    <div class="auth-card warm-card">
      <div class="auth-logo">💌</div>
      <h1 class="auth-title">创建你的小窝账号</h1>
      <p class="auth-sub">注册后会生成专属情侣码，把码发给 Ta 就能绑定啦</p>

      <el-form ref="formRef" :model="form" :rules="rules" size="large">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名（3-20 位字母数字下划线）" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码（6-32 位）" :prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" :prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称（1-20 位）" :prefix-icon="Postcard" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="auth-btn" :loading="loading" @click="handleRegister">注 册</el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        已有账号？
        <router-link class="auth-link" to="/login">去登录</router-link>
      </div>
    </div>

    <!-- 注册成功弹窗：醒目展示情侣码 + 复制按钮（仅展示一次，关闭后无法找回） -->
    <el-dialog v-model="dialogVisible" title="🎉 注册成功" width="460px" :show-close="false"
               :close-on-click-modal="false" align-center>
      <p class="code-tip">这是你的专属情侣码，快发给 Ta，让 Ta 绑定你吧～</p>
      <div class="code-warning">
        <el-icon class="code-warning-icon"><WarningFilled /></el-icon>
        情侣码<b>仅展示这一次</b>：请立即复制并妥善保存，关闭本弹窗后将<b>无法再次查看</b>，
        绑定必须凭此码，若丢失只能重新注册新账号。
      </div>
      <div class="couple-code" title="点击复制" @click="copyCode">
        {{ coupleCode }}
        <el-icon class="copy-icon"><CopyDocument /></el-icon>
      </div>
      <el-button class="code-copy-btn" round type="primary" @click="copyCode">复制情侣码并保存</el-button>
      <template #footer>
        <el-button round @click="router.push('/bind')">我已保存，去绑定</el-button>
        <el-button type="primary" round @click="router.push('/')">先进去看看</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Postcard } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const dialogVisible = ref(false)
const coupleCode = ref('')

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: ''
})

// 校验规则与契约一致：username 3-20 位字母数字下划线；password 6-32 位；nickname 1-20 位
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,20}$/, message: '3-20 位字母、数字或下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 1, max: 20, message: '昵称长度为 1-20 位', trigger: 'blur' }
  ]
}

// 注册
async function handleRegister() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    // 确认密码仅前端校验，不提交给后端
    const { confirmPassword, ...payload } = form
    const data = await userStore.register(payload)
    // 注册成功响应含 coupleCode，醒目展示
    coupleCode.value = data.coupleCode
    dialogVisible.value = true
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

// 复制情侣码（navigator.clipboard + 非安全上下文兜底）
async function copyCode() {
  const text = coupleCode.value
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('情侣码已复制，快发给 Ta 吧 💕')
  } catch (e) {
    // 兜底方案：临时 textarea + execCommand
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('情侣码已复制，快发给 Ta 吧 💕')
  }
}
</script>

<style scoped>
.code-tip {
  font-size: 14px;
  color: #a98d99;
  text-align: center;
  margin-bottom: 16px;
}

/* 仅展示一次的醒目警告 */
.code-warning {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0 0 16px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.7;
  text-align: left;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fcd34d;
  border-radius: 10px;
}

.code-warning-icon {
  flex-shrink: 0;
  margin-top: 3px;
  color: #b45309;
}

/* 情侣码大字展示 */
.couple-code {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: 0 auto 16px;
  padding: 18px 24px;
  max-width: 260px;
  font-size: 34px;
  font-weight: 800;
  letter-spacing: 6px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 2px dashed var(--el-color-primary-light-5);
  border-radius: 16px;
  cursor: pointer;
  user-select: all;
  transition: transform 0.2s;
}

.couple-code:hover {
  transform: scale(1.03);
}

.copy-icon {
  font-size: 20px;
}

.code-copy-btn {
  display: block;
  margin: 0 auto;
}
</style>
