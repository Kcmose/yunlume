<script setup lang="ts">
import { computed } from 'vue'
import type { NavigationCategory } from '@/types/category'
import BookmarkItem from './BookmarkItem.vue'
import { navigationIconLabel, navigationIconUrl } from '@/utils/adminNavigationManage'

const props = defineProps<{
  category: NavigationCategory
}>()

const iconLabel = computed(() => navigationIconLabel(props.category.icon ?? '', '◈'))
const iconUrl = computed(() => navigationIconUrl(props.category.icon ?? ''))

function anchorId(id: string | number) {
  return `category-${String(id).replace(/[^a-zA-Z0-9_-]/g, '-')}`
}
</script>

<template>
  <article :id="anchorId(category.id)" class="category-card">
    <h2 class="category-card__title">
      <span class="category-card__icon" aria-hidden="true">
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
