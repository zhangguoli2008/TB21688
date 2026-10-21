import { createPinia, setActivePinia } from 'pinia';
import { mount, flushPromises } from '@vue/test-utils';
import { createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Component } from 'vue';
import { api } from '../api/client';
import { createAppRouter } from '../router';
import { useSessionStore } from '../stores/session';
import CatalogPage from '../pages/CatalogPage.vue';
import PurchasesPage from '../pages/PurchasesPage.vue';
import PurchaseDetailPage from '../pages/PurchaseDetailPage.vue';
import OrderDetailPage from '../pages/OrderDetailPage.vue';
import { owner, support, json, apiError } from './fixtures';
import { store, order } from './m1-fixtures';
import { offer, mapping, evaluation, intent, purchase, policy, itemId } from './m2-fixtures';
const m2Owner={...owner,permissions:[...owner.permissions,'sourcing.read','sourcing.manage','procurement.read','procurement.manage']};
const m2Support={...support,permissions:[...support.permissions,'procurement.read']};
const list=(items:unknown[])=>json({items,total:items.length,limit:20,offset:0});
async function setup(component:Component,path:string,persona=m2Owner) {
 const pinia=createPinia();setActivePinia(pinia);const session=useSessionStore();session.user=persona;session.checked=true;
 const router=createAppRouter(createMemoryHistory());await router.push(path);await router.isReady();return {wrapper:mount(component,{global:{plugins:[pinia,router,ElementPlus]}}),router};
}
beforeEach(()=>api.clearCsrf());afterEach(()=>{vi.unstubAllGlobals();document.body.innerHTML='';});
const response=async(path:string)=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):path.includes('/supplier-offers')?list([offer]):path.includes('/sku-mappings')?list([mapping]):path.includes('/procurement-polic')?json(policy):path.includes('/stores')?list([store]):list([]);
describe('M2 views',()=>{
 it('denies direct catalog access for support but allows scoped procurement reading',async()=>{
  const {router,wrapper}=await setup(PurchasesPage,'/purchases',m2Support);await router.push('/catalog/sourcing');expect(router.currentRoute.value.path).toBe('/403');wrapper.unmount();
 });
 it('links sales subitems to their store and external SKU for human review',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>json({...order,items:[{id:itemId,externalItemId:'ITEM',externalSkuId:'MOCK-SKU-BLUE',title:'合成蓝色商品',qty:1,paidAllocatedFen:19901,cancelledQty:0,fulfilledQty:0}],timeline:[]})));
  const {wrapper}=await setup(OrderDetailPage,`/orders/${order.id}`);await flushPromises();const link=wrapper.get('[data-testid="source-item"]');expect(link.attributes('href')).toContain('/catalog/sourcing');expect(link.attributes('href')).toContain('salesSkuId=MOCK-SKU-BLUE');expect(link.attributes('href')).toContain(`itemId=${itemId}`);wrapper.unmount();
 });
 it('requires explicit SKU verification and sends CAS once when confirming a mapping',async()=>{
  const writes:RequestInit[]=[];let finish!:(response:Response)=>void;
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(options.method==='PATCH'&&path.includes('/sku-mappings')){writes.push(options);return new Promise<Response>(resolve=>{finish=resolve;});}return response(path);}));
  const {wrapper}=await setup(CatalogPage,`/catalog/mappings?storeId=${store.id}&salesSkuId=MOCK-SKU-BLUE`);await flushPromises();await wrapper.get('[data-testid="review-mapping"]').trigger('click');
  expect(wrapper.get('[data-testid="confirm-mapping"]').attributes('disabled')).toBeDefined();await wrapper.get('[name="reviewAcknowledged"]').setValue(true);await wrapper.get('[data-testid="confirm-mapping"]').trigger('click');await flushPromises();
  expect(JSON.parse(writes[0]!.body as string)).toEqual({version:0,status:'CONFIRMED',priority:1,reviewAcknowledged:true});await wrapper.get('[data-testid="confirm-mapping"]').trigger('click');expect(writes).toHaveLength(1);finish(json({...mapping,status:'CONFIRMED',version:1,confirmedBy:owner.userId,confirmedAt:'2026-10-20T16:00:00Z'}));await flushPromises();expect(wrapper.text()).toContain('已确认');wrapper.unmount();
 });
 it('previews structured blockers and never enables intent creation for a blocked item',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/evaluate')?json({...evaluation,allowed:false,reasons:[{code:'NO_CONFIRMED_MAPPING',message:'没有已确认映射'}],selectedQuote:null}):response(path)));
  const {wrapper}=await setup(CatalogPage,`/catalog/sourcing?storeId=${store.id}&salesSkuId=MOCK-SKU-BLUE&itemId=${itemId}`);await flushPromises();await wrapper.findAll('form').find(form=>form.find('[name="salesOrderItemId"]').exists())!.trigger('submit');await flushPromises();expect(wrapper.text()).toContain('NO_CONFIRMED_MAPPING');expect(wrapper.get('[data-testid="create-intent"]').attributes('disabled')).toBeDefined();wrapper.unmount();
 });
 it('requires purchase confirmation and keeps the same key for an ambiguous retry',async()=>{
  const writes:RequestInit[]=[];
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(path.endsWith('/evaluate'))return json(evaluation);if(path==='/api/v1/purchase-intents'&&options.method==='POST'){writes.push(options);return apiError(503,'PROVIDER_UNAVAILABLE');}return response(path);}));
  const {wrapper}=await setup(CatalogPage,`/catalog/sourcing?storeId=${store.id}&salesSkuId=MOCK-SKU-BLUE&itemId=${itemId}`);await flushPromises();await wrapper.findAll('form').find(form=>form.find('[name="salesOrderItemId"]').exists())!.trigger('submit');await flushPromises();await wrapper.get('[data-testid="create-intent"]').trigger('click');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-intent"]').trigger('click');await flushPromises();await wrapper.get('[data-testid="confirm-intent"]').trigger('click');await flushPromises();expect(writes).toHaveLength(2);expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBe(new Headers(writes[1]!.headers).get('Idempotency-Key'));wrapper.unmount();
 });
 it('renders unknown creation as frozen and offers only a read refresh',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/purchase-intents/')?json({...intent,state:'CREATE_UNKNOWN',purchaseOrderId:purchase.id}):json(purchase)));
  const {wrapper}=await setup(PurchaseDetailPage,`/purchases/${purchase.id}`,m2Support);await flushPromises();expect(wrapper.text()).toContain('请勿重新下单');expect(wrapper.text()).toContain('¥40.00');expect(wrapper.text()).not.toContain('去付款');expect(wrapper.findAll('button').map(button=>button.text())).toEqual(['刷新状态']);wrapper.unmount();
 });
 it('shows list errors separately from successful emptiness and syncs filters in URL',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/stores')?list([store]):apiError(503,'PURCHASE_UNAVAILABLE')));
  const {wrapper,router}=await setup(PurchasesPage,'/purchases',m2Support);await flushPromises();expect(wrapper.text()).toContain('PURCHASE_UNAVAILABLE');expect(wrapper.find('[data-testid="purchases-empty"]').exists()).toBe(false);await wrapper.get('[name="state"]').setValue('CREATE_UNKNOWN');await wrapper.get('[data-testid="purchase-filters"]').trigger('submit');await flushPromises();expect(router.currentRoute.value.query.state).toBe('CREATE_UNKNOWN');wrapper.unmount();
 });
});

describe('M2 quote and policy interactions',()=>{
 it('keeps quote failures distinct and requires confirmation before changing a fault with CAS',async()=>{
  const writes:RequestInit[]=[];let quoted=0;
  vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
   if(path.endsWith('/quotes')){quoted++;return apiError(503,'SUPPLIER_TIMEOUT');}
   if(path.endsWith('/mock-config')){writes.push(options);return json({...offer,fault:'QUOTE_TIMEOUT',version:1});}
   return response(path);
  }));
  const {wrapper}=await setup(CatalogPage,`/catalog/sourcing?storeId=${store.id}&salesSkuId=MOCK-SKU-BLUE`);await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='查看 / 对照')!.trigger('click');
  await wrapper.findAll('form').find(form=>form.text().includes('报价数量'))!.trigger('submit');await flushPromises();expect(quoted).toBe(1);expect(wrapper.text()).toContain('SUPPLIER_TIMEOUT');expect(wrapper.text()).not.toContain('到手采购总价');
  const config=wrapper.get('.mock-config');await config.find('select').setValue('QUOTE_TIMEOUT');await config.trigger('submit');await flushPromises();expect(writes).toHaveLength(0);
  await wrapper.findAll('button').find(button=>button.text()==='确认保存')!.trigger('click');await flushPromises();expect(writes).toHaveLength(1);expect(new Headers(writes[0]!.headers).get('X-CSRF-TOKEN')).toBe('csrf');expect(JSON.parse(writes[0]!.body as string)).toMatchObject({version:0,fault:'QUOTE_TIMEOUT',unitPriceFen:3500});expect(wrapper.text()).toContain('请重新报价及预览规则');wrapper.unmount();
 });
 it('confirms policy changes before PUT with version and integer fen',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(options.method==='PUT'){writes.push(options);return json({...policy,maxSingleFen:15000,version:1});}return response(path);}));
  const {wrapper}=await setup(CatalogPage,`/catalog/sourcing?storeId=${store.id}&itemId=${itemId}`);await flushPromises();await wrapper.get('[name="maxSingleFen"]').setValue('15000');await wrapper.findAll('form').find(form=>form.find('[name="maxSingleFen"]').exists())!.trigger('submit');await flushPromises();expect(writes).toHaveLength(0);await wrapper.findAll('button').find(button=>button.text()==='确认保存')!.trigger('click');await flushPromises();expect(writes).toHaveLength(1);expect(JSON.parse(writes[0]!.body as string)).toMatchObject({version:0,maxSingleFen:15000});expect(wrapper.text()).toContain('保存规则（版本 1）');wrapper.unmount();
 });
 it('requires a refreshed review after a mapping CAS conflict',async()=>{
  let reads=0;vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(options.method==='PATCH')return apiError(409,'VERSION_CONFLICT');if(path.includes('/sku-mappings')){reads++;return list([{...mapping,version:reads-1}]);}return response(path);}));
  const {wrapper}=await setup(CatalogPage,`/catalog/mappings?storeId=${store.id}&salesSkuId=MOCK-SKU-BLUE`);await flushPromises();await wrapper.get('[data-testid="review-mapping"]').trigger('click');await wrapper.get('[name="reviewAcknowledged"]').setValue(true);await wrapper.get('[data-testid="confirm-mapping"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('VERSION_CONFLICT');await wrapper.findAll('button').find(button=>button.text()==='刷新最新版本')!.trigger('click');await flushPromises();expect(reads).toBe(2);expect(wrapper.find('[data-testid="confirm-mapping"]').exists()).toBe(false);await wrapper.get('[data-testid="review-mapping"]').trigger('click');expect(wrapper.text()).toContain('版本 1');expect(wrapper.get('[data-testid="confirm-mapping"]').attributes('disabled')).toBeDefined();wrapper.unmount();
 });
 it('distinguishes loading purchases from a successful empty list',async()=>{
  let finish!:(response:Response)=>void;vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('/stores')?list([]):new Promise<Response>(resolve=>{finish=resolve;})));
  const {wrapper}=await setup(PurchasesPage,'/purchases');expect(wrapper.find('[aria-busy="true"]').exists()).toBe(true);expect(wrapper.find('[data-testid="purchases-empty"]').exists()).toBe(false);finish(list([]));await flushPromises();expect(wrapper.find('[data-testid="purchases-empty"]').exists()).toBe(true);wrapper.unmount();
 });
});
