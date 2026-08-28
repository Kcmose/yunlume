<script setup lang="ts">
import { computed } from 'vue'
import type { NavigationCategory } from '@/types/category'
import BookmarkItem from './BookmarkItem.vue'

const props = defineProps<{
  category: NavigationCategory
}>()

const iconLabel = computed(() => {
  const icon = props.category.icon?.trim()
  if (icon && icon.length <= 3 && !/^https?:\/\//i.test(icon)) return icon
  return '◈'
})

const iconUrl = computed(() => {
  const icon = props.category.icon?.trim()
  return icon && /^https?:\/\//i.test(icon) ? icon : ''
})

function anchorId(id: string | number) {
  return `category-${String(id).replace(/[^a-zA-Z0-9_-]/g, '-')}`
}
</script>

<template>
  <article :id="anchorId(category.id)" class="category-card">
    <h2 class="category-card__title">
      <span aria-hidden="true">
        <img
          v-if="iconUrl"
          :src="iconUrl"
          alt=""
          loading="lazy"
          referrerpolicy="no-referrer"
        />
        <template v-else>{{ iconLabel }}</template>
      </span>
      {{ category.name }}
    </h2>
    <div class="category-card__body">
      <div class="category-card__bookmarks">
        <BookmarkItem v-for="bookmark in category.bookmarks" :key="bookmark.id" :bookmark="bookmark" />
      </div>
    </div>
  </article>
</template>
