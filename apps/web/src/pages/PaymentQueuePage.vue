<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, Wallet } from '@element-plus/icons-vue';
import { api } from '../api/client';
import { useRemote } from '../composables/useRemote';
import { formatFen, shanghaiTime } from '../domain/m1';
import { signedFen } from '../domain/m2';
import { parsePaymentFilters, paymentQuery, paymentStates, paymentStateNames, type PaymentView } from '../domain/m3';
import ErrorPanel from '../components/ErrorPanel.vue';
import PagePagination from '../components/PagePagination.vue';
import StatusBadge from '../components/StatusBadge.vue';
import PaymentActions from '../components/PaymentActions.vue';
import PaymentNavigation from '../components/PaymentNavigation.vue';
const route=useRoute();const router=useRouter();const filters=computed(()=>parsePaymentFilters(route.query));const form=reactive({storeId:'',state:''});
const queue=useRemote(()=>api.listPayments(filters.value));const stores=useRemote(()=>api.listStores(100,0));
watch(()=>route.fullPath,()=>{form.storeId=filters.value.storeId??'';form.state=filters.value.state??'';void queue.load();},{immediate:true});void stores.load();
function apply(){void router.push({path:'/payments/queue',query:paymentQuery({...filters.value,...form,offset:0})});}
function paginate(offset:number,limit:number){void router.push({path:'/payments/queue',query:paymentQuery({...filters.value,offset,limit})});}
function update(value:PaymentView){if(queue.data.value)queue.data.value={...queue.data.value,items:queue.data.value.items.map(item=>item.purchaseId===value.purchaseId?value:item)};}
</script>
<template>
  <div>
    <div class="page-heading"><div><p class="eyebrow">支付中心 / Mock</p><h1>付款队列</h1><p class="muted">销售、预计成本与权威实付分别记录 · Mock / 待授权</p></div><el-button :icon="Refresh" :loading="queue.loading.value" @click="queue.load">刷新权威状态</el-button></div><PaymentNavigation /><p class="scope-hint">真实支付通道关闭。超时进入 UNKNOWN 并冻结重付，后台仅查询权威状态；本页不会访问真实收银台。</p>
    <section class="panel filter-panel"><form data-testid="payment-filters" class="filter-form" @submit.prevent="apply"><label>店铺<select v-model="form.storeId" name="storeId"><option value="">全部可见店铺</option><option v-for="store in stores.data.value?.items" :key="store.id" :value="store.id">{{ store.displayName }}</option></select></label><label>付款状态<select v-model="form.state" name="state"><option value="">全部状态</option><option v-for="state in paymentStates" :key="state" :value="state">{{ paymentStateNames[state] }}</option></select></label><div class="filter-actions"><el-button native-type="submit" type="primary">查询</el-button></div></form><ErrorPanel v-if="stores.error.value" :error="stores.error.value" title="店铺筛选读取失败"><el-button @click="stores.load">重试店铺</el-button></ErrorPanel><p v-if="stores.data.value&&stores.data.value.total>100" class="help-text muted">选择器显示前 100 个可见店铺，可通过含 storeId 的链接查看其他店铺。</p></section>
    <section class="panel table-panel" :aria-busy="queue.loading.value"><div v-if="queue.loading.value" class="data-state" role="status"><el-skeleton :rows="5" animated /><p>正在读取 Mock 付款队列…</p></div><ErrorPanel v-else-if="queue.error.value" :error="queue.error.value" title="付款队列读取失败"><el-button @click="queue.load">重试</el-button></ErrorPanel><div v-else-if="queue.data.value&&!queue.data.value.items.length" data-testid="payments-empty" class="data-state"><el-icon class="empty-icon"><Wallet /></el-icon><h2>暂无匹配付款记录</h2><p>创建 Mock 采购后可查看付款状态；调整筛选不会创建交易。</p></div><div v-else-if="queue.data.value" class="table-scroll"><table class="data-table payment-table"><thead><tr><th>采购单 / 店铺</th><th>付款状态 / 决策</th><th>销售 / 预计成本 / 利润</th><th>Mock 权威实付</th><th>操作 / 最近更新</th></tr></thead><tbody><tr v-for="payment in queue.data.value.items" :key="payment.purchaseId"><td><RouterLink class="text-link mono" :to="`/purchases/${payment.purchaseId}`">{{ payment.purchaseId }}</RouterLink><small>{{ stores.data.value?.items.find(store=>store.id===payment.storeId)?.displayName??payment.storeId }}</small><small class="mono">{{ payment.externalOrderId??'尚无 Mock 外部单号' }}</small></td><td><StatusBadge kind="payment" :status="payment.state" /><small class="mono">{{ payment.state }} · {{ payment.decisionCode??'无阻塞原因' }}</small></td><td class="money-cell">{{ formatFen(payment.salesAllocatedFen) }}<small>预计成本 {{ formatFen(payment.amountFen) }}</small><small>预计利润 {{ signedFen(payment.estimatedProfitFen) }} · CNY</small></td><td class="money-cell">{{ formatFen(payment.paidFen) }}<small>{{ payment.state==='PAID'?'已核验合成权威流水':'以后台独立查单为准' }}</small></td><td><PaymentActions :payment="payment" @updated="update" /><small class="time-cell">{{ shanghaiTime(payment.updatedAt) }}（上海）</small></td></tr></tbody></table></div><PagePagination v-if="queue.data.value" :total="queue.data.value.total" :limit="filters.limit" :offset="filters.offset" :loading="queue.loading.value" @change="paginate" /></section>
  </div>
</template>
