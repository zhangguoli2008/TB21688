<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Document, Refresh, Plus } from '@element-plus/icons-vue';
import { api, asApiError, type ApiError } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useRemote } from '../composables/useRemote';
import { parseOrderFilters, orderQuery, sourceStatuses, statusNames, shanghaiTime, formatFen, type OrderFilters, type Scenario, type ScenarioResult } from '../domain/m1';
import ErrorPanel from '../components/ErrorPanel.vue';
import StatusBadge from '../components/StatusBadge.vue';
import PagePagination from '../components/PagePagination.vue';
const session = useSessionStore();
const route = useRoute();
const router = useRouter();
const filters = computed(() => parseOrderFilters(route.query));
const { data, loading, error, load } = useRemote(() => api.listOrders(filters.value));
const stores = useRemote(() => api.listStores(100, 0));
const form = reactive({ storeId: '', sourceStatus: '', q: '', from: '', to: '' });
const filterError = ref('');
const scenario = reactive({ storeId: '', scenario: 'PAID' as Scenario, externalOrderId: '' });
const scenarioPending = ref(false);
const scenarioError = ref<ApiError | null>(null);
const result = ref<ScenarioResult | null>(null);
let signature = '';
let idem = '';
const eligibleStores = computed(() => stores.data.value?.items.filter(s => s.enabled && s.authStatus === 'ACTIVE') ?? []);
const pageSales = computed(() => formatFen(data.value?.items.reduce((sum, o) => sum + BigInt(o.paidAmountFen), 0n) ?? 0n));
function syncForm(f: OrderFilters) { form.storeId = f.storeId ?? ''; form.sourceStatus = f.sourceStatus ?? ''; form.q = f.q ?? ''; form.from = f.from?.slice(0, 16) ?? ''; form.to = f.to?.slice(0, 16) ?? ''; filterError.value = ''; }
watch(() => route.fullPath, () => { syncForm(filters.value); void load(); }, { immediate: true });
onMounted(() => void stores.load());
function apply() { filterError.value = ''; const from = form.from ? `${form.from}:00Z` : undefined; const to = form.to ? `${form.to}:00Z` : undefined; if (from && to && Date.parse(from) >= Date.parse(to)) {
    filterError.value = '开始时间必须早于结束时间。';
    return;
} const next = parseOrderFilters({ ...form, from, to, limit: String(filters.value.limit), offset: '0' }); void router.push({ path: '/orders', query: orderQuery(next) }); }
function clear() { syncForm({ limit: 20, offset: 0 }); void router.push({ path: '/orders', query: orderQuery({ limit: 20, offset: 0 }) }); }
function paginate(offset: number, limit: number) { void router.push({ path: '/orders', query: orderQuery({ ...filters.value, offset, limit }) }); }
async function simulate() { if (scenarioPending.value || !session.can('orders.simulate'))
    return; scenarioPending.value = true; scenarioError.value = null; result.value = null; const body = { scenario: scenario.scenario, externalOrderId: scenario.externalOrderId.trim() }; const next = JSON.stringify({ storeId: scenario.storeId, ...body }); if (next !== signature) {
    signature = next;
    idem = globalThis.crypto.randomUUID();
} try {
    result.value = await api.simulateOrders(scenario.storeId, body, idem);
}
catch (cause) {
    scenarioError.value = asApiError(cause);
}
finally {
    scenarioPending.value = false;
} }
</script>
<template>
  <div>
    <div class="page-heading"><div><p class="eyebrow">业务管理 / 销售订单</p><h1>订单工作台</h1><p class="muted">查看 Mock 权威状态与销售子项。时间显示：Asia/Shanghai。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新订单</el-button></div>
    <div class="summary-grid orders-summary"><section class="summary-card"><span class="summary-icon"><el-icon><Document /></el-icon></span><div><span class="card-label">匹配销售订单</span><strong>{{ data ? data.total : '—' }} <small>笔</small></strong><p>当前筛选 / 服务端店铺范围</p></div></section><section class="summary-card"><div><span class="card-label">本页销售金额 · CNY</span><strong>{{ data ? pageSales : '—' }}</strong><p>Mock 销售实收合计 · 退款另见来源状态</p></div></section><section class="summary-card"><div><span class="card-label">同步与授权</span><strong>MOCK</strong><p>异步事件入库 · 真实平台待授权</p></div></section></div>
    <section class="panel filter-panel"><form data-testid="apply-filters" class="filter-form" @submit.prevent="apply"><label>店铺<select v-model="form.storeId" name="storeId"><option value="">全部可见店铺</option><option v-for="store in stores.data.value?.items" :key="store.id" :value="store.id">{{ store.displayName }}</option></select></label><label>销售来源状态<select v-model="form.sourceStatus" name="sourceStatus"><option value="">全部状态</option><option v-for="state in sourceStatuses" :key="state" :value="state">{{ statusNames[state] }}</option></select></label><label>平台订单号（精确匹配）<input v-model="form.q" name="q" maxlength="80" placeholder="输入完整外部订单号" /></label><label>来源更新起点（UTC，含）<input v-model="form.from" name="from" type="datetime-local" /></label><label>来源更新终点（UTC，不含）<input v-model="form.to" name="to" type="datetime-local" /></label><div class="filter-actions"><el-button type="primary" native-type="submit">查询</el-button><el-button data-testid="clear-filters" @click="clear">清空</el-button></div></form><p v-if="filterError" class="field-error" role="alert">{{ filterError }}</p><p v-if="stores.data.value && stores.data.value.total>100" class="help-text muted">筛选选择器显示前 100 个可见店铺，可使用含 storeId 的订单链接查看其他店铺。</p><ErrorPanel v-if="stores.error.value" :error="stores.error.value" title="店铺筛选加载失败"><el-button @click="stores.load">重试店铺列表</el-button></ErrorPanel></section>
    <section class="panel table-panel" :aria-busy="loading"><div v-if="loading" data-testid="orders-loading" class="data-state" role="status"><el-skeleton :rows="4" animated /><p>正在加载销售订单…</p></div><ErrorPanel v-else-if="error" :error="error" :title="error.status===403?'无权查看销售订单':'订单加载失败'"><el-button @click="load">重试</el-button></ErrorPanel><div v-else-if="data&&!data.items.length" data-testid="orders-empty" class="data-state"><el-icon class="empty-icon"><Document /></el-icon><h2>暂无匹配订单</h2><p>调整筛选条件，或生成 Mock 订单后手动刷新。</p></div><div v-else-if="data" class="table-scroll"><table class="data-table"><thead><tr><th>平台订单号 / 店铺</th><th>销售来源状态</th><th>本地销售状态</th><th>销售实收</th><th>来源更新（上海）</th><th>操作</th></tr></thead><tbody><tr v-for="order in data.items" :key="order.id"><td><RouterLink class="text-link mono" :to="{path:`/orders/${order.id}`,query:route.query}">{{ order.externalOrderId }}</RouterLink><small>{{ order.storeName }}</small></td><td><StatusBadge :status="order.sourceStatus" /></td><td><StatusBadge :status="order.localStatus" /></td><td class="money-cell">{{ formatFen(order.paidAmountFen) }}<small>CNY · Mock</small></td><td class="time-cell">{{ shanghaiTime(order.sourceUpdatedAt) }}<small>来源版本 {{ order.sourceVersion }}</small></td><td><RouterLink class="text-link" :to="{path:`/orders/${order.id}`,query:route.query}">查看详情</RouterLink></td></tr></tbody></table></div><PagePagination v-if="data" :total="data.total" :limit="filters.limit" :offset="filters.offset" :loading="loading" @change="paginate" /></section>
    <section v-if="session.can('orders.simulate')" class="panel scenario-panel"><div class="panel-heading"><div><h2><el-icon><Plus /></el-icon> 生成 Mock 销售订单</h2><p>仅店主演示 · 合成事件通过后台 Worker 异步处理。</p></div><el-tag type="warning" effect="plain">无真实付款</el-tag></div><form data-testid="scenario-form" class="scenario-form" @submit.prevent="simulate"><label>已启用且授权有效的店铺<select v-model="scenario.storeId" required :disabled="scenarioPending"><option value="" disabled>请选择 Mock 店铺</option><option v-for="store in eligibleStores" :key="store.id" :value="store.id">{{ store.displayName }}</option></select></label><label>外部订单号<input v-model="scenario.externalOrderId" maxlength="80" pattern="[A-Za-z0-9_-]+" placeholder="例如 demo_order_001" required :disabled="scenarioPending" /></label><label>模拟场景<select v-model="scenario.scenario" :disabled="scenarioPending"><option value="PAID">PAID · 模拟已付款</option><option value="DUPLICATE">DUPLICATE · 重复事件</option><option value="OUT_OF_ORDER">OUT_OF_ORDER · 乱序事件</option><option value="DELAYED">DELAYED · 延迟事件</option><option value="REFUNDED">REFUNDED · 模拟已退款</option></select></label><el-button type="primary" native-type="submit" :loading="scenarioPending" :disabled="scenarioPending||!scenario.storeId||!scenario.externalOrderId.trim()">提交模拟事件</el-button></form><ErrorPanel v-if="scenarioError" :error="scenarioError" title="模拟事件提交未确认" /><div v-if="result" class="success-note" role="status">已接受 {{ result.acceptedEvents }} 个 Mock 事件，订单号 {{ result.externalOrderId }}。后台异步处理中，延迟场景至少等待 2 秒。<el-button link type="primary" :loading="loading" @click="load">刷新查看结果</el-button><p>模拟状态不代表真实平台收款、退款或采购。</p></div><p v-if="!stores.loading.value&&!eligibleStores.length" class="help-text muted">暂无可模拟的店铺，请先连接店铺并启用 Mock 授权。</p></section>
    <p class="scope-note">M1 仅销售订单闭环 · SKU 映射、采购、支付与物流仍待后续里程碑。</p>
  </div>
</template>
