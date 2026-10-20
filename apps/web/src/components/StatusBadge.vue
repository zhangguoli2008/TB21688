<script setup lang="ts">
import { computed } from 'vue';
import { CircleCheck, Clock, Warning, CircleClose } from '@element-plus/icons-vue';
import { statusNames } from '../domain/m1';
const props = defineProps<{
    status: string;
}>();
const tone = computed(() => ['ACTIVE', 'PAID', 'VERIFIED_PAID'].includes(props.status) ? 'success' : ['CANCELLED', 'REVOKED', 'SUSPENDED'].includes(props.status) ? 'danger' : ['REFUNDING', 'EXPIRED'].includes(props.status) ? 'warning' : 'neutral');
const icon = computed(() => tone.value === 'success' ? CircleCheck : tone.value === 'danger' ? CircleClose : tone.value === 'warning' ? Warning : Clock);
</script>
<template><span class="status-badge" :class="`badge-${tone}`"><el-icon aria-hidden="true"><component :is="icon" /></el-icon>{{ statusNames[status] ?? status }}</span></template>
