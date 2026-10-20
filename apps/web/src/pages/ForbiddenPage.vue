<script setup lang="ts">
import { Lock } from "@element-plus/icons-vue";
import { useSessionStore } from "../stores/session";
const session = useSessionStore();
</script>

<template>
  <section class="panel route-state" aria-labelledby="forbidden-title">
    <span class="route-state-icon"><el-icon><Lock /></el-icon></span>
    <p class="eyebrow">403 · 权限不足</p>
    <h1 id="forbidden-title">当前身份无权访问此页面</h1>
    <p>
      当前角色：{{ session.user?.roles.join("、") }}。经营总览需要 reports.read
      权限。
    </p>
    <p class="muted">您可以使用右上角“退出”，再选择商家店主身份演示。</p>
    <RouterLink
      v-if="session.can('reports.read')"
      to="/dashboard"
      class="el-button el-button--primary"
    >
      返回经营总览
    </RouterLink><el-tag v-else type="info">客服身份仅拥有演示资源权限</el-tag>
  </section>
</template>
