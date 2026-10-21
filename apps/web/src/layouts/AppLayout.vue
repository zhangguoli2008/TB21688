<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Box,
  Connection,
  DataAnalysis,
  Document,
  Goods,
  Lock,
  OfficeBuilding,
  Setting,
  ShoppingCart,
  SwitchButton,
  User,
  Van,
  Wallet,
} from "@element-plus/icons-vue";
import { useSessionStore } from "../stores/session";
import { asApiError, type ApiError } from "../api/client";
import ErrorPanel from "../components/ErrorPanel.vue";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();
const logoutError = ref<ApiError | null>(null);
const futureNavigation = [
  { label: "物流与售后", icon: Van, stage: "M4" },
  { label: "经营报表", icon: DataAnalysis, stage: "M4" },
];
async function logout() {
  logoutError.value = null;
  try {
    await session.logout();
    await router.replace("/login");
  } catch (cause) {
    logoutError.value = asApiError(cause);
  }
}
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <aside class="sidebar">
      <div class="app-brand">
        <span class="brand-symbol"><el-icon><Connection /></el-icon></span><span>智采云<small>SMARTPROCURE</small></span>
      </div>
      <div class="workspace-label">商家工作台 <span>M3</span></div>
      <nav aria-label="主导航">
        <RouterLink
          v-if="session.can('reports.read')"
          class="navigation-item"
          :class="{ active: route.path === '/dashboard' }"
          to="/dashboard"
          :aria-current="route.path === '/dashboard' ? 'page' : undefined"
        >
          <el-icon><DataAnalysis /></el-icon><span>经营总览</span>
        </RouterLink>
        <p class="nav-caption">业务管理</p>
        <RouterLink v-if="session.can('stores.read')" class="navigation-item" :class="{active:route.path.startsWith('/stores')}" to="/stores" :aria-current="route.path.startsWith('/stores')?'page':undefined"><el-icon><OfficeBuilding /></el-icon><span>店铺中心</span></RouterLink>
        <RouterLink v-if="session.can('orders.read')" class="navigation-item" :class="{active:route.path.startsWith('/orders')}" to="/orders" :aria-current="route.path.startsWith('/orders')?'page':undefined"><el-icon><Document /></el-icon><span>订单工作台</span></RouterLink>
        <RouterLink v-if="session.can('sourcing.read')" class="navigation-item" :class="{active:route.path.startsWith('/catalog')}" to="/catalog/mappings" :aria-current="route.path.startsWith('/catalog')?'page':undefined"><el-icon><Goods /></el-icon><span>商品与货源</span></RouterLink>
        <RouterLink v-if="session.can('procurement.read')" class="navigation-item" :class="{active:route.path.startsWith('/purchases')||route.path.startsWith('/purchase-intents')}" to="/purchases" :aria-current="route.path.startsWith('/purchases')||route.path.startsWith('/purchase-intents')?'page':undefined"><el-icon><ShoppingCart /></el-icon><span>采购管理</span></RouterLink>
        <RouterLink v-if="session.can('payments.read')" class="navigation-item" :class="{active:route.path.startsWith('/payments')}" to="/payments/queue" :aria-current="route.path.startsWith('/payments')?'page':undefined"><el-icon><Wallet /></el-icon><span>支付中心</span></RouterLink>
        <button
          v-for="item in futureNavigation"
          :key="item.label"
          class="navigation-item future-nav"
          type="button"
          disabled
          :aria-label="`${item.label}，${item.stage} 阶段开放`"
        >
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><small>{{ item.stage }}</small>
        </button>
        <p class="nav-caption">组织与设置</p>
        <button class="navigation-item future-nav" type="button" disabled>
          <el-icon><User /></el-icon><span>成员与权限</span><small>后续</small>
        </button>
        <button class="navigation-item future-nav" type="button" disabled>
          <el-icon><Setting /></el-icon><span>审计与设置</span><small>后续</small>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <el-icon><Box /></el-icon>
        <div>演示环境已隔离<small>真实通道关闭 · 待授权</small></div>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="tenant-context">
          <el-icon><OfficeBuilding /></el-icon>
          <div>
            <span class="context-label">当前租户</span><strong>{{ session.user?.tenantName ?? "等待会话确认" }}</strong>
          </div>
          <span class="tenant-fixed">服务端确认</span>
        </div>
        <div class="account-actions">
          <el-tag type="warning" effect="plain">MOCK</el-tag><span class="account-email">{{ session.user?.email }}</span><el-button
            text
            :icon="SwitchButton"
            :loading="session.loading"
            @click="logout"
          >
            退出
          </el-button>
        </div>
      </header>
      <div class="mode-banner" role="note">
        <el-icon><Lock /></el-icon><strong>MOCK 演示模式</strong><span>真实平台通道已关闭；不执行真实采购、扣款或物流回填。</span>
      </div>
      <main id="main-content" class="main-content" tabindex="-1">
        <ErrorPanel
          v-if="logoutError"
          class="logout-error"
          :error="logoutError"
          title="退出未确认，请重试"
        />
        <RouterView :key="`${session.user?.currentTenantId}:${session.user?.userId}`" />
      </main>
      <footer class="workspace-footer">
        智采云 <span>M3 双模式支付模拟 · Mock / 待授权</span><span>时间显示：Asia/Shanghai</span>
      </footer>
    </div>
  </div>
</template>
