<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import type { AdminSearchEngine, SearchEnginePayload } from '@/types/searchEngine'
import { ensureHttpProtocol, isSafeHttpUrl } from '@/utils/url'

const props = defineProps<{
  modelValue: boolean
  engine: AdminSearchEngine | null
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [payload: SearchEnginePayload]
}>()

const formRef = ref<FormInstance>()

const emptyForm = (): SearchEnginePayload => ({
  name: '',
  icon: '',
  searchUrl: '',
  placeholder: '',
  sortOrder: 0,
  visible: true,
})

const form = reactive<SearchEnginePayload>(emptyForm())
let formVersion = 0

const rules: FormRules<SearchEnginePayload> = {
  name: [
    { required: true, message: '请输入搜索引擎名称', trigger: 'blur' },
    { max: 50, message: '名称不能超过 50 个字符', trigger: 'blur' },
  ],
  searchUrl: [
    { required: true, message: '请输入搜索地址模板', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!isSafeHttpUrl(value)) {
          return callback(new Error('请输入有效的 HTTP(S) 搜索地址'))
        }
        const placeholders = [...value.matchAll(/\{([^{}]+)}/g)]
        if (placeholders.some((match) => match[1] !== 'keyword')) {
          return callback(new Error('搜索地址模板只支持 {keyword} 占位符'))
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  icon: [
    { max: 255, message: '图标内容不能超过 255 个字符', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const icon = value?.trim()
        if (!icon || icon.length <= 3 || isSafeHttpUrl(icon)) return callback()
        callback(new Error('请输入 1～3 个字符，或有效的 HTTP(S) 图片地址'))
      },
      trigger: 'blur',
    },
  ],
  placeholder: [
    { max: 100, message: '提示文字不能超过 100 个字符', trigger: 'blur' },
  ],
}

watch(
  () => [props.modelValue, props.engine] as const,
  ([visible]) => {
    formVersion += 1
    if (!visible) return
    Object.assign(
      form,
      props.engine
        ? {
            name: props.engine.name,
            icon: props.engine.icon ?? '',
            searchUrl: props.engine.searchUrl,
            placeholder: props.engine.placeholder ?? '',
            sortOrder: props.engine.sortOrder,
            visible: props.engine.visible,
          }
        : emptyForm(),
    )
    void nextTick(() => formRef.value?.clearValidate())
  },
  { flush: 'sync' },
)

async function submit() {
  if (props.submitting || !props.modelValue) return
  const version = formVersion
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (version !== formVersion || props.submitting || !props.modelValue) return
  const icon = form.icon.trim()
  emit('submit', {
    ...form,
    name: form.name.trim(),
    icon: icon.length > 3 ? ensureHttpProtocol(icon) : icon,
    searchUrl: ensureHttpProtocol(form.searchUrl),
    placeholder: form.placeholder.trim(),
  })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="engine ? '编辑搜索引擎' : '新增搜索引擎'"
    width="min(620px, calc(100vw - 32px))"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <div class="admin-form-grid admin-form-grid--2">
        <el-form-item label="搜索引擎名称" prop="name">
          <el-input v-model="form.name" maxlength="50" placeholder="例如：Google" />
        </el-form-item>
        <el-form-item label="图标文字 / URL" prop="icon">
          <el-input v-model="form.icon" maxlength="255" placeholder="例如：G，或 HTTPS 图片地址" />
        </el-form-item>
      </div>

      <el-form-item label="搜索地址模板" prop="searchUrl">
        <el-input
          v-model="form.searchUrl"
          maxlength="500"
          placeholder="https://www.google.com/search?q={keyword}"
        />
        <p class="admin-form-tip">
          使用 <code>{keyword}</code> 标记关键词位置；未填写标记时会自动追加 <code>q</code> 参数。
        </p>
      </el-form-item>

      <el-form-item label="搜索框提示文字" prop="placeholder">
        <el-input
          v-model="form.placeholder"
          maxlength="100"
          show-word-limit
          placeholder="例如：使用 Google 搜索"
        />
      </el-form-item>

      <div class="admin-form-grid admin-form-grid--2">
        <el-form-item label="排序值">
          <el-input-number
            v-model="form.sortOrder"
            :min="0"
            :max="9999"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="启用状态">
          <div class="search-engine-dialog__switch">
            <el-switch
              v-model="form.visible"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
            />
            <span>停用后不会在公开首页出现</span>
          </div>
        </el-form-item>
      </div>

      <el-alert
        v-if="engine?.isDefault"
        title="停用当前默认引擎后，系统会自动选择下一个可用引擎。"
        type="info"
        :closable="false"
        show-icon
      />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        保存搜索引擎
      </el-button>
    </template>
  </el-dialog>
</template>
