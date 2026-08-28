<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import type { EntityId, SortOrderItem } from '@/types/common'
import {
  buildSequentialSortPayload,
  hasSameEntityOrder,
  moveItem,
} from '@/utils/adminNavigationManage'

interface SortDialogItem {
  id: EntityId
  label: string
  meta?: string
  icon?: string
  iconUrl?: string
}

const props = defineProps<{
  modelValue: boolean
  title: string
  description: string
  items: SortDialogItem[]
  submitting: boolean
  emptyText?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [items: SortOrderItem[]]
}>()

const draft = ref<SortDialogItem[]>([])
const announcement = ref('')
const changed = computed(() => !hasSameEntityOrder(props.items, draft.value))

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    draft.value = props.items.map((item) => ({ ...item }))
    announcement.value = ''
  },
)

function move(index: number, offset: number) {
  const targetIndex = index + offset
  if (targetIndex < 0 || targetIndex >= draft.value.length) return
  const item = draft.value[index]
  draft.value = moveItem(draft.value, index, targetIndex)
  announcement.value = `${item?.label ?? '当前项目'}已移动到第 ${targetIndex + 1} 位`
}

function updateVisible(visible: boolean) {
  if (!visible && props.submitting) return
  emit('update:modelValue', visible)
}

function submit() {
  if (!changed.value || props.submitting || !draft.value.length) return
  emit('submit', buildSequentialSortPayload(draft.value))
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="min(580px, calc(100vw - 24px))"
    class="sort-order-dialog"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    destroy-on-close
    @update:model-value="updateVisible"
  >
    <p id="sort-order-help" class="sort-order-dialog__description">
      {{ description }} 可使用每项右侧按钮调整，也可聚焦整行后按 Alt + ↑ / Alt + ↓。
    </p>

    <ol
      v-if="draft.length"
      class="sort-order-list"
      aria-describedby="sort-order-help"
    >
      <li
        v-for="(item, index) in draft"
        :key="item.id"
        class="sort-order-item"
        tabindex="0"
        @keydown.alt.up.prevent="move(index, -1)"
        @keydown.alt.down.prevent="move(index, 1)"
      >
        <span class="sort-order-item__position" aria-hidden="true">{{ index + 1 }}</span>
        <span class="sort-order-item__icon" aria-hidden="true">
          <img v-if="item.iconUrl" :src="item.iconUrl" alt="" referrerpolicy="no-referrer" />
          <template v-else>{{ item.icon || '↕' }}</template>
        </span>
        <span class="sort-order-item__content">
          <strong>{{ item.label }}</strong>
          <small v-if="item.meta">{{ item.meta }}</small>
        </span>
        <span class="sort-order-item__actions">
          <el-button
            circle
            plain
            :icon="ArrowUp"
            :disabled="index === 0 || submitting"
            :aria-label="`将${item.label}上移一位`"
            @click="move(index, -1)"
          />
          <el-button
            circle
            plain
            :icon="ArrowDown"
            :disabled="index === draft.length - 1 || submitting"
            :aria-label="`将${item.label}下移一位`"
            @click="move(index, 1)"
          />
        </span>
      </li>
    </ol>
    <el-empty v-else :description="emptyText || '暂无可排序项目'" />

    <p class="sort-order-dialog__live" aria-live="polite">{{ announcement }}</p>

    <template #footer>
      <el-button :disabled="submitting" @click="updateVisible(false)">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!changed || !draft.length"
        @click="submit"
      >
        保存排序
      </el-button>
    </template>
  </el-dialog>
</template>
