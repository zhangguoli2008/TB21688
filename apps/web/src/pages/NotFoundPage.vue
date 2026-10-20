<script setup lang="ts">
import { Document } from "@element-plus/icons-vue";
import { useSessionStore } from "../stores/session";
const session = useSessionStore();
</script>

<template>
  <section class="panel route-state" aria-labelledby="not-found-title">
    <span class="route-state-icon"><el-icon><Document /></el-icon></span>
    <p class="eyebrow">404 · 页面不存在</p>
    <h1 id="not-found-title">暂时找不到这个页面</h1>
    <p class="muted">请检查链接地址，业务页面将按里程碑逐步开放。</p>
    <RouterLink
      :to="session.can('reports.read') ? '/dashboard' : '/403'"
      class="el-button el-button--primary"
    >
      返回工作台
    </RouterLink>
  </section>
</template>
