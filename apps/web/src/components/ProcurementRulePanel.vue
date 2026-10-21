<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { api, asApiError, ApiError } from '../api/client';
import { contract, signedFen, type Evaluation, type Intent, type Policy, type PolicyUpdate } from '../domain/m2';
import { formatFen, shanghaiTime, uuid } from '../domain/m1';
import { useSessionStore } from '../stores/session';
import ErrorPanel from './ErrorPanel.vue';
const props=defineProps<{storeId:string;itemId:string}>();
const session=useSessionStore();
const item=ref(props.itemId);const scope=ref('tenant');const policy=ref<Policy|null>(null);const draft=ref<PolicyUpdate|null>(null);
const loading=ref(false);const policyError=ref<ApiError|null>(null);const pending=ref(false);const policyConfirm=ref(false);const policyNotice=ref('');
const evaluation=ref<Evaluation|null>(null);const evaluating=ref(false);const error=ref<ApiError|null>(null);const confirm=ref(false);const creating=ref(false);const created=ref<Intent|null>(null);
let signature='';let key='';let revision=0;let policySequence=0;
const now=ref(Date.now());
const timer=globalThis.setInterval(()=>{now.value=Date.now();},1000);
onScopeDispose(()=>{globalThis.clearInterval(timer);revision++;policySequence++;});
const policyStore=computed(()=>scope.value==='store'?props.storeId:undefined);
const capture=()=>({revision,itemId:item.value,storeId:props.storeId,policyStore:policyStore.value});
const isCurrent=(context:ReturnType<typeof capture>)=>context.revision===revision&&context.itemId===item.value&&context.storeId===props.storeId&&context.policyStore===policyStore.value;
const canCreate=computed(()=>!pending.value&&evaluation.value?.allowed&&evaluation.value.salesOrderItemId===item.value&&(!props.storeId||evaluation.value.storeId===props.storeId)&&evaluation.value.selectedQuote&&Date.parse(evaluation.value.selectedQuote.expiresAt)>now.value&&!created.value);
const moneyFields=[['maxSingleFen','单笔采购上限（分）'],['maxDailyFen','每日采购上限（分）'],['maxShippingFen','运费上限（分）'],['minProfitFen','最低预计利润（分）'],['platformFeeFen','预计平台费（分）'],['reserveFen','售后预留（分）']] as const;
function invalidateContext(){
  revision++;evaluation.value=null;created.value=null;error.value=null;confirm.value=false;
  evaluating.value=false;creating.value=false;pending.value=false;policyConfirm.value=false;policyNotice.value='';
}
async function loadPolicy(){
  const context=capture();const current=++policySequence;
  policy.value=null;draft.value=null;policyError.value=null;loading.value=false;
  if(!session.can('procurement.manage')||(scope.value==='store'&&!uuid(props.storeId)))return;
  loading.value=true;
  try{
    const result=await api.getPolicy(context.policyStore);
    if(isCurrent(context)&&current===policySequence){policy.value=result;const {mode,...body}=result;void mode;draft.value=body;}
  }catch(cause){if(isCurrent(context)&&current===policySequence)policyError.value=asApiError(cause);}
  finally{if(isCurrent(context)&&current===policySequence)loading.value=false;}
}
watch(()=>props.itemId,value=>{item.value=value;},{flush:'sync'});
watch(()=>[props.storeId,item.value,scope.value],()=>{invalidateContext();void loadPolicy();},{immediate:true,flush:'sync'});
async function evaluate(){
  if(evaluating.value||creating.value||pending.value||!session.can('procurement.manage'))return;
  const context=capture();evaluating.value=true;evaluation.value=null;created.value=null;error.value=null;
  try{
    const result=await api.evaluateProcurement(context.itemId);
    if(!isCurrent(context))return;
    if(result.salesOrderItemId!==context.itemId||(context.storeId&&result.storeId!==context.storeId))throw new ApiError('INVALID_RESPONSE','采购预览与当前店铺或销售子项不一致，请重新选择。');
    evaluation.value=result;
  }catch(cause){if(isCurrent(context))error.value=asApiError(cause);}
  finally{if(isCurrent(context))evaluating.value=false;}
}
async function savePolicy(){
  if(pending.value||creating.value||evaluating.value||!draft.value)return;
  const context=capture();const body={...draft.value};pending.value=true;policyError.value=null;
  try{
    const result=await api.updatePolicy(body,context.policyStore);
    if(!isCurrent(context))return;
    policy.value=result;const {mode,...updated}=result;void mode;draft.value=updated;policyConfirm.value=false;
    policyNotice.value='Mock 采购规则已保存。已确认映射且满足规则的销售子项可由后台自动生成唯一采购意图。';
    evaluation.value=null;confirm.value=false;
  }catch(cause){if(isCurrent(context))policyError.value=asApiError(cause);}
  finally{if(isCurrent(context))pending.value=false;}
}
async function create(){
  if(creating.value||!canCreate.value||!evaluation.value?.selectedQuote)return;
  const context=capture();creating.value=true;error.value=null;
  const body={salesOrderItemId:context.itemId,quoteId:evaluation.value.selectedQuote.id};
  const next=JSON.stringify(body);if(next!==signature){signature=next;key=globalThis.crypto.randomUUID();}
  try{
    const result=await api.createPurchaseIntent(body,key);
    if(isCurrent(context)){created.value=result;confirm.value=false;}
  }catch(cause){if(isCurrent(context))error.value=asApiError(cause);}
  finally{if(isCurrent(context))creating.value=false;}
}
</script>
<template>
  <section v-if="session.can('procurement.manage')" class="panel editor-panel">
    <div class="panel-heading"><div><h2>采购规则与子项预览</h2><p>金额全部按整数分输入 · 规则与支付策略独立 · 仅 Mock</p></div><el-tag type="warning" effect="plain">不执行付款</el-tag></div>
    <form class="inline-form" @submit.prevent="evaluate"><label>销售子项 ID<input v-model="item" name="salesOrderItemId" placeholder="从销售订单详情进入可自动填写" :disabled="evaluating||creating" /></label><el-button data-testid="evaluate-item" native-type="submit" type="primary" :loading="evaluating" :disabled="!uuid(item)||creating||evaluating||pending">预览采购规则</el-button></form>
    <ErrorPanel v-if="error" :error="error" title="采购请求未完成"><RouterLink v-if="error.status===409" class="text-link" to="/purchases?view=intents">查看已存在的采购意图</RouterLink><p v-if="error.status===409" class="help-text">报价或业务版本可能变化；刷新规则预览后再决定，已存在的意图不会重复创建。</p></ErrorPanel>
    <div v-if="evaluation" class="evaluation-result"><h3>{{ evaluation.allowed?'规则允许 · 可生成意图':'规则阻塞 · 不可采购' }}</h3><p>待采购数量 {{ evaluation.qty }} · 子项版本 {{ evaluation.salesItemVersion }} · 销售来源版本 {{ evaluation.salesSourceVersion }}</p><ul v-if="evaluation.reasons.length" class="reason-list"><li v-for="reason in evaluation.reasons" :key="reason.code"><strong>{{ reason.code }}</strong> {{ reason.message }}</li></ul><div v-if="evaluation.selectedQuote" class="quote-summary"><span>选定到手成本 <strong>{{ formatFen(evaluation.selectedQuote.totalFen) }}</strong> CNY</span><span>报价有效至 {{ shanghaiTime(evaluation.selectedQuote.expiresAt) }}（上海）</span></div><div v-if="evaluation.candidates.length" class="table-scroll"><table class="data-table"><thead><tr><th>已确认候选 / 优先级</th><th>规则结果</th><th>到手成本 / 预计利润</th></tr></thead><tbody><tr v-for="candidate in evaluation.candidates" :key="candidate.mappingId"><td class="mono">{{ candidate.offerId }}<small>优先级 {{ candidate.priority }}</small></td><td><strong>{{ candidate.allowed?'允许':'阻塞' }}</strong><ul class="reason-list"><li v-for="reason in candidate.reasons" :key="reason.code">{{ reason.code }} · {{ reason.message }}</li></ul></td><td>{{ candidate.quote?formatFen(candidate.quote.totalFen):'报价不可用' }}<small>预计利润 {{ candidate.estimatedProfitFen===null?'不可用':signedFen(candidate.estimatedProfitFen) }}</small></td></tr></tbody></table></div><p class="help-text">后台也会为符合规则的子项自动生成唯一意图；重复请求返回同一意图，创单成功仍为待付款。</p><el-button data-testid="create-intent" type="primary" :disabled="!canCreate||creating" @click="confirm=true">审核并生成 Mock 采购意图</el-button><p v-if="evaluation.allowed&&!canCreate&&!created" class="help-text field-error">报价已过期或不可用，请重新预览。</p></div>
    <div v-if="created" class="success-note" role="status">已接受 Mock 采购意图，后台异步创单，尚未付款。<RouterLink class="text-link" :to="`/purchase-intents/${created.id}`">查看采购意图状态</RouterLink></div>
    <div class="policy-editor"><div class="panel-heading"><div><h3>采购策略</h3><p>租户与店铺规则共同约束，预览结果包含实际生效快照。</p></div><label>规则范围<select v-model="scope" :disabled="loading||pending"><option value="tenant">当前租户</option><option value="store" :disabled="!uuid(storeId)">当前店铺</option></select></label></div><div v-if="loading" class="data-state" role="status">正在加载采购策略…</div><ErrorPanel v-else-if="policyError" :error="policyError" title="采购策略读取或保存失败"><el-button :disabled="pending" @click="policyConfirm=false;loadPolicy()">刷新最新规则</el-button></ErrorPanel><form v-if="draft" class="policy-form" @submit.prevent="policyConfirm=true"><label v-for="[field,label] in moneyFields" :key="field">{{ label }}<input v-model.number="draft[field]" :name="field" type="number" min="0" :max="field==='maxSingleFen'||field==='maxDailyFen'?1000000000000:1000000000" step="1" required :disabled="pending" /></label><label>目标地区<select v-model="draft.region" :disabled="pending"><option value="CN">CN · 合成中国区域</option></select></label><label class="checkbox-label"><input v-model="draft.enabled" type="checkbox" :disabled="pending" />允许 Mock 自动采购规划</label><el-button native-type="submit" :disabled="pending||creating||evaluating||!contract<PolicyUpdate>('M2PolicyUpdate')(draft)">保存规则（版本 {{ policy?.version }}）</el-button></form><p v-if="policyNotice" class="success-note" role="status">{{ policyNotice }}</p></div>
    <el-dialog v-model="confirm" title="确认生成 Mock 采购意图" width="500px" :close-on-click-modal="false" :close-on-press-escape="!creating" :show-close="!creating"><p>本次到手成本：{{ evaluation?.selectedQuote?formatFen(evaluation.selectedQuote.totalFen):'不可用' }} CNY。</p><p class="help-text">服务端重新核验报价、映射和销售状态后创建唯一意图，由 Worker 异步模拟创单。创单成功仅为待付款；超时将冻结重下单并查询 Mock 权威状态。</p><ErrorPanel v-if="error" :error="error" /><template #footer><el-button :disabled="creating" @click="confirm=false">取消</el-button><el-button data-testid="confirm-intent" type="primary" :disabled="creating||!canCreate" :loading="creating" @click="create">确认生成意图</el-button></template></el-dialog>
    <el-dialog v-model="policyConfirm" title="确认保存 Mock 采购规则" width="480px" :close-on-click-modal="false" :close-on-press-escape="!pending" :show-close="!pending"><p>启用规则后，后台可为已人工确认映射且符合条件的子项生成唯一采购意图。不会执行付款。</p><p class="help-text">请核对本次限额与最低利润，租户和店铺规则共同生效，保存操作记录审计。</p><ErrorPanel v-if="policyError" :error="policyError" /><template #footer><el-button :disabled="pending" @click="policyConfirm=false">取消</el-button><el-button type="primary" :loading="pending" :disabled="pending" @click="savePolicy">确认保存</el-button></template></el-dialog>
  </section>
</template>
