<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useAuthStore } from '@/stores/auth.store'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Brush,
  Check,
  InfoFilled,
  Monitor,
  Picture,
  RefreshRight,
  WarningFilled,
} from '@element-plus/icons-vue'
import BackgroundImageField from '@/components/admin/BackgroundImageField.vue'
import PageHeading from '@/components/admin/PageHeading.vue'
import { getAdminSiteConfig, updateSiteConfig } from '@/api/site.api'
import { fallbackSiteConfig } from '@/data/fallback'
import type { SiteConfig } from '@/types/site'
import {
  getBackgroundConfigSnapshot,
  hasBackgroundConfigChanged,
} from '@/utils/backgroundConfig'
import { getHttpStatus } from '@/utils/httpError'
import {
  createSiteConfigUpdatePayload,
  getSiteConfigSnapshot,
  getSiteConfigValidationError,
  hasSiteConfigChanged,
  type SiteConfigSnapshot,
} from '@/utils/siteConfigState'

const formRef = ref<FormInstance>()
const authStore = useAuthStore()
const form = reactive<SiteConfig>({ ...fallbackSiteConfig })
const loading = ref(true)
const saving = ref(false)
const configLoaded = ref(false)
const loadError = ref('')
const backgroundUploads = ref(0)
const savedBackground = ref(getBackgroundConfigSnapshot(fallbackSiteConfig))
const savedConfig = ref<SiteConfigSnapshot | null>(null)
const formDirty = computed(() => hasSiteConfigChanged(form, savedConfig.value))
const hasUnsavedWork = computed(() => formDirty.value || backgroundUploads.value > 0)
const backgroundDirty = computed(() => (
  savedConfig.value !== null
  && hasBackgroundConfigChanged(form, savedBackground.value)
))
const imageValidationError = computed(() => (
  form.backgroundType === 'image' && !form.backgroundImage.trim()
    ? '图片背景模式必须设置 PC 端背景图；移动端图片可以留空并自动使用 PC 图片。'
    : ''
))
const rules: FormRules<SiteConfig> = {
  siteName: [
    { required: true, whitespace: true, message: '请输入站点名称', trigger: 'blur' },
    { max: 50, message: '站点名称不能超过 50 个字符', trigger: ['blur', 'change'] },
  ],
  siteDescription: [
    { max: 255, message: '站点简介不能超过 255 个字符', trigger: ['blur', 'change'] },
  ],
}

async function load() {
  loading.value = true
  configLoaded.value = false
  loadError.value = ''
  try {
    const remoteConfig = await getAdminSiteConfig()
    if (!Number.isInteger(remoteConfig.version) || remoteConfig.version < 0) {
      throw new Error('服务端没有返回配置版本，请确认后端已完成升级')
    }
    Object.assign(form, fallbackSiteConfig, remoteConfig, {
      backgroundImage: remoteConfig.backgroundImage ?? '',
      mobileBackgroundImage: remoteConfig.mobileBackgroundImage ?? '',
    })
    savedBackground.value = getBackgroundConfigSnapshot(form)
    savedConfig.value = getSiteConfigSnapshot(form)
    configLoaded.value = true
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '站点配置加载失败'
    ElMessage.error(`${loadError.value}，当前表单已锁定`)
  } finally {
    loading.value = false
  }
}

function applyColorPreset(backgroundColor: string, fontColor: string) {
  form.backgroundType = 'color'
  form.backgroundColor = backgroundColor
  form.fontColor = fontColor
}

function handleColorChange(field: 'backgroundColor' | 'fontColor', value: string | null) {
  const fallback = field === 'backgroundColor' ? '#000000' : '#ffffff'
  form[field] = /^#[0-9a-fA-F]{6}$/.test(value ?? '') ? value! : fallback
}

function handleBackgroundUploadState(uploading: boolean) {
  backgroundUploads.value = Math.max(0, backgroundUploads.value + (uploading ? 1 : -1))
}

async function save(successMessage = '站点配置已保存') {
  if (!configLoaded.value) {
    ElMessage.warning('请先重新加载站点配置，加载成功后才能保存')
    return false
  }
  if (backgroundUploads.value > 0) {
    ElMessage.warning('背景图片仍在上传，请等待上传完成')
    return false
  }
  if (!(await formRef.value?.validate().catch(() => false))) {
    ElMessage.warning('请先完成必填项，再保存设置')
    return false
  }
  const validationError = getSiteConfigValidationError(form)
  if (validationError) {
    ElMessage.warning(validationError)
    return false
  }
  saving.value = true
  try {
    const persistedConfig = await updateSiteConfig(createSiteConfigUpdatePayload(form))
    Object.assign(form, persistedConfig, {
      backgroundImage: persistedConfig.backgroundImage ?? '',
      mobileBackgroundImage: persistedConfig.mobileBackgroundImage ?? '',
    })
    savedBackground.value = getBackgroundConfigSnapshot(form)
    savedConfig.value = getSiteConfigSnapshot(form)
    ElMessage.success(successMessage)
    return true
  } catch (error) {
    if (getHttpStatus(error) === 409) {
      configLoaded.value = false
      loadError.value = '站点配置已被其他页面或会话修改。当前表单已锁定，请重新加载最新配置后再编辑。'
      try {
        await ElMessageBox.confirm(
          '检测到其他页面已更新站点配置。重新加载将使用服务端最新内容，并放弃本页尚未保存的修改。',
          '配置版本冲突',
          {
            type: 'warning',
            confirmButtonText: '重新加载最新配置',
            cancelButtonText: '暂不重新加载',
          },
        )
        await load()
      } catch {
        // Keep the stale form locked until the administrator explicitly reloads it.
      }
      return false
    }
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function reloadFromServer() {
  if (formDirty.value) {
    try {
      await ElMessageBox.confirm(
        '重新加载会使用服务端最新配置，并放弃本页尚未保存的修改。',
        '确认重新加载？',
        {
          type: 'warning',
          confirmButtonText: '放弃修改并重新加载',
          cancelButtonText: '继续编辑',
        },
      )
    } catch {
      return
    }
  }
  await load()
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!authStore.token || !hasUnsavedWork.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  // An expired session must always be allowed to leave for the login page.
  if (!authStore.token || !hasUnsavedWork.value) return true
  try {
    await ElMessageBox.confirm(
      '当前站点配置还有未保存的修改，离开后这些修改会丢失。',
      '确认离开？',
      {
        type: 'warning',
        confirmButtonText: '放弃修改并离开',
        cancelButtonText: '继续编辑',
      },
    )
    return true
  } catch {
    return false
  }
})

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  void load()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<template>
  <div class="admin-page">
    <PageHeading title="站点配置" description="调整公开首页的品牌信息、主题背景和功能开关。" eyebrow="SITE SETTINGS">
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!configLoaded || backgroundUploads > 0"
        @click="save()"
      ><Check /> 保存更改</el-button>
    </PageHeading>

    <section v-if="loadError" class="admin-panel site-config-load-error" role="alert">
      <WarningFilled aria-hidden="true" />
      <div>
        <strong>未能安全读取站点配置</strong>
        <p>{{ loadError }}</p>
        <small>为避免默认值或旧版本覆盖线上配置，加载成功前已禁止编辑和保存。</small>
      </div>
      <el-button type="primary" plain :loading="loading" @click="reloadFromServer">
        <RefreshRight /> 重新加载
      </el-button>
    </section>

    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      :disabled="!configLoaded || saving"
      label-position="top"
      class="site-config-form"
    >
      <section class="admin-panel settings-section">
        <header class="settings-section__header">
          <span><InfoFilled /></span>
          <div><h2>基础信息</h2><p>访客第一眼看到的站点身份与介绍</p></div>
        </header>
        <div class="settings-section__body">
          <el-form-item label="站点名称" prop="siteName"><el-input v-model="form.siteName" maxlength="50" show-word-limit /></el-form-item>
          <el-form-item label="站点简介（可选）" prop="siteDescription"><el-input v-model="form.siteDescription" maxlength="255" show-word-limit /></el-form-item>
          <el-form-item label="顶部公告"><el-input v-model="form.messageText" maxlength="100" show-word-limit /></el-form-item>
        </div>
      </section>

      <section class="admin-panel settings-section">
        <header class="settings-section__header">
          <span><Brush /></span>
          <div><h2>视觉主题</h2><p>选择背景形式与前景文字颜色</p></div>
        </header>
        <div class="settings-section__body">
          <el-form-item label="背景类型">
            <el-radio-group v-model="form.backgroundType" class="background-type-selector">
              <el-radio-button value="color">
                <el-icon><Monitor /></el-icon>
                <span>纯色背景</span>
              </el-radio-button>
              <el-radio-button value="image">
                <el-icon><Picture /></el-icon>
                <span>图片背景</span>
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <div class="theme-preset-list" aria-label="纯色快捷预设">
            <button
              type="button"
              :disabled="!configLoaded || saving"
              :class="{ 'is-active': form.backgroundType === 'color' && (form.backgroundColor || '').toLowerCase() === '#000000' }"
              :aria-pressed="form.backgroundType === 'color' && (form.backgroundColor || '').toLowerCase() === '#000000'"
              @click="applyColorPreset('#000000', '#ffffff')"
            >
              <span class="theme-preset-list__sample theme-preset-list__sample--black">Aa</span>
              <span><strong>纯黑</strong><small>#000000 / 白字</small></span>
            </button>
            <button
              type="button"
              :disabled="!configLoaded || saving"
              :class="{ 'is-active': form.backgroundType === 'color' && (form.backgroundColor || '').toLowerCase() === '#ffffff' }"
              :aria-pressed="form.backgroundType === 'color' && (form.backgroundColor || '').toLowerCase() === '#ffffff'"
              @click="applyColorPreset('#ffffff', '#111111')"
            >
              <span class="theme-preset-list__sample theme-preset-list__sample--white">Aa</span>
              <span><strong>纯白</strong><small>#FFFFFF / 黑字</small></span>
            </button>
          </div>
          <div class="admin-form-grid admin-form-grid--2">
            <el-form-item :label="form.backgroundType === 'image' ? '图片加载前的备用背景色' : '自定义背景颜色'"><el-color-picker v-model="form.backgroundColor" color-format="hex" @change="handleColorChange('backgroundColor', $event)" /><span class="color-value">{{ form.backgroundColor }}</span></el-form-item>
            <el-form-item label="字体颜色"><el-color-picker v-model="form.fontColor" color-format="hex" @change="handleColorChange('fontColor', $event)" /><span class="color-value">{{ form.fontColor }}</span></el-form-item>
          </div>
          <div v-if="form.backgroundType === 'image'" class="background-image-grid">
            <BackgroundImageField
              v-model="form.backgroundImage"
              label="PC 端背景图"
              hint="用于桌面和大屏设备"
              recommended-size="1920 × 1080"
              preview-mode="desktop"
              :disabled="!configLoaded || saving"
              @uploading-change="handleBackgroundUploadState"
            />
            <BackgroundImageField
              v-model="form.mobileBackgroundImage"
              label="移动端背景图"
              hint="手机端优先使用；留空则自动使用 PC 图片"
              recommended-size="750 × 1334"
              preview-mode="mobile"
              :disabled="!configLoaded || saving"
              @uploading-change="handleBackgroundUploadState"
            />
          </div>
          <el-alert
            v-if="imageValidationError"
            class="site-config-image-error"
            type="error"
            show-icon
            :closable="false"
            :title="imageValidationError"
          />
          <div
            class="background-apply-bar"
            :class="{ 'is-dirty': backgroundDirty }"
            aria-live="polite"
          >
            <span>
              <strong>
                {{ !configLoaded
                  ? '等待读取服务端配置'
                  : backgroundUploads > 0
                    ? '背景图片正在上传'
                    : backgroundDirty
                      ? '背景设置尚未应用'
                      : '背景设置已保存' }}
              </strong>
              <small>
                {{ !configLoaded
                  ? '加载成功前不会应用或保存任何背景设置。'
                  : backgroundUploads > 0
                  ? '上传完成后即可保存并应用。'
                  : backgroundDirty
                    ? '当前只在后台预览，保存后公开首页才会更新。'
                    : '公开首页正在使用这组背景设置。' }}
              </small>
            </span>
            <el-button
              type="primary"
              :loading="saving"
              :disabled="!configLoaded || backgroundUploads > 0 || !backgroundDirty || Boolean(imageValidationError)"
              @click="save('背景设置已保存并应用到首页')"
            >
              <Check /> 保存并应用背景
            </el-button>
          </div>
        </div>
      </section>

      <section class="admin-panel settings-section">
        <header class="settings-section__header">
          <span><Monitor /></span>
          <div><h2>页面功能</h2><p>按需开启公开首页的辅助功能</p></div>
        </header>
        <div class="settings-switch-list">
          <div><span><strong>顶部公告</strong><small>在页面顶部展示公告文字</small></span><el-switch v-model="form.topContentEnabled" /></div>
        </div>
      </section>
    </el-form>
  </div>
</template>
