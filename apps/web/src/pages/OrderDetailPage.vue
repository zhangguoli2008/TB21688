<script setup lang="ts">
import { ref,watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, Clock } from '@element-plus/icons-vue';
import { api } from '../api/client';
import { useSessionStore } from '../stores/session';
const session = useSessionStore();
import { useRemote } from '../composables/useRemote';
import { shanghaiTime, formatFen, platformName } from '../domain/m1';
import OrderActivityTimeline from '../components/OrderActivityTimeline.vue';
import M4EventGenerator from '../components/M4EventGenerator.vue';
import ErrorPanel from '../components/ErrorPanel.vue';
import StatusBadge from '../components/StatusBadge.vue';
const timelineLabels = new Map([
    ['AUTHORITY_APPLIED', '已重查并同步 Mock 权威状态'],
    ['AUTHORITY_UNCHANGED', 'Mock 权威状态无变更'],
    ['STALE_AUTHORITY_IGNORED', '已忽略旧的 Mock 权威版本'],
]);
const timelineLabel = (action: string) => timelineLabels.get(action) ?? '已记录 Mock 订单同步事件';
const notice=ref('');
function eventAccepted(){notice.value='Mock 事件已受理，等待后台独立核验。';void load();}
const route = useRoute();
const { data, loading, error, load } = useRemote(() => api.getOrder(String(route.params.id)));
watch(() => route.params.id, () => {notice.value='';void load();}, { immediate: true });
</script>
<template><div><div class="page-heading"><div><p class="eyebrow">订单工作台 / 销售订单详情</p><h1>销售订单详情</h1><p class="muted">Mock 合成数据 · 时间显示：Asia/Shanghai</p></div><div class="inline-actions"><RouterLink :to="{path:'/orders',query:route.query}"><el-button>返回订单列表</el-button></RouterLink><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div></div><p v-if="notice" class="success-note" role="status">{{ notice }}</p><div v-if="loading" class="panel data-state" role="status" aria-busy="true"><el-skeleton :rows="6" animated /><p>正在加载订单详情…</p></div><ErrorPanel v-else-if="error" :error="error" :title="error.status===404?'订单不存在或不在当前访问范围':error.status===403?'无权查看订单详情':'订单详情加载失败'"><el-button @click="load">重试</el-button></ErrorPanel><template v-else-if="data"><section class="panel detail-panel"><div class="panel-heading"><div><h2 class="mono">{{ data.externalOrderId }}</h2><p>{{ data.storeName }} · {{ platformName(data.platform) }}</p></div><el-tag effect="plain" type="warning">MOCK</el-tag></div><dl class="order-facts"><div><dt>销售来源状态</dt><dd><StatusBadge :status="data.sourceStatus" /></dd></div><div><dt>本地销售状态</dt><dd><StatusBadge :status="data.localStatus" /></dd></div><div><dt>销售实收（CNY）</dt><dd class="detail-money">{{ formatFen(data.paidAmountFen) }}</dd></div><div><dt>模拟付款时间</dt><dd>{{ shanghaiTime(data.paidAt) }}</dd></div><div><dt>承诺发货时间</dt><dd>{{ shanghaiTime(data.promisedShipAt) }}</dd></div><div><dt>来源更新时间</dt><dd>{{ shanghaiTime(data.sourceUpdatedAt) }}</dd></div><div><dt>入库时间</dt><dd>{{ shanghaiTime(data.createdAt) }}</dd></div><div><dt>来源 / 本地版本</dt><dd>{{ data.sourceVersion }} / {{ data.version }}</dd></div></dl><p class="help-text muted">Mock 付款状态仅用于销售订单演示，真实收款、采购与支付通道待授权。</p></section><section class="panel table-panel"><div class="section-title"><h2>销售子项</h2><p>查看销售来源的数量、金额分摊及取消 / 履约进度；本地 Mock 回填确认请查看独立物流记录。</p></div><div class="table-scroll"><table class="data-table"><thead><tr><th>商品</th><th>外部商品 / SKU</th><th>数量</th><th>分摊销售金额</th><th>来源已取消 / 已履约数量</th><th v-if="session.can('sourcing.manage')">采购审核</th></tr></thead><tbody><tr v-for="item in data.items" :key="item.id"><td>{{ item.title }}</td><td class="mono">{{ item.externalItemId }}<small>{{ item.externalSkuId }}</small></td><td>{{ item.qty }}</td><td class="money-cell">{{ formatFen(item.paidAllocatedFen) }}</td><td>{{ item.cancelledQty }} / {{ item.fulfilledQty }}</td><td v-if="session.can('sourcing.manage')"><RouterLink data-testid="source-item" class="text-link" :to="{path:'/catalog/sourcing',query:{storeId:data.storeId,salesSkuId:item.externalSkuId,itemId:item.id}}">核验货源 / 采购预览</RouterLink></td></tr></tbody></table><p v-if="!data.items.length" class="data-state">暂无销售子项。</p></div></section><section class="panel timeline-panel"><h2>销售订单时间轴</h2><p class="muted help-text">最近 100 条同步记录 · 来源版本与记录时间</p><ol v-if="data.timeline.length" class="event-timeline"><li v-for="entry in data.timeline" :key="entry.id"><el-icon aria-hidden="true"><Clock /></el-icon><div><strong>{{ timelineLabel(entry.action) }}</strong><p>{{ shanghaiTime(entry.occurredAt) }} · 来源版本 {{ entry.sourceVersion }}</p><small class="mono">事件 {{ entry.eventId }}</small></div></li></ol><p v-else class="data-state">暂无时间轴记录。</p></section><section v-if="session.can('logistics.read')||session.can('aftersales.read')" class="panel detail-panel"><h2>独立物流与售后记录</h2><div class="inline-actions"><RouterLink v-if="session.can('logistics.read')" class="text-link" :to="{path:'/shipments',query:{salesOrderId:data.id}}">查看订单全部包裹</RouterLink><RouterLink v-if="session.can('aftersales.read')" class="text-link" :to="{path:'/aftersales',query:{salesOrderId:data.id}}">查看订单售后</RouterLink></div></section><M4EventGenerator v-if="session.can('aftersales.simulate')" kind="source" :target-id="data.id" :items="data.items" @done="eventAccepted" /><OrderActivityTimeline :order-id="data.id" /></template></div></template>
