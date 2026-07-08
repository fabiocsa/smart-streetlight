<template>
  <div class="login-wrapper">
    <el-card class="login-card">
      <template #header>
        <div class="card-header">
          <el-icon :size="28" color="#409EFF"><Monitor /></el-icon>
          <span class="title">智慧路灯管理系统</span>
        </div>
      </template>

      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-width="0">
            <el-form-item prop="username">
              <el-input v-model="loginForm.username" placeholder="用户名" prefix-icon="User" />
            </el-form-item>
            <el-form-item prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="密码"
                prefix-icon="Lock" show-password @keyup.enter="handleLogin" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" style="width: 100%"
                @click="handleLogin">登 录</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-width="0">
            <el-form-item prop="username">
              <el-input v-model="registerForm.username" placeholder="用户名" prefix-icon="User" />
            </el-form-item>
            <el-form-item prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="密码"
                prefix-icon="Lock" show-password />
            </el-form-item>
            <el-form-item prop="confirmPassword">
              <el-input v-model="registerForm.confirmPassword" type="password" placeholder="确认密码"
                prefix-icon="Lock" show-password />
            </el-form-item>
            <el-form-item prop="role">
              <el-select v-model="registerForm.role" placeholder="选择角色" style="width: 100%">
                <el-option label="市政人员（日常监控）" value="municipal" />
                <el-option label="管理员（全部权限）" value="admin" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="loading" style="width: 100%"
                @click="handleRegister">注 册</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="role-hint">
        <el-text size="small" type="info">
          市政人员：监控 + 控制 | 管理员：全部权限（含告警/设备管理）
        </el-text>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/authStore'

const router = useRouter()
const authStore = useAuthStore()

const activeTab = ref('login')
const loading = ref(false)

// --- 登录表单 ---
const loginForm = ref({ username: '', password: '' })
const loginFormRef = ref(null)
const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.login(loginForm.value)
    ElMessage.success(`欢迎，${authStore.username}`)
    router.push('/dashboard')
  } catch {
    // 错误已拦截
  } finally {
    loading.value = false
  }
}

// --- 注册表单 ---
const registerForm = ref({ username: '', password: '', confirmPassword: '', role: 'municipal' })
const registerFormRef = ref(null)
const validateConfirm = (rule, value, callback) => {
  if (value !== registerForm.value.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}
const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名 2-20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, max: 50, message: '密码 4-50 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

async function handleRegister() {
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.register({
      username: registerForm.value.username,
      password: registerForm.value.password,
      role: registerForm.value.role
    })
    ElMessage.success('注册成功')
    router.push('/dashboard')
  } catch {
    // 错误已拦截
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrapper {
  display: flex; justify-content: center; align-items: center;
  min-height: 100vh; background: #f0f2f5;
}
.login-card {
  width: 400px;
}
.card-header {
  display: flex; align-items: center; gap: 10px;
}
.card-header .title {
  font-size: 18px; font-weight: 600;
}
.role-hint {
  text-align: center; margin-top: 12px;
}
</style>
