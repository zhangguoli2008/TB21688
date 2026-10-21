<script setup lang="ts">
import { ref,watch } from 'vue';
import { api } from '../api/client';
import { useRemote } from '../composables/useRemote';
import { shanghaiTime } from '../domain/m1';
import ErrorPanel from './ErrorPanel.vue';
import PagePagination from './PagePagination.vue';
const props=defineProps<{orderId:string}>();const offset=ref(0),limit=ref(20);const remote=useRemote(()=>api.listOrderActivity(props.orderId,{limit:limit.value,offset:offset.value}));
watch(()=>props.orderId,()=>{offset.value=0;void remote.load();},{immediate:true});
function paginate(next:number,size:number){offset.value=next;limit.value=size;void remote.load();}
</script>
<template><section class="panel timeline-panel"><div class="panel-heading"><div><h2>操作时间轴</h2><p>当前销售订单的安全事件摘要 · Mock / 上海时间</p></div><el-button :loading="remote.loading.value" @click="remote.load">刷新时间轴</el-button></div><p v-if="remote.loading.value" role="status" class="help-text">正在读取操作时间轴…</p><ErrorPanel v-else-if="remote.error.value" :error="remote.error.value" title="操作时间轴读取失败"><el-button @click="remote.load">重试时间轴</el-button></ErrorPanel><p v-else-if="!remote.data.value?.items.length" class="help-text muted">暂无已记录的操作事件。</p><ol v-else class="event-timeline"><li v-for="item in remote.data.value.items" :key="item.id"><div><strong>{{ item.action }}</strong><p>{{ item.source }} · {{ item.entityType }} · {{ item.code??'已记录' }}</p><small>{{ shanghaiTime(item.occurredAt) }} · {{ item.actorId?'已认证操作者':'后台任务' }}</small><RouterLink v-if="item.entityType==='SHIPMENT'" class="text-link" :to="`/shipments/${item.entityId}`">查看包裹</RouterLink><RouterLink v-if="['AFTERSALE','AFTERSALE_CASE'].includes(item.entityType)" class="text-link" :to="`/aftersales/${item.entityId}`">查看售后</RouterLink></div></li></ol><PagePagination v-if="remote.data.value" :total="remote.data.value.total" :limit="limit" :offset="offset" :loading="remote.loading.value" @change="paginate" /></section></template>
