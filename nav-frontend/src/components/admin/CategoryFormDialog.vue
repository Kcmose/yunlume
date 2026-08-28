<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import type { Category, CategoryPayload } from '@/types/category'
import { isValidNavigationIcon } from '@/utils/adminNavigationManage'

const props = defineProps<{
  modelValue: boolean
  category: Category | null
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [payload: CategoryPayload]
}>()

const formRef = ref<FormInstance>()
const form = reactive<CategoryPayload>({ name: '', icon: '✦', sortOrder: 0, visible: true })
const rules: FormRules<CategoryPayload> = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
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
    Object.assign(form, props.category
      ? { name: props.category.name, icon: props.category.icon, sortOrder: props.category.sortOrder, visible: props.category.visible }
      : { name: '', icon: '✦', sortOrder: 0, visible: true })
    void nextTick(() => formRef.value?.clearValidate())
  },
)

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  emit('submit', { ...form })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="category ? '编辑分类' : '新增分类'"
    width="min(520px, calc(100vw - 32px))"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="分类名称" prop="name">
        <el-input v-model="form.name" maxlength="30" show-word-limit placeholder="例如：开发者社区" />
      </el-form-item>
      <div class="admin-form-grid admin-form-grid--2">
        <el-form-item label="分类图标" prop="icon">
          <el-input v-model="form.icon" maxlength="100" placeholder="例如：✦、DEV 或 https://.../icon.png" />
          <p class="admin-form-tip">建议使用 1–3 字短标记 / Emoji，或填写完整 HTTP(S) 图片 URL。</p>
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
      </div>
      <el-form-item label="前台展示">
        <el-switch v-model="form.visible" inline-prompt active-text="是" inactive-text="否" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存分类</el-button>
    </template>
  </el-dialog>
</template>
