<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, asApiError, type ApiError } from '../api/client';
import { useSessionStore } from '../stores/session';
import { frozen, type PaymentView } from '../domain/m3';
import { formatFen } from '../domain/m1';
import ErrorPanel from './ErrorPanel.vue';
const props=defineProps<{payment:PaymentView}>();
const emit=defineEmits<{updated:[value:PaymentView]}>();
const session=useSessionStore();const route=useRoute();const router=useRouter();
const pending=ref(false);const error=ref<ApiError|null>(null);const notice=ref('');const confirmation=ref<'checkout'|'request'|null>(null);
let revision=0;const keys=new Map<string,string>();
const startable=computed(()=>props.payment.purchaseState==='AWAITING_PAYMENT'&&['WAIT_MERCHANT_PAYMENT','BLOCKED'].includes(props.payment.state));
const context=()=>`${route.fullPath}:${session.user?.currentTenantId}:${session.user?.userId}:${props.payment.purchaseId}`;
watch(context,()=>{revision++;pending.value=false;confirmation.value=null;error.value=null;notice.value='';keys.clear();},{flush:'sync'});
onScopeDispose(()=>revision++);
function closeDialog(value:boolean){if(!value&&!pending.value)confirmation.value=null;}
async function execute(action:'checkout'|'request'|'reconcile'){
 if(pending.value||(action==='checkout'&&!session.can('payments.manual'))||(action!=='checkout'&&!session.can('payments.manage')))return;
 if(action!=='reconcile'&&(!startable.value||frozen(props.payment.state)))return;
 if(action==='reconcile'&&!props.payment.attempt)return;
 const current=revision;const purchaseId=props.payment.purchaseId;pending.value=true;error.value=null;notice.value='';
 if(!keys.has(action))keys.set(action,globalThis.crypto.randomUUID());
 try{
  if(action==='checkout'){
   const entry=await api.getCheckoutEntry(purchaseId,keys.get(action)!);
   if(current===revision){confirmation.value=null;await router.push(entry.url);}
  }else{
   const value=action==='request'?await api.requestPayment(purchaseId,keys.get(action)!):await api.reconcilePayment(purchaseId,keys.get(action)!);
   if(current!==revision)return;
   if(value.purchaseId!==purchaseId)throw new Error('Payment context mismatch');
   emit('updated',value);confirmation.value=null;
   notice.value=action==='reconcile'?'已排入只读查单；不会再次发起付款。请刷新权威状态。':'已接受 Mock 付款编排；后台核验后处理，请刷新状态。';
   keys.delete(action);
  }
 }catch(cause){if(current===revision)error.value=asApiError(cause);}
 finally{if(current===revision)pending.value=false;}
}
</script>
<template>
  <div class="payment-actions">
    <p v-if="frozen(payment.state)" class="payment-freeze" role="status">正在核实，请勿再次付款。{{ payment.state==='MANUAL_RECONCILIATION'?'自动查询已耗尽，需人工核对；仍禁止重付。':'结果未知，所有重新付款入口已冻结。' }}</p>
    <p v-if="payment.state==='FAILED'" class="payment-freeze" role="status">该付款尝试已明确失败，本阶段不提供第二次付款尝试。可只读查单并核对对账记录；存在差异时需人工核对。</p>
    <div class="row-actions">
      <el-button v-if="startable&&session.can('payments.manual')" data-testid="open-checkout" size="small" :disabled="pending" @click="confirmation='checkout'">打开本地 Mock 付款页</el-button>
      <el-button v-if="startable&&session.can('payments.manage')" data-testid="request-payment" size="small" :disabled="pending" @click="confirmation='request'">按策略编排 Mock 付款</el-button>
      <el-button v-if="payment.attempt&&session.can('payments.manage')" data-testid="reconcile-payment" size="small" :loading="pending&&confirmation===null" :disabled="pending" @click="execute('reconcile')">只读查单</el-button>
    </div>
    <p v-if="notice" class="help-text" role="status">{{ notice }}</p>
    <ErrorPanel v-if="error&&!confirmation" :error="error" title="付款请求未完成" />
    <el-dialog :model-value="confirmation!==null" :title="confirmation==='checkout'?'确认打开本地 Mock 付款页':'确认按策略编排 Mock 付款'" width="500px" :close-on-click-modal="false" :close-on-press-escape="!pending" :show-close="!pending" @update:model-value="closeDialog">
      <p>采购金额 {{ formatFen(payment.amountFen) }} CNY · 合成 Mock 交易。</p>
      <p class="help-text">{{ confirmation==='checkout'?'服务端重新校验后创建唯一人工付款尝试和预算预占。页面仅模拟回执，后台仍需独立查验权威状态。':'服务端按当前 MANUAL / AUTO / RULE_BASED 策略和门禁异步处理。未签约、暂停或规则阻塞不会静默付款。' }}</p>
      <p class="help-text mono">采购单 {{ payment.purchaseId }}</p>
      <ErrorPanel v-if="error" :error="error"><p v-if="error.status===409" class="help-text">状态或版本可能变化，请关闭弹窗并刷新核对。</p></ErrorPanel>
      <template #footer><el-button :disabled="pending" @click="confirmation=null">取消</el-button><el-button data-testid="confirm-payment-action" type="primary" :loading="pending" :disabled="pending||!startable" @click="execute(confirmation==='checkout'?'checkout':'request')">确认继续</el-button></template>
    </el-dialog>
  </div>
</template>
