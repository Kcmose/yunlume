<script setup lang="ts">
import type { NavigationCategory } from '@/types/category'
import CategoryCard from './CategoryCard.vue'

withDefaults(defineProps<{
  categories: NavigationCategory[]
  loading?: boolean
  usingFallback?: boolean
  searchActive?: boolean
}>(), {
  loading: false,
  usingFallback: false,
  searchActive: false,
})

defineEmits<{ retry: [] }>()
</script>

<template>
  <section id="navigation-content" class="category-section" aria-labelledby="navigation-heading">
    <h2 id="navigation-heading" class="sr-only">网站导航分类</h2>
    <div v-if="usingFallback" class="portal-runtime-status" role="status">
      <span>部分公开数据暂时无法更新，页面已保留可用内容或使用安全默认值。</span>
      <button type="button" :disabled="loading" @click="$emit('retry')">
        {{ loading ? '正在重试…' : '重新连接' }}
      </button>
    </div>
    <div v-if="categories.length" class="category-grid">
      <CategoryCard v-for="category in categories" :key="category.id" :category="category" />
    </div>
    <div v-else-if="loading" class="portal-empty is-loading" role="status" aria-live="polite">
      <span aria-hidden="true">···</span>
      <h2>正在加载导航内容</h2>
      <p>正在连接服务，请稍候。</p>
    </div>
    <div v-else class="portal-empty">
      <span aria-hidden="true">⌕</span>
      <h2>{{ searchActive ? '没有找到相关书签' : '暂时还没有导航内容' }}</h2>
      <p>
        {{ searchActive
          ? '换个关键词试试，按回车可继续使用当前搜索引擎。'
          : '管理员添加并启用分类与书签后，会在这里展示。' }}
      </p>
    </div>
  </section>
</template>
