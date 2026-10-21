<script setup lang="ts">
import { useSessionStore } from '../stores/session';
const session=useSessionStore();
</script>
<template><nav class="catalog-tabs" aria-label="支付中心导航"><RouterLink to="/payments/queue" active-class="active">付款队列</RouterLink><RouterLink v-if="session.can('paymentPolicy.manage')" to="/payments/policies" active-class="active">支付策略</RouterLink><RouterLink to="/payments/mandates" active-class="active">Mock 签约</RouterLink><RouterLink to="/payments/reconciliations" active-class="active">只读对账记录</RouterLink></nav></template>
