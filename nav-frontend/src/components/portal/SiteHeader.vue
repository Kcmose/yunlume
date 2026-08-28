<script setup lang="ts">
defineProps<{
  name: string
  description: string
}>()
</script>

<template>
  <section class="portal-hero" aria-labelledby="site-title">
    <h1 id="site-title">{{ name }}</h1>
    <p class="portal-hero__description">{{ description }}</p>
  </section>
</template>
