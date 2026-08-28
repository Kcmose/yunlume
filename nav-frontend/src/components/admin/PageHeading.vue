<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  description: string
}>()
</script>

<template>
  <div class="admin-page-heading">
    <div>
      <p>{{ eyebrow || 'CONTENT MANAGEMENT' }}</p>
      <h2>{{ title }}</h2>
      <span>{{ description }}</span>
    </div>
    <div class="admin-page-heading__actions">
      <slot />
    </div>
  </div>
</template>
