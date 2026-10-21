<script setup lang="ts">
import { computed } from 'vue';
import { CircleCheck, Clock, Warning, CircleClose } from '@element-plus/icons-vue';
import { statusNames } from '../domain/m1';
import { stateNames } from '../domain/m2';
import { paymentStateNames } from '../domain/m3';
const props = defineProps<{
    status: string;
    kind?: 'payment' | 'procurement';
}>();
const labels = computed(() => props.kind==='payment'?paymentStateNames:props.kind==='procurement'?stateNames:statusNames);
const tone = computed(() => ['ACTIVE', 'PAID', 'VERIFIED_PAID', 'CONFIRMED'].includes(props.status) ? 'success' : ['CANCELLED', 'REVOKED', 'SUSPENDED', 'REVIEW_REQUIRED', 'FAILED'].includes(props.status) ? 'danger' : ['REFUNDING', 'EXPIRED', 'CREATE_UNKNOWN', 'AWAITING_PAYMENT', 'REVIEW', 'PAUSED', 'UNKNOWN', 'MANUAL_RECONCILIATION', 'WAIT_MERCHANT_PAYMENT', 'BLOCKED'].includes(props.status) ? 'warning' : 'neutral');
const icon = computed(() => tone.value === 'success' ? CircleCheck : tone.value === 'danger' ? CircleClose : tone.value === 'warning' ? Warning : Clock);
</script>
<template><span class="status-badge" :class="`badge-${tone}`"><el-icon aria-hidden="true"><component :is="icon" /></el-icon>{{ labels[status] ?? stateNames[status] ?? paymentStateNames[status] ?? status }}</span></template>
