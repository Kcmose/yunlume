<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, Right, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth.store'
import type { LoginPayload } from '@/types/auth'

const formRef = ref<FormInstance>()
const form = reactive<LoginPayload>({ username: 'admin', password: '' })
const rules: FormRules<LoginPayload> = {
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  try {
    const result = await authStore.login(form)
    if (!result) return
    ElMessage.success('登录成功，欢迎回来')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/admin'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败，请检查账号或密码')
  }
}
</script>

<template>
  <div class="admin-login">
    <section class="admin-login__story" aria-label="yunlume 品牌介绍">
      <RouterLink class="admin-login__brand" to="/">
        <span>i</span>
        <strong>yunlume</strong>
      </RouterLink>
      <div class="admin-login__story-content">
        <p>YOUR LINKS, YOUR UNIVERSE.</p>
        <h1>把散落的灵感，<br />整理成随时可达的宇宙。</h1>
        <span>一个安静、快速且真正属于你的网络起点。</span>
      </div>
      <div class="admin-login__orbit" aria-hidden="true">
        <i /><i /><i />
      </div>
      <small>yunlume NAVIGATION SYSTEM · 2026</small>
    </section>
    <main class="admin-login__panel">
      <div class="admin-login__form-wrap">
        <div class="admin-login__mobile-brand">yunlume</div>
        <p class="admin-login__eyebrow">ADMIN CONSOLE</p>
        <h2>欢迎回来</h2>
        <p class="admin-login__intro">登录后即可管理站点外观、分类与书签内容。</p>
        <el-alert
          v-if="route.query.installed === '1'"
          class="admin-login__install-reminder"
          type="warning"
          :closable="false"
          title="首次安装已完成"
          description="安装入口已由完成标记锁定，请将 database_config 卷纳入备份。"
          show-icon
        />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
          <el-form-item label="管理员账号" prop="username">
            <el-input v-model="form.username" size="large" autocomplete="username" placeholder="请输入账号">
              <template #prefix><User /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="登录密码" prop="password">
            <el-input v-model="form.password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入密码">
              <template #prefix><Lock /></template>
            </el-input>
          </el-form-item>
          <el-button class="admin-login__submit" type="primary" size="large" :loading="authStore.loading" @click="submit">
            进入管理中心 <Right />
          </el-button>
        </el-form>
        <p class="admin-login__hint">使用部署时创建的管理员账号登录 · 登录后可在账号安全中修改密码</p>
        <RouterLink class="admin-login__back" to="/">← 返回公开首页</RouterLink>
      </div>
    </main>
  </div>
</template>
