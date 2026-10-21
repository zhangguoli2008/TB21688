import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { createAppRouter } from '../router';
import { useSessionStore } from '../stores/session';
import { owner, support, json, apiError } from './fixtures';
import { store,order } from './m1-fixtures';
import { purchase,intent } from './m2-fixtures';
import { shipment,shipmentDetail,aftersale,aftersaleDetail,report,notification,accepted } from './m4-fixtures';
const fullOwner={...owner,permissions:[...owner.permissions,'logistics.read','logistics.manage','aftersales.read','aftersales.manage','aftersales.simulate','reports.manage','notifications.read']};
const reader={...support,permissions:[...support.permissions,'logistics.read','aftersales.read','notifications.read']};
const wrappers:VueWrapper[]=[];
const list=(items:unknown[],total=items.length)=>json({items,total,limit:20,offset:0});
async function response(path:string){
 if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});
 if(path.includes('/stores?'))return list([store]);
 if(path.includes('/shipments?'))return list([shipment]);
 if(path.includes(`/shipments/${shipment.id}`))return json(shipmentDetail);
 if(path.includes('/aftersales?'))return list([aftersale]);
 if(path.includes(`/aftersales/${aftersale.id}`))return json(aftersaleDetail);
 if(path.includes('/reports/overview?'))return json(report);
 if(path.includes('/notifications?'))return list([notification]);
 return list([]);
}
async function setup(path:string,persona=fullOwner){const pinia=createPinia();setActivePinia(pinia);const session=useSessionStore();session.user=persona;session.checked=true;const router=createAppRouter(createMemoryHistory());await router.push(path);await router.isReady();const wrapper=mount({template:'<RouterView />'},{global:{plugins:[pinia,router,ElementPlus]}});wrappers.push(wrapper);await flushPromises();return {wrapper,router,session};}
beforeEach(()=>{api.clearCsrf();vi.stubGlobal('fetch',vi.fn(response));});
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());vi.unstubAllGlobals();document.body.innerHTML='';});
describe('M4 route and authority interactions',()=>{
 it('separates supplier state from downstream acknowledgement and limits support to read',async()=>{const {wrapper}=await setup('/shipments',reader);expect(wrapper.text()).toContain('物流中心');expect(wrapper.text()).toContain('等待补偿');expect(wrapper.text()).toContain('MOCK-TRACK-ONE');expect(wrapper.find('[data-testid="shipment-generator"]').exists()).toBe(false);});
 it('preserves errors instead of empty success and synchronizes store pagination filters',async()=>{let fail=true;vi.stubGlobal('fetch',vi.fn(async path=>path.includes('/shipments?')?(fail?apiError(503,'SHIPMENT_UNAVAILABLE'):list([],42)):response(path)));const {wrapper,router}=await setup('/shipments');expect(wrapper.text()).toContain('SHIPMENT_UNAVAILABLE');expect(wrapper.find('[data-testid="m4-empty"]').exists()).toBe(false);fail=false;await wrapper.get('[name="storeId"]').setValue(store.id);await wrapper.get('[data-testid="m4-filters"]').trigger('submit');await flushPromises();expect(router.currentRoute.value.query.storeId).toBe(store.id);expect(wrapper.find('[data-testid="m4-empty"]').exists()).toBe(true);await wrapper.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(router.currentRoute.value.query.offset).toBe('20');});
 it('confirms one retry and refreshes authority rather than inventing ACKED',async()=>{const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/sync-retry')){writes.push(options);return json({...shipmentDetail,currentSync:{...shipment.currentSync,state:'SENDING'}});}return response(path);}));const {wrapper}=await setup(`/shipments/${shipment.id}`);await wrapper.get('[data-testid="retry-shipment"]').trigger('click');expect(writes).toHaveLength(0);await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();expect(writes).toHaveLength(1);expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBeTruthy();expect(wrapper.text()).toContain('等待补偿');expect(wrapper.text()).not.toContain('回填已成功');});
 it('keeps source refunded and supplier refund pending visibly separate',async()=>{const {wrapper}=await setup(`/aftersales/${aftersale.id}`);expect(wrapper.text()).toContain('来源平台状态');expect(wrapper.text()).toContain('供应商权威状态');expect(wrapper.text()).toContain('REFUND_PENDING');expect(wrapper.text()).toContain('无已核验退款记录');await wrapper.get('[data-testid="aftersale-action"]').trigger('click');expect(wrapper.find('[data-testid="confirm-m4-action"]').exists()).toBe(true);});
 it('denies report deep links without reports.read and hides simulate from support',async()=>{const {router,wrapper}=await setup('/reports',reader);expect(router.currentRoute.value.path).toBe('/403');await router.push(`/aftersales/${aftersale.id}`);await flushPromises();expect(wrapper.find('[data-testid="supplier-generator"]').exists()).toBe(false);expect(wrapper.find('[data-testid="aftersale-action"]').exists()).toBe(false);});
 it('shows estimated profit and net cash flow separately while actual costs are incomplete',async()=>{const {wrapper}=await setup('/reports');expect(wrapper.text()).toContain('销售实收');expect(wrapper.text()).toContain('采购实际付出');expect(wrapper.text()).toContain('预计利润');expect(wrapper.text()).toContain('净现金流');expect(wrapper.text()).toContain('-¥55.00');expect(wrapper.text()).toContain('成本不完整');expect(wrapper.text()).toContain('ORDER_CREATED_COHORT');});
 it('marks notification read only after server confirmation',async()=>{let finish!:(r:Response)=>void;vi.stubGlobal('fetch',vi.fn(async(path)=>path.endsWith('/read')?new Promise<Response>(resolve=>{finish=resolve;}):response(path)));const {wrapper}=await setup('/notifications');await wrapper.get('[data-testid="read-notification"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('未读');finish(json({...notification,readAt:'2026-10-21T01:00:00Z'}));await flushPromises();expect(wrapper.text()).toContain('已读');});
});

describe('M4 write boundaries and stale contexts',()=>{
 it('retains a retry key even when the confirmation is dismissed after a network error',async()=>{
  const writes:RequestInit[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/sync-retry')){writes.push(options);return apiError(503,'NETWORK_UNKNOWN');}return response(path);}));
  const {wrapper}=await setup(`/shipments/${shipment.id}`);await wrapper.get('[data-testid="retry-shipment"]').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();
  await wrapper.findAll('button').find(b=>b.text()==='取消')!.trigger('click');await wrapper.get('[data-testid="retry-shipment"]').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();
  expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBe(new Headers(writes[1]!.headers).get('Idempotency-Key'));
 });
 it('generates two independent internal Mock packages without accepting a real tracking number',async()=>{
  const bodies:Record<string,unknown>[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/shipment-events')){bodies.push(JSON.parse(options.body));return json(accepted,202);}if(path.includes('/purchase-intents/'))return json(intent);if(path.includes(`/purchases/${purchase.id}`))return json(purchase);return response(path);}));
  const {wrapper}=await setup(`/purchases/${purchase.id}`);for(let n=0;n<2;n++){await wrapper.get('[data-testid="shipment-generator"] button').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();}
  expect(bodies).toHaveLength(2);expect(bodies[0]!.externalShipmentId).not.toBe(bodies[1]!.externalShipmentId);expect(bodies[0]!.trackingNumber).toMatch(/^MOCK-TRACK-/);expect(wrapper.text()).toContain('Mock 事件已排队');
 });
 it('sends a tracking correction against the same package and the next source version',async()=>{
  let body:Record<string,unknown>={};vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/shipment-events')){body=JSON.parse(options.body);return json(accepted,202);}return response(path);}));
  const {wrapper}=await setup(`/shipments/${shipment.id}`);await wrapper.get('[data-testid="shipment-generator"] button').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();expect(body.externalShipmentId).toBe('MOCK-PKG-ONE');expect(body.sourceVersion).toBe(2);expect(body.trackingNumber).not.toBe('MOCK-TRACK-ONE');expect(wrapper.text()).toContain('Mock 事件已受理，等待后台独立核验。');
 });
 it('does not apply a pending action after switching to another shipment',async()=>{
  let finish!:(r:Response)=>void;const other='b1000000-0000-4000-8000-000000000002';vi.stubGlobal('fetch',vi.fn(async path=>{if(path.endsWith('/sync-retry'))return new Promise<Response>(resolve=>{finish=resolve;});if(path.includes(other))return json({...shipmentDetail,id:other,externalShipmentId:'MOCK-PKG-TWO'});return response(path);}));
  const {wrapper,router}=await setup(`/shipments/${shipment.id}`);await wrapper.get('[data-testid="retry-shipment"]').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();await router.push(`/shipments/${other}`);await flushPromises();finish(json(shipmentDetail));await flushPromises();expect(wrapper.text()).toContain('MOCK-PKG-TWO');expect(wrapper.find('[data-testid="confirm-m4-action"]').exists()).toBe(false);
 });
 it('creates a source refund event with integer fen and requires supplier authority confirmation independently',async()=>{
  const bodies:Record<string,unknown>[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/aftersale-events')){bodies.push(JSON.parse(options.body));return json(accepted,202);}if(path.includes(`/orders/${order.id}/activity`))return list([]);if(path.endsWith(`/orders/${order.id}`))return json({...order,items:[{id:shipment.salesOrderItemId,externalItemId:'MOCK-ITEM',externalSkuId:'MOCK-SKU',title:'合成商品',qty:1,paidAllocatedFen:8600,cancelledQty:0,fulfilledQty:0}],timeline:[]});return response(path);}));
  const {wrapper}=await setup(`/orders/${order.id}`);await wrapper.get('[name="requestedRefundFen"]').setValue('1.5');expect(wrapper.get('[data-testid="source-generator"] button').attributes('disabled')).toBeDefined();await wrapper.get('[name="requestedRefundFen"]').setValue('8600');await wrapper.get('[name="eventSourceState"]').setValue('REFUNDED');await wrapper.get('[data-testid="source-generator"] button').trigger('click');expect(bodies).toHaveLength(0);await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();expect(bodies[0]!.requestedRefundFen).toBe(8600);expect(bodies[0]!.sourceCaseId).toMatch(/^MOCK-CASE-/);expect(wrapper.text()).not.toContain('供应商退款成功');expect(wrapper.text()).toContain('Mock 事件已受理，等待后台独立核验。');
 });
 it('shows operation times in Shanghai and paginates safe activity summaries',async()=>{
  const paths:string[]=[];vi.stubGlobal('fetch',vi.fn(async path=>{paths.push(path);if(path.includes('/activity?'))return list([{id:notification.id,salesOrderId:order.id,source:'WORKER',action:'SHIPMENT_SYNC_FAILED',entityType:'SHIPMENT',entityId:shipment.id,code:'PROVIDER_5XX',actorId:null,occurredAt:'2026-10-20T16:00:00Z',mode:'MOCK'}],42);if(path.endsWith(`/orders/${order.id}`))return json({...order,items:[],timeline:[]});return response(path);}));
  const {wrapper}=await setup(`/orders/${order.id}`);expect(wrapper.text()).toContain('2026/10/21');expect(wrapper.text()).toContain('00:00:00');await wrapper.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(paths.some(path=>path.includes('/activity?limit=20&offset=20'))).toBe(true);
 });
});

describe('M4 successive compensation requests',()=>{
 it('uses a new key for a new confirmed retry after the previous request was accepted',async()=>{
  const keys:string[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/sync-retry')){keys.push(new Headers(options.headers).get('Idempotency-Key')!);return json(shipmentDetail);}return response(path);}));const {wrapper}=await setup(`/shipments/${shipment.id}`);
  for(let n=0;n<2;n++){await wrapper.get('[data-testid="retry-shipment"]').trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();}expect(keys).toHaveLength(2);expect(keys[0]).not.toBe(keys[1]);
 });
});

describe('M4 projection, return and per-user reading boundaries',()=>{
 it('warns when projection work remains and preserves incomplete actual profit',async()=>{vi.stubGlobal('fetch',vi.fn(async path=>path.includes('/reports/overview?')?json({...report,projectionPendingCount:3}):response(path)));const {wrapper}=await setup('/reports');expect(wrapper.text()).toContain('3 个报表投影待更新');expect(wrapper.text()).toContain('当前金额可能滞后');expect(wrapper.text()).toContain('成本不完整');});
 it('distinguishes a return requirement from supplier refund completion',async()=>{vi.stubGlobal('fetch',vi.fn(async path=>path.includes(`/aftersales/${aftersale.id}`)?json({...aftersaleDetail,type:'RETURN_REFUND',sourceState:'REFUNDING',state:'RETURN_IN_TRANSIT',upstreamState:'RETURN_REQUIRED',coordinations:[{...aftersaleDetail.coordinations[0],state:'RETURN_REQUIRED',upstreamSourceState:'RETURN_REQUIRED'}]}):response(path)));const {wrapper}=await setup(`/aftersales/${aftersale.id}`);expect(wrapper.text()).toContain('退货退款');expect(wrapper.text()).toContain('等待退货');expect(wrapper.text()).toContain('RETURN_REQUIRED');expect(wrapper.text()).toContain('无已核验退款记录');});
 it('reloads unread notifications for a different authenticated user without reusing the first user marker',async()=>{let current='OWNER';vi.stubGlobal('fetch',vi.fn(async path=>{if(path.endsWith('/read'))return json({...notification,readAt:'2026-10-21T01:00:00Z'});if(path.includes('/notifications?'))return list([{...notification,readAt:current==='OWNER'?'2026-10-21T01:00:00Z':null}]);return response(path);}));const {wrapper,session}=await setup('/notifications');expect(wrapper.text()).toContain('已读');current='SUPPORT';session.user=reader;await flushPromises();expect(wrapper.text()).toContain('未读');expect(wrapper.get('[data-testid="read-notification"]').text()).toBe('标记已读');});
 it('keeps the event identity and every reviewed source field stable after ambiguous failure',async()=>{const bodies:string[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path.endsWith('/supplier-events')){bodies.push(options.body);return apiError(503,'PROVIDER_UNKNOWN');}return response(path);}));const {wrapper}=await setup(`/aftersales/${aftersale.id}`);await wrapper.get('[data-testid="supplier-generator"] button').trigger('click');for(let n=0;n<2;n++){await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();}expect(bodies).toHaveLength(2);expect(bodies[0]).toBe(bodies[1]);expect(JSON.parse(bodies[0]!).eventId).toMatch(/^MOCK-/);expect(wrapper.text()).toContain('REFUND_PENDING');});
});

describe('M4 persistent report action idempotency',()=>{
 it('queues a new projection rebuild for each deliberate successful confirmation on the same route',async()=>{
  const keys:string[]=[];vi.stubGlobal('fetch',vi.fn(async(path,options)=>{if(path==='/api/v1/reports/refresh'){keys.push(new Headers(options.headers).get('Idempotency-Key')!);return json({state:'QUEUED',queuedCount:1,mode:'MOCK'},202);}return response(path);}));
  const {wrapper,router}=await setup('/reports');
  for(let n=0;n<2;n++){await wrapper.findAll('button').find(button=>button.text()==='重建 Mock 报表投影')!.trigger('click');await wrapper.get('[data-testid="confirm-m4-action"]').trigger('click');await flushPromises();}
  expect(router.currentRoute.value.fullPath).toBe('/reports');expect(keys).toHaveLength(2);expect(keys[0]).not.toBe(keys[1]);expect(wrapper.text()).toContain('报表投影刷新已排队');
 });
});
