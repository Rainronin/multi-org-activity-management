<script setup lang="ts">
import { useSessionStore } from './stores/session'

const session = useSessionStore()
</script>

<template>
  <!-- 会话变化清除旧页面；失效时先卸载受保护页面及其草稿守卫。 -->
  <RouterView
    v-if="$route.meta.public || session.status !== 'guest'"
    :key="session.generation"
  />
</template>
