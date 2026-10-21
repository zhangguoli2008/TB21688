<script setup lang="ts">
import { useSessionStore } from '../stores/session';
const session=useSessionStore();
</script>
<template><nav class="catalog-tabs" aria-label="履约经营导航"><RouterLink v-if="session.can('logistics.read')" to="/shipments" active-class="active">物流中心</RouterLink><RouterLink v-if="session.can('aftersales.read')" to="/aftersales" active-class="active">售后中心</RouterLink><RouterLink v-if="session.can('reports.read')" to="/reports" active-class="active">经营报表</RouterLink><RouterLink v-if="session.can('notifications.read')" to="/notifications" active-class="active">站内通知</RouterLink></nav></template>
