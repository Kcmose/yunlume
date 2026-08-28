<script setup lang="ts">
import { computed } from 'vue'
import type { Bookmark } from '@/types/bookmark'

const props = defineProps<{ bookmark: Bookmark }>()

const iconLabel = computed(() => {
  const icon = props.bookmark.icon?.trim()
  if (icon && icon.length <= 3 && !/^https?:\/\//i.test(icon)) return icon
  return '▱'
})

const iconUrl = computed(() => {
  const icon = props.bookmark.icon?.trim()
  return icon && /^https?:\/\//i.test(icon) ? icon : ''
})
</script>

<template>
  <a
    class="bookmark-item"
    :href="bookmark.url"
    :target="bookmark.isExternal ? '_blank' : '_self'"
    :rel="bookmark.isExternal ? 'noopener noreferrer' : undefined"
    :aria-label="bookmark.name"
    :title="bookmark.description ? `${bookmark.name} · ${bookmark.description}` : bookmark.name"
  >
    <span class="bookmark-item__icon" aria-hidden="true">
      <img
        v-if="iconUrl"
        :src="iconUrl"
        alt=""
        loading="lazy"
        referrerpolicy="no-referrer"
      />
      <template v-else>{{ iconLabel }}</template>
    </span>
    <span class="bookmark-item__name">{{ bookmark.name }}</span>
  </a>
</template>
