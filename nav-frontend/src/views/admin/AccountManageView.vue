<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Key, Lock, User, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import PageHeading from '@/components/admin/PageHeading.vue'
import { useAuthStore } from '@/stores/auth.store'
import type { ChangePasswordPayload } from '@/types/auth'
import {
  evaluatePasswordPolicy,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
} from '@/utils/passwordPolicy'

const authStore = useAuthStore()
const router = useRouter()
const formRef = ref<FormInstance>()
const changingPassword = ref(false)
const loggingOutAll = ref(false)
const form = reactive<ChangePasswordPayload>({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const username = computed(() => authStore.user?.username ?? '')
const nickname = computed(() => authStore.user?.nickname || '未设置')
const role = computed(() => {
  const value = authStore.user?.role || ''
  return /admin/i.test(value) ? '管理员' : value || '未知角色'
})
const passwordPolicy = computed(() => evaluatePasswordPolicy(
  form.newPassword,
  username.value,
  form.currentPassword,
))
const strengthLabel = computed(() => ({
  empty: '等待输入',
  weak: '未满足要求',
  medium: '符合要求',
  strong: '强密码',
}[passwordPolicy.value.strength]))
const strengthWidth = computed(() => ({
  empty: '0%',
  weak: '28%',
  medium: '68%',
  strong: '100%',
}[passwordPolicy.value.strength]))

const rules: FormRules<ChangePasswordPayload> = {
  currentPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const result = evaluatePasswordPolicy(value ?? '', username.value, form.currentPassword)
        if (!result.lengthValid) {
          return callback(new Error(`密码至少 ${PASSWORD_MIN_LENGTH} 个字符，且不能超过 ${PASSWORD_MAX_LENGTH} 个 UTF-8 字节`))
        }
        if (!result.whitespaceFree) return callback(new Error('密码不能包含空格或其他空白字符'))
        if (!result.categoriesValid) return callback(new Error('请至少使用大写字母、小写字母、数字、符号中的三类'))
        if (!result.usernameFree) return callback(new Error('密码不能包含管理员用户名'))
        if (!result.differsFromCurrent) return callback(new Error('新密码不能与当前密码相同'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) return callback(new Error('两次输入的新密码不一致'))
        callback()
      },
      trigger: ['blur', 'change'],
    },
  ],
}

async function submitPasswordChange() {
  if (!username.value) {
    await authStore.fetchProfile()
    if (!username.value) {
      ElMessage.error('未能读取当前管理员资料，请重新登录后再试')
      return
    }
  }
  if (!(await formRef.value?.validate().catch(() => false))) return
  changingPassword.value = true
  try {
    await authStore.changePassword({ ...form })
    await router.replace('/admin/login')
    ElMessage.success('密码修改成功，请使用新密码重新登录')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '密码修改失败，请稍后重试')
  } finally {
    changingPassword.value = false
  }
}

async function logoutAllSessions() {
  try {
    await ElMessageBox.confirm(
      '确认后，当前设备及其他设备上的管理会话都会失效，需要重新登录。',
      '退出全部会话',
      {
        type: 'warning',
        confirmButtonText: '确认全部退出',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  loggingOutAll.value = true
  try {
    await authStore.logoutAll()
    await router.replace('/admin/login')
    ElMessage.success('已退出所有设备上的管理会话')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '退出全部会话失败，请稍后重试')
  } finally {
    loggingOutAll.value = false
  }
}

onMounted(() => {
  if (!authStore.user) void authStore.fetchProfile()
})
</script>

<template>
  <div class="admin-page account-page">
    <PageHeading
      title="账号安全"
      description="查看当前管理员身份，并定期更新密码和管理登录会话。"
      eyebrow="ACCOUNT SECURITY"
    />

    <div class="account-security-grid">
      <section class="admin-panel account-profile" aria-labelledby="account-profile-title">
        <header class="account-section-heading">
          <span><User /></span>
          <div>
            <h2 id="account-profile-title">当前账号</h2>
            <p>信息来自当前登录会话的管理员资料</p>
          </div>
        </header>
        <dl class="account-profile__details">
          <div><dt>用户名</dt><dd>{{ username || '加载中…' }}</dd></div>
          <div><dt>昵称</dt><dd>{{ nickname }}</dd></div>
          <div><dt>角色</dt><dd><el-tag type="primary" effect="light">{{ role }}</el-tag></dd></div>
        </dl>
        <p class="account-profile__notice"><Lock />账号资料只用于后台身份识别，不会展示在公开首页。</p>
      </section>

      <section class="admin-panel account-password" aria-labelledby="account-password-title">
        <header class="account-section-heading">
          <span><Key /></span>
          <div>
            <h2 id="account-password-title">修改登录密码</h2>
            <p>修改成功后，所有设备都需要使用新密码重新登录</p>
          </div>
        </header>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="account-password__form"
          @keyup.enter="submitPasswordChange"
        >
          <el-form-item label="当前密码" prop="currentPassword">
            <el-input
              v-model="form.currentPassword"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="请输入当前密码"
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="form.newPassword"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="请输入新的登录密码"
            />
          </el-form-item>

          <div class="password-strength" :class="`is-${passwordPolicy.strength}`" aria-live="polite">
            <div class="password-strength__summary">
              <span>密码强度</span>
              <strong>{{ strengthLabel }}</strong>
            </div>
            <div class="password-strength__track" aria-hidden="true"><i :style="{ width: strengthWidth }" /></div>
            <ul>
              <li :class="{ 'is-valid': passwordPolicy.lengthValid }">至少 {{ PASSWORD_MIN_LENGTH }} 个字符，且不超过 {{ PASSWORD_MAX_LENGTH }} 个 UTF-8 字节</li>
              <li :class="{ 'is-valid': passwordPolicy.whitespaceFree && form.newPassword.length > 0 }">不包含空格或其他空白字符</li>
              <li :class="{ 'is-valid': passwordPolicy.categoriesValid }">大写、小写、数字、符号至少三类（当前 {{ passwordPolicy.categoryCount }}/4）</li>
              <li :class="{ 'is-valid': passwordPolicy.usernameFree && form.newPassword.length > 0 }">不包含用户名</li>
              <li :class="{ 'is-valid': passwordPolicy.differsFromCurrent && form.currentPassword.length > 0 && form.newPassword.length > 0 }">与当前密码不同</li>
            </ul>
          </div>

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="请再次输入新密码"
            />
          </el-form-item>
          <el-button type="primary" :loading="changingPassword" :disabled="!username" @click="submitPasswordChange">
            <Key /> 修改密码
          </el-button>
        </el-form>
      </section>

      <section class="admin-panel account-danger" aria-labelledby="account-session-title">
        <div class="account-danger__copy">
          <span><WarningFilled /></span>
          <div>
            <h2 id="account-session-title">退出全部会话</h2>
            <p>如果怀疑账号在其他设备登录，可以让所有管理令牌立即失效。</p>
          </div>
        </div>
        <el-button type="danger" plain :loading="loggingOutAll" @click="logoutAllSessions">退出所有设备</el-button>
      </section>
    </div>
  </div>
</template>
