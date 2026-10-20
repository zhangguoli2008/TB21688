<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  Box,
  CircleCheck,
  Connection,
  DataLine,
  Refresh,
  Warning,
} from "@element-plus/icons-vue";
import {
  api,
  asApiError,
  type ApiError,
  type SystemStatus,
} from "../api/client";
import ErrorPanel from "../components/ErrorPanel.vue";

const loading = ref(true);
const error = ref<ApiError | null>(null);
const status = ref<SystemStatus | null>(null);
const checkedAt = ref<Date | null>(null);
const serviceNames = {
  api: "API 服务",
  postgresql: "PostgreSQL",
  redis: "Redis",
  rabbitmq: "RabbitMQ",
};
const stateNames = { UP: "运行正常", DOWN: "异常", UNKNOWN: "未知" };
const formatTime = (time: Date) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(time);
async function loadStatus() {
  loading.value = true;
  error.value = null;
  status.value = null;
  try {
    status.value = await api.getSystemStatus();
    checkedAt.value = new Date();
  } catch (cause) {
    error.value = asApiError(cause);
  } finally {
    loading.value = false;
  }
}
onMounted(loadStatus);
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">工作台 / 总览</p>
        <h1>经营总览</h1>
        <p class="muted">欢迎进入演示工作台，基础环境状态尽在这里。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadStatus">
        刷新状态
      </el-button>
    </div>
    <div class="summary-grid">
      <section class="summary-card">
        <span class="summary-icon"><el-icon><Connection /></el-icon></span>
        <div>
          <span class="card-label">运行模式</span><strong>MOCK <small>演示</small></strong>
          <p>使用合成身份与模拟适配器</p>
        </div>
      </section>
      <section class="summary-card">
        <span class="summary-icon"><el-icon><CircleCheck /></el-icon></span>
        <div>
          <span class="card-label">真实平台通道</span><strong>已关闭</strong>
          <p>待平台审批及正式授权</p>
        </div>
      </section>
      <section class="summary-card">
        <span class="summary-icon"><el-icon><DataLine /></el-icon></span>
        <div>
          <span class="card-label">当前交付阶段</span><strong>M0 <small>基础框架</small></strong>
          <p>认证、租户隔离与服务检查</p>
        </div>
      </section>
    </div>
    <section
      class="panel services-panel"
      aria-labelledby="services-title"
      :aria-busy="loading"
    >
      <div class="panel-heading">
        <div>
          <h2 id="services-title">基础服务状态</h2>
          <p>来自 API 实际探测，异常不会显示为正常。</p>
        </div>
        <span v-if="checkedAt && !loading && !error" class="checked-at">检查于 {{ formatTime(checkedAt) }} <small>上海时间</small></span>
      </div>
      <div
        v-if="loading"
        class="status-loading"
        role="status"
        aria-live="polite"
      >
        <el-skeleton :rows="2" animated /><span>正在检查服务连接…</span>
      </div>
      <ErrorPanel v-else-if="error" :error="error" title="服务状态获取失败">
        <el-button data-testid="retry-status" @click="loadStatus">
          重试
        </el-button>
      </ErrorPanel>
      <div
        v-else-if="status"
        data-testid="service-list"
        class="service-grid"
        aria-live="polite"
      >
        <div
          v-for="service in status.services"
          :key="service.name"
          class="service-item"
          :data-testid="`service-${service.name}`"
        >
          <div class="service-name">
            <el-icon><Connection /></el-icon><strong>{{ serviceNames[service.name] }}</strong>
          </div>
          <span
            class="service-state"
            :class="`state-${service.status.toLowerCase()}`"
          ><span class="state-dot"></span>{{ stateNames[service.status] }}</span>
        </div>
      </div>
      <p v-if="status" class="service-note">
        <el-icon><Warning /></el-icon>真实通道关闭。服务可达不代表已获得平台授权或交易能力。
      </p>
    </section>
    <section class="panel business-empty" aria-labelledby="business-title">
      <div class="empty-illustration">
        <el-icon><Box /></el-icon>
      </div>
      <div>
        <el-tag size="small" type="info" effect="plain">
          业务功能后续开放
        </el-tag>
        <h2 id="business-title">这里将呈现您的经营数据</h2>
        <p>
          当前阶段尚未开放店铺、订单、采购、支付和履约流程。<br />完成后续里程碑后，经营数据与待办将出现在这里。
        </p>
      </div>
    </section>
    <section class="scope-note">
      <strong>M0 演示范围</strong><span>本地会话</span><span>当前租户</span><span>权限保护</span><span>真实服务状态</span><span class="muted">无真实交易</span>
    </section>
  </div>
</template>
