<script setup lang="ts">
import { Close, Search } from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { SearchEngine } from '@/types/searchEngine'
import {
  isSameSearchEngine,
  searchEngineIconUrl,
  searchEngineMark,
} from '@/utils/searchEnginePicker'

const props = defineProps<{
  modelValue: string
  resultCount: number
  engine: SearchEngine
  engines: SearchEngine[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'select-engine': [engineId: SearchEngine['id']]
  submit: []
  clear: []
}>()

const searchRoot = ref<HTMLDivElement | null>(null)
const engineTrigger = ref<HTMLButtonElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const pickerOpen = ref(false)
const canChooseEngine = computed(() => props.engines.length > 1)

function togglePicker() {
  if (!canChooseEngine.value) return
  pickerOpen.value = !pickerOpen.value
  if (pickerOpen.value) {
    void nextTick(() => {
      searchRoot.value
        ?.querySelector<HTMLButtonElement>('.portal-search__picker-option.is-active')
        ?.focus()
    })
  }
}

function closePicker(focusTrigger = false) {
  if (!pickerOpen.value) return
  pickerOpen.value = false
  if (focusTrigger) void nextTick(() => engineTrigger.value?.focus())
}

function selectEngine(engine: SearchEngine) {
  emit('select-engine', engine.id)
  closePicker()
  void nextTick(() => searchInput.value?.focus())
}

function handlePointerDown(event: PointerEvent) {
  if (!searchRoot.value?.contains(event.target as Node)) closePicker()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !pickerOpen.value) return
  event.preventDefault()
  closePicker(true)
}

function handleFocusOut(event: FocusEvent) {
  if (!pickerOpen.value) return
  const nextTarget = event.relatedTarget as Node | null
  if (nextTarget && searchRoot.value?.contains(nextTarget)) return

  void nextTick(() => {
    if (!searchRoot.value?.contains(document.activeElement)) closePicker()
  })
}

function handleSubmit() {
  closePicker()
  emit('submit')
}

onMounted(() => document.addEventListener('pointerdown', handlePointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handlePointerDown))
</script>

<template>
  <div
    ref="searchRoot"
    class="portal-search-shell"
    @keydown="handleKeydown"
    @focusout="handleFocusOut"
  >
    <form class="portal-search" role="search" @submit.prevent="handleSubmit">
      <button
        ref="engineTrigger"
        class="portal-search__engine"
        type="button"
        :class="{ 'is-open': pickerOpen }"
        :aria-label="canChooseEngine ? `当前搜索引擎：${engine.name}，点击选择` : `搜索引擎：${engine.name}`"
        :aria-expanded="pickerOpen"
        :aria-haspopup="canChooseEngine ? 'dialog' : undefined"
        :aria-controls="canChooseEngine ? 'search-engine-picker' : undefined"
        :title="canChooseEngine ? `当前：${engine.name}，点击选择` : engine.name"
        @click="togglePicker"
      >
        <img
          v-if="searchEngineIconUrl(engine)"
          :src="searchEngineIconUrl(engine)"
          alt=""
          referrerpolicy="no-referrer"
        />
        <template v-else>{{ searchEngineMark(engine) }}</template>
      </button>
      <span class="portal-search__divider" aria-hidden="true" />
      <label class="sr-only" for="site-search">使用 {{ engine.name }} 搜索，同时筛选站内书签</label>
      <input
        id="site-search"
        ref="searchInput"
        :value="modelValue"
        type="search"
        autocomplete="off"
        :placeholder="engine.placeholder || '想要搜索什么'"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      />
      <button
        v-if="modelValue"
        class="portal-search__clear"
        type="button"
        aria-label="清空搜索"
        @click="emit('clear')"
      >
        <Close aria-hidden="true" />
      </button>
      <button class="portal-search__submit" type="submit" aria-label="搜索">
        <Search aria-hidden="true" />
      </button>
      <p class="sr-only" aria-live="polite">当前匹配 {{ resultCount }} 个书签</p>
    </form>

    <section
      v-if="pickerOpen"
      id="search-engine-picker"
      class="portal-search__picker"
      role="dialog"
      aria-label="选择搜索引擎"
    >
      <header class="portal-search__picker-header">
        <strong>选择搜索引擎</strong>
        <span>{{ engines.length }} 个可用</span>
      </header>
      <div class="portal-search__picker-grid" role="group" aria-label="可用搜索引擎">
        <button
          v-for="candidate in engines"
          :key="candidate.id"
          class="portal-search__picker-option"
          :class="{ 'is-active': isSameSearchEngine(candidate.id, engine.id) }"
          type="button"
          :aria-pressed="isSameSearchEngine(candidate.id, engine.id)"
          @click="selectEngine(candidate)"
        >
          <span class="portal-search__picker-icon" aria-hidden="true">
            <img
              v-if="searchEngineIconUrl(candidate)"
              :src="searchEngineIconUrl(candidate)"
              alt=""
              referrerpolicy="no-referrer"
            />
            <template v-else>{{ searchEngineMark(candidate) }}</template>
          </span>
          <span class="portal-search__picker-name">{{ candidate.name }}</span>
        </button>
      </div>
    </section>
  </div>
</template>
