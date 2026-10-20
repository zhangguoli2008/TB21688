<script setup lang="ts">
defineProps<{
    total: number;
    limit: number;
    offset: number;
    loading: boolean;
}>();
const emit = defineEmits<{
    change: [
        offset: number,
        limit: number
    ];
}>();
function resize(event: {
    target: unknown;
}) { emit('change', 0, Number((event.target as {
    value: string;
}).value)); }
</script>
<template><div class="pagination-bar"><span>共 {{ total }} 条 · 当前 {{ total ? offset + 1 : 0 }}–{{ Math.min(offset + limit,total) }} 条</span><div class="inline-actions"><label>每页 <select aria-label="每页数量" :value="limit" :disabled="loading" @change="resize"><option :value="20">20 条</option><option :value="50">50 条</option><option :value="100">100 条</option></select></label><el-button size="small" :disabled="loading || offset === 0" @click="emit('change',Math.max(0,offset-limit),limit)">上一页</el-button><el-button size="small" :disabled="loading || offset+limit >= total || offset+limit > 10000" @click="emit('change',offset+limit,limit)">下一页</el-button></div></div></template>
