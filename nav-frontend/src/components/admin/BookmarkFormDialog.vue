<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import type { Bookmark, BookmarkPayload } from '@/types/bookmark'
import type { Category } from '@/types/category'
import { isValidNavigationIcon } from '@/utils/adminNavigationManage'
import { ensureHttpProtocol, isSafeHttpUrl } from '@/utils/url'

const props = defineProps<{
  modelValue: boolean
  bookmark: Bookmark | null
  categories: Category[]
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [payload: BookmarkPayload]
}>()

const formRef = ref<FormInstance>()
const emptyForm = (): BookmarkPayload => ({
  categoryId: props.categories[0]?.id ?? '',
  name: '',
  url: '',
  icon: '',
  description: '',
  sortOrder: 0,
  isRecommend: false,
  isExternal: true,
  visible: true,
})
const form = reactive<BookmarkPayload>(emptyForm())
const rules: FormRules<BookmarkPayload> = {
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  name: [{ required: true, message: '请输入书签名称', trigger: 'blur' }],
  url: [
    { required: true, message: '请输入书签地址', trigger: 'blur' },
    { validator: (_rule, value, callback) => isSafeHttpUrl(value) ? callback() : callback(new Error('请输入有效的 HTTP(S) 地址')), trigger: 'blur' },
  ],
  icon: [{
    validator: (_rule, value, callback) => isValidNavigationIcon(String(value ?? ''))
      ? callback()
      : callback(new Error('请输入 1–3 字短标记或完整的 HTTP(S) 图片 URL')),
    trigger: 'blur',
  }],
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, props.bookmark
      ? {
          categoryId: props.bookmark.categoryId,
          name: props.bookmark.name,
          url: props.bookmark.url,
          icon: props.bookmark.icon,
          description: props.bookmark.description,
          sortOrder: props.bookmark.sortOrder,
          isRecommend: props.bookmark.isRecommend,
          isExternal: props.bookmark.isExternal,
          visible: props.bookmark.visible,
        }
      : emptyForm())
    void nextTick(() => formRef.value?.clearValidate())
  },
)

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  emit('submit', {
    ...form,
    url: ensureHttpProtocol(form.url),
    isRecommend: props.bookmark?.isRecommend ?? false,
  })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="bookmark ? '编辑书签' : '新增书签'"
    width="min(620px, calc(100vw - 32px))"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <div class="admin-form-grid admin-form-grid--2">
        <el-form-item label="所属分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="书签名称" prop="name">
          <el-input v-model="form.name" maxlength="40" placeholder="例如：GitHub" />
        </el-form-item>
      </div>
      <el-form-item label="书签地址" prop="url">
        <el-input v-model="form.url" placeholder="https://example.com" />
      </el-form-item>
      <el-form-item label="简介描述">
        <el-input v-model="form.description" maxlength="100" show-word-limit placeholder="一句话说明这个网站" />
      </el-form-item>
      <div class="admin-form-grid admin-form-grid--2">
        <el-form-item label="图标文字 / URL" prop="icon">
          <el-input v-model="form.icon" maxlength="255" placeholder="例如：GH 或 https://.../icon.png" />
          <p class="admin-form-tip">建议使用 1–3 字短标记 / Emoji，或填写完整 HTTP(S) 图片 URL。</p>
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
      </div>
      <div class="admin-switch-row">
        <el-checkbox v-model="form.visible">前台展示</el-checkbox>
        <el-checkbox v-model="form.isExternal">新窗口打开</el-checkbox>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存书签</el-button>
    </template>
  </el-dialog>
</template>
