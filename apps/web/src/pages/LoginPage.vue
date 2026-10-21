<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import {
  Connection,
  Lock,
  OfficeBuilding,
  Right,
} from "@element-plus/icons-vue";
import { asApiError, type ApiError, type Persona } from "../api/client";
import { useSessionStore } from "../stores/session";
import ErrorPanel from "../components/ErrorPanel.vue";

const session = useSessionStore();
const router = useRouter();
const persona = ref<Persona>("OWNER_A");
const error = ref<ApiError | null>(null);
async function login() {
  error.value = null;
  try {
    await session.login(persona.value);
    await router.replace(session.can("reports.read") ? "/dashboard" : session.can("orders.read") ? "/orders" : "/403");
  } catch (cause) {
    error.value = asApiError(cause);
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-brand">
      <span class="brand-symbol"><el-icon><Connection /></el-icon></span><span>智采云 <small>SMARTPROCURE</small></span>
    </div>
    <section class="login-content" aria-labelledby="login-title">
      <div class="login-intro">
        <p class="eyebrow">多租户电商采购工作台</p>
        <h1>从清晰的工作台，<br />开始每一天。</h1>
        <p class="intro-copy">
          在隔离的演示环境中，核验 SKU 货源，查看销售订单、Mock 采购与合成付款进度。
        </p>
        <div class="intro-note">
          <el-icon><Lock /></el-icon><span>真实平台通道已关闭；不执行真实采购或付款</span>
        </div>
        <div class="milestone-line">
          <span class="milestone-dot"></span>M3 双模式 Mock 支付
          <span class="muted">/ 本地演示</span>
        </div>
      </div>
      <section class="login-card" aria-labelledby="login-title">
        <el-tag effect="light" type="warning">MOCK 演示环境</el-tag>
        <h2 id="login-title">进入演示工作台</h2>
        <p class="muted">仅使用合成身份；无需填写任何真实账号或密码。</p>
        <form @submit.prevent="login">
          <label for="persona">选择演示身份</label>
          <el-select
            id="persona"
            v-model="persona"
            aria-label="选择演示身份"
            size="large"
            :disabled="session.loading"
          >
            <el-option label="商家 A · 店主" value="OWNER_A" />
            <el-option label="商家 A · 客服（权限受限）" value="SUPPORT_A" />
            <el-option label="商家 B · 店主" value="OWNER_B" />
          </el-select>
          <div class="identity-note">
            <el-icon><OfficeBuilding /></el-icon><span>身份与店铺范围由服务端会话确认。客服仅可查看授权范围内的店铺、订单与付款记录。</span>
          </div>
          <ErrorPanel v-if="error" :error="error" title="登录未完成" />
          <el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="session.loading"
          >
            进入工作台<el-icon class="button-icon"><Right /></el-icon>
          </el-button>
        </form>
        <p class="login-footnote">
          M3 提供人工、自动与规则模式的合成付款、UNKNOWN 冻结及只读查单；不执行真实付款。
        </p>
      </section>
    </section>
    <footer class="login-footer">
      智采云 · 独立 B/S 多租户采购 SaaS
      <span>本地演示 · 生产身份系统尚未实现</span>
    </footer>
  </main>
</template>
