<script setup lang="ts">
import { watch } from 'vue';
import { api } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useRemote } from '../composables/useRemote';
import ErrorPanel from './ErrorPanel.vue';
import ReportFinancialSummary from './ReportFinancialSummary.vue';
const session=useSessionStore();const remote=useRemote(()=>api.getReportOverview());watch(()=>session.user?.currentTenantId,()=>{if(session.can('reports.read'))void remote.load();},{immediate:true});
</script>
<template><section v-if="session.can('reports.read')"><div class="panel-heading"><h2>经营金额概览</h2><RouterLink class="text-link" to="/reports">查看范围与订单明细</RouterLink><el-button :loading="remote.loading.value" @click="remote.load">刷新金额</el-button></div><div v-if="remote.loading.value" class="panel data-state" role="status">正在读取 Mock 经营金额…</div><ErrorPanel v-else-if="remote.error.value" :error="remote.error.value" title="经营金额读取失败"><el-button @click="remote.load">重试经营金额</el-button></ErrorPanel><ReportFinancialSummary v-else-if="remote.data.value" :report="remote.data.value" /></section></template>
