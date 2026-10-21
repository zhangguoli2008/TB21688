import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { createAppRouter } from '../router';
import { useSessionStore } from '../stores/session';
import { owner, support, json, apiError } from './fixtures';
import { store, order } from './m1-fixtures';
import { intent, purchase } from './m2-fixtures';
import { payment, paymentPolicy, mandate, attempt, checkout, mockCheckout } from './m3-fixtures';
const m3Owner={...owner,permissions:[...owner.permissions,'payments.read','payments.manual','payments.manage','paymentPolicy.manage']};
const m3Support={...support,permissions:[...support.permissions,'payments.read']};
const wrappers:VueWrapper[]=[];
const list=(items:unknown[],total=items.length)=>json({items,total,limit:20,offset:0});
const configuration={purchaseId:purchase.id,behavior:'NORMAL',version:0,runtimeMode:'MOCK'};
async function setup(path:string,persona=m3Owner){
 const pinia=createPinia();setActivePinia(pinia);const session=useSessionStore();session.user=persona;session.checked=true;
 const router=createAppRouter(createMemoryHistory());await router.push(path);await router.isReady();
 const wrapper=mount({template:'<RouterView />'},{global:{plugins:[pinia,router,ElementPlus]}});wrappers.push(wrapper);await flushPromises();return {wrapper,router,session};
}
async function response(path:string){
 if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});
 if(path.includes('/stores?'))return list([store]);
 if(path.includes('/payment-queue?'))return list([payment]);
 if(path.endsWith('/payment-policy'))return json(paymentPolicy);
 if(path.includes('/payment-mandates?'))return list([mandate]);
 if(path.endsWith('/mock-checkout'))return json(mockCheckout);
 if(path.endsWith('/payment-status'))return json(payment);
 if(path.endsWith('/payment-config'))return json(configuration);
 if(path.includes('/payment-reconciliations?'))return list([]);
 if(path.includes('/purchase-intents/'))return json(intent);
 if(path.includes(`/purchases/${purchase.id}`))return json(purchase);
 return list([]);
}
beforeEach(()=>{api.clearCsrf();vi.stubGlobal('fetch',vi.fn(response));});
afterEach(()=>{wrappers.splice(0).forEach(wrapper=>wrapper.unmount());vi.unstubAllGlobals();document.body.innerHTML='';});

describe('M3 payment route and queue behavior',()=>{
 it('denies direct policy access for support and exposes read-only scoped queue navigation',async()=>{
  const {wrapper,router}=await setup('/payments/policies',m3Support);expect(router.currentRoute.value.path).toBe('/403');
  await router.push('/payments/queue');await flushPromises();expect(wrapper.text()).toContain('付款队列');expect(wrapper.text()).toContain(purchase.id);
  expect(wrapper.find('[data-testid="open-checkout"]').exists()).toBe(false);expect(wrapper.find('[data-testid="request-payment"]').exists()).toBe(false);expect(wrapper.find('[data-testid="reconcile-payment"]').exists()).toBe(false);
 });
 it('freezes UNKNOWN payment actions and queues only read-only reconciliation',async()=>{
  const writes:string[]=[];const unknown={...payment,state:'UNKNOWN',attempt:{...attempt,state:'UNKNOWN'}};
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.includes('/payment-queue?'))return list([unknown]);if(options.method==='POST'&&!path.endsWith('/csrf')){writes.push(path);return json(unknown,202);}return response(path);}));
  const {wrapper}=await setup('/payments/queue');expect(wrapper.text()).toContain('正在核实，请勿再次付款');expect(wrapper.find('[data-testid="open-checkout"]').exists()).toBe(false);expect(wrapper.find('[data-testid="request-payment"]').exists()).toBe(false);
  await wrapper.get('[data-testid="reconcile-payment"]').trigger('click');await flushPromises();expect(writes).toEqual([`/api/v1/purchases/${purchase.id}/payment-reconcile`]);expect(wrapper.text()).toContain('已排入只读查单');expect(wrapper.text()).toContain('UNKNOWN');
 });
 it('syncs allowed queue filters and pagination in URL without confusing errors and emptiness',async()=>{
  let fail=true;vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/payment-queue?')?(fail?apiError(503,'QUEUE_UNAVAILABLE'):list([],42)):response(path)));
  const {wrapper,router}=await setup('/payments/queue');expect(wrapper.text()).toContain('QUEUE_UNAVAILABLE');expect(wrapper.find('[data-testid="payments-empty"]').exists()).toBe(false);
  fail=false;await wrapper.get('[name="state"]').setValue('MANUAL_RECONCILIATION');await wrapper.get('[name="storeId"]').setValue(store.id);await wrapper.get('[data-testid="payment-filters"]').trigger('submit');await flushPromises();
  expect(router.currentRoute.value.query).toMatchObject({state:'MANUAL_RECONCILIATION',storeId:store.id,offset:'0'});expect(wrapper.find('[data-testid="payments-empty"]').exists()).toBe(true);
  await wrapper.findAll('button').find(button=>button.text()==='下一页')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query.offset).toBe('20');
 });
 it('requires checkout confirmation, disables double clicks and preserves its key after ambiguous retry',async()=>{
  const writes:RequestInit[]=[];let finish!:(value:Response)=>void;
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/checkout-entry')){writes.push(options);if(writes.length===1)return new Promise<Response>(resolve=>{finish=resolve;});return apiError(503,'PAYMENT_UNAVAILABLE');}return response(path);}));
  const {wrapper}=await setup('/payments/queue');await wrapper.get('[data-testid="open-checkout"]').trigger('click');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-payment-action"]').trigger('click');await flushPromises();await wrapper.get('[data-testid="confirm-payment-action"]').trigger('click');expect(writes).toHaveLength(1);
  finish(apiError(503,'PAYMENT_UNAVAILABLE'));await flushPromises();await wrapper.get('[data-testid="confirm-payment-action"]').trigger('click');await flushPromises();expect(writes).toHaveLength(2);expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBe(new Headers(writes[1]!.headers).get('Idempotency-Key'));
 });
 it('discards pending checkout navigation when queue context changes',async()=>{
  let finish!:(value:Response)=>void;vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/checkout-entry')?new Promise<Response>(resolve=>{finish=resolve;}):response(path)));
  const {wrapper,router}=await setup('/payments/queue');await wrapper.get('[data-testid="open-checkout"]').trigger('click');await wrapper.get('[data-testid="confirm-payment-action"]').trigger('click');await flushPromises();await router.push('/payments/queue?state=PAID');await flushPromises();finish(json(checkout));await flushPromises();expect(router.currentRoute.value.fullPath).toBe('/payments/queue?state=PAID');expect(wrapper.find('[data-testid="confirm-payment-action"]').exists()).toBe(false);
 });
});

describe('M3 policy and mandate changes',()=>{
 it('validates integer fen and confirms policy version before PUT',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/payment-policy')&&options.method==='PUT'){writes.push(options);return json({...paymentPolicy,mode:'RULE_BASED',maxSingleFen:15000,isPaused:false,version:1});}return response(path);}));
  const {wrapper}=await setup('/payments/policies');expect(wrapper.text()).toContain('仅 Mock 可测试');expect(wrapper.text()).toContain('UTC');
  await wrapper.get('[name="maxSingleFen"]').setValue('1.5');expect(wrapper.get('[data-testid="save-payment-policy"]').attributes('disabled')).toBeDefined();
  await wrapper.get('[name="maxSingleFen"]').setValue('15000');await wrapper.get('[name="mode"]').setValue('RULE_BASED');await wrapper.get('[name="isPaused"]').setValue(false);await wrapper.get('[data-testid="payment-policy-form"]').trigger('submit');expect(writes).toHaveLength(0);
  await wrapper.get('[data-testid="confirm-payment-policy"]').trigger('click');await flushPromises();expect(writes).toHaveLength(1);expect(JSON.parse(writes[0]!.body as string)).toEqual({...paymentPolicy,globalPaused:undefined,usedDailyFen:undefined,runtimeMode:undefined,mode:'RULE_BASED',maxSingleFen:15000,isPaused:false});expect(wrapper.text()).toContain('当前版本 1');
 });
 it('requires confirmed Mock mandate changes with CAS and hides management from support',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/mock-state')){writes.push(options);return json({...mandate,status:'ACTIVE',version:1,expiresAt:'2026-11-21T00:00:00Z'});}return response(path);}));
  const {wrapper}=await setup('/payments/mandates');await wrapper.get('[data-testid="edit-mandate"]').trigger('click');await wrapper.get('[name="mandateStatus"]').setValue('ACTIVE');await wrapper.get('[data-testid="mandate-form"]').trigger('submit');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-mandate"]').trigger('click');await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toEqual({status:'ACTIVE',maxSingleFen:50000,version:0});expect(wrapper.text()).toContain('Mock 签约已生效');
  const other=await setup('/payments/mandates',m3Support);expect(other.wrapper.find('[data-testid="edit-mandate"]').exists()).toBe(false);
 });
});

describe('M3 authenticated local Mock checkout',()=>{
 it('keeps UNPAID in the waiting queue and never marks paid from a receipt click',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/receipt')){writes.push(options);return json({...payment,attempt});}return response(path);}));
  const {wrapper}=await setup(`/payments/mock/${attempt.id}`);expect(wrapper.text()).toContain('本地 Mock 回执模拟器');expect(wrapper.text()).toContain('¥55.00');expect(wrapper.find('input[type="password"]').exists()).toBe(false);
  await wrapper.get('[name="receiptOutcome"]').setValue('UNPAID');await wrapper.get('.inline-form').trigger('submit');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-receipt"]').trigger('click');await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toEqual({outcome:'UNPAID'});expect(wrapper.text()).toContain('待商家付款');expect(wrapper.text()).toContain('回执仅作为线索');expect(wrapper.text()).not.toContain('Mock 权威已付');
 });
 it('hides all receipt actions for an unknown attempt',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/mock-checkout')?json({...mockCheckout,attempt:{...attempt,state:'UNKNOWN'},canComplete:false}):response(path)));
  const {wrapper}=await setup(`/payments/mock/${attempt.id}`);expect(wrapper.text()).toContain('正在核实，请勿再次付款');expect(wrapper.find('[data-testid="simulate-receipt"]').exists()).toBe(false);
 });
 it('does not apply a receipt response after switching to another attempt',async()=>{
  const otherId='a5000000-0000-4000-8000-000000000002';let finish!:(value:Response)=>void;
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>{if(path.endsWith('/receipt'))return new Promise<Response>(resolve=>{finish=resolve;});if(path.includes(otherId))return json({...mockCheckout,attempt:{...attempt,id:otherId}});return response(path);}));
  const {wrapper,router}=await setup(`/payments/mock/${attempt.id}`);await wrapper.get('.inline-form').trigger('submit');await wrapper.get('[data-testid="confirm-receipt"]').trigger('click');await flushPromises();await router.push(`/payments/mock/${otherId}`);await flushPromises();finish(json({...payment,state:'PAID',paidFen:5500,attempt:{...attempt,state:'PAID'}}));await flushPromises();expect(wrapper.text()).toContain(otherId);expect(wrapper.text()).not.toContain('Mock 权威已付');expect(wrapper.find('[data-testid="confirm-receipt"]').exists()).toBe(false);
 });
});

describe('M3 financial details and safe reconciliation records',()=>{
 it('shows separate estimated and verified amounts and confirms fault config CAS',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/payment-config')&&options.method==='PATCH'){writes.push(options);return json({...configuration,behavior:'PAY_TIMEOUT_AFTER',version:1});}return response(path);}));
  const {wrapper}=await setup(`/purchases/${purchase.id}`);expect(wrapper.text()).toContain('销售分摊金额');expect(wrapper.text()).toContain('¥86.00');expect(wrapper.text()).toContain('预计利润');expect(wrapper.text()).toContain('¥31.00');expect(wrapper.text()).toContain('Mock 权威实付');expect(wrapper.text()).toContain('UTC 日占用');
  await wrapper.get('[name="paymentBehavior"]').setValue('PAY_TIMEOUT_AFTER');await wrapper.get('[data-testid="payment-config-form"]').trigger('submit');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-payment-config"]').trigger('click');await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toEqual({behavior:'PAY_TIMEOUT_AFTER',version:0});
 });
 it('shows safe reconciliation summaries with pagination and no management controls for support',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/payment-reconciliations?')?list([{id:'a7000000-0000-4000-8000-000000000001',purchaseId:purchase.id,attemptId:attempt.id,outcome:'MISMATCH',localState:'UNKNOWN',createdAt:'2026-10-21T00:00:00Z',runtimeMode:'MOCK'}]):response(path)));
  const {wrapper}=await setup('/payments/reconciliations',m3Support);expect(wrapper.text()).toContain('权威数据不一致');expect(wrapper.text()).toContain('2026/10/21');expect(wrapper.text()).toContain('08:00');expect(wrapper.text()).toContain(purchase.id);
 });
});


describe('M3 guard and shared-label regressions',()=>{
 it('does not read payment finances without payments.read',async()=>{
  const paths:string[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string)=>{paths.push(path);return response(path);}));
  const {wrapper}=await setup(`/purchases/${purchase.id}`,support);expect(wrapper.text()).toContain('采购订单详情');expect(paths.some(path=>path.endsWith('/payment-status'))).toBe(false);expect(paths.some(path=>path.endsWith('/payment-policy'))).toBe(false);expect(paths.some(path=>path.endsWith('/payment-config'))).toBe(false);
 });
 it('denies support direct access to the manual Mock checkout',async()=>{
  const {router}=await setup(`/payments/mock/${attempt.id}`,m3Support);expect(router.currentRoute.value.path).toBe('/403');
 });
 it('hides new payment actions when purchase creation has not been verified',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/payment-queue?')?list([{...payment,purchaseState:'CREATE_UNKNOWN',state:'BLOCKED',decisionCode:'PURCHASE_NOT_PAYABLE'}]):response(path)));
  const {wrapper}=await setup('/payments/queue');expect(wrapper.text()).toContain('PURCHASE_NOT_PAYABLE');expect(wrapper.find('[data-testid="open-checkout"]').exists()).toBe(false);expect(wrapper.find('[data-testid="request-payment"]').exists()).toBe(false);
 });
 it('keeps sales source and local status labels separate from payment attempts',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes(`/orders/${order.id}`)?json({...order,sourceStatus:'PAID',localStatus:'NEW',items:[],timeline:[]}):response(path)));
  const {wrapper}=await setup(`/orders/${order.id}`);expect(wrapper.text()).toContain('已付款（Mock）');expect(wrapper.text()).toContain('新订单');
 });
});

describe('M3 reviewed terminal and authority-wait behavior',()=>{
 it('keeps failed attempts terminal while allowing only read-only reconciliation',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/payment-queue?')?list([{...payment,state:'FAILED',attempt:{...attempt,state:'FAILED'}}]):response(path)));
  const {wrapper}=await setup('/payments/queue');expect(wrapper.find('[data-testid="open-checkout"]').exists()).toBe(false);expect(wrapper.find('[data-testid="request-payment"]').exists()).toBe(false);expect(wrapper.get('[data-testid="reconcile-payment"]').text()).toBe('只读查单');expect(wrapper.text()).toContain('该付款尝试已明确失败');expect(wrapper.text()).toContain('不提供第二次付款尝试');
 });
 it('shows a frozen authority-wait state and safe navigation when receipt verification is pending',async()=>{
  let submitted=false;vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
   if(path.endsWith('/receipt')){submitted=true;return json({...payment,state:'PAYING',attempt:{...attempt,state:'PAYING'}});}
   if(path.endsWith('/mock-checkout')&&submitted)return apiError(409,'PAYMENT_STATUS_UNKNOWN');
   return response(path);
  }));
  const {wrapper}=await setup(`/payments/mock/${attempt.id}`);await wrapper.get('.inline-form').trigger('submit');await wrapper.get('[data-testid="confirm-receipt"]').trigger('click');await flushPromises();
  const frozen=wrapper.get('[data-testid="mock-checkout-frozen"]');expect(frozen.text()).toContain('正在核实，请勿再次付款');expect(frozen.text()).toContain('等待后台独立权威核验');expect(frozen.find(`a[href="/purchases/${purchase.id}"]`).exists()).toBe(true);expect(frozen.find('a[href="/payments/queue"]').exists()).toBe(true);expect(wrapper.find('[data-testid="simulate-receipt"]').exists()).toBe(false);expect(wrapper.text()).not.toContain('Mock 权威已付');
 });
});
