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
      当前角色：{{ session.user?.roles.join("、") }}。此页面需要相应的操作权限。
    </p>
    <p class="muted">客服可查看授权范围内的店铺与销售订单；管理操作需由店主执行。</p>
    <RouterLink
      v-if="session.can('orders.read')"
      to="/orders"
      class="el-button el-button--primary"
    >
      返回订单工作台
    </RouterLink>
    <RouterLink
      v-if="session.can('stores.read')"
      to="/stores"
      class="el-button"
    >
      查看店铺
    </RouterLink>
    <RouterLink
      v-if="session.can('reports.read')"
      to="/dashboard"
      class="el-button el-button--primary"
    >
      返回经营总览
    </RouterLink>
  </section>
</template>
