import { describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { json } from './fixtures';
import { shipment, shipmentDetail, shipmentEvent, accepted, report, aftersaleDetail } from './m4-fixtures';
describe('M4 strict authority and tenant-safe contracts',()=>{
 it('bounds only indexed shipment filters and strips injected tenant context',async()=>{
  let url='';const client=createApiClient(async path=>{url=path;return json({items:[shipment],total:1,limit:100,offset:0});});
  await client.listShipments({limit:999,offset:-1,syncState:'RETRY_WAIT',tenantId:'other'} as never);
  expect(url).toContain('limit=100');expect(url).toContain('offset=0');expect(url).toContain('syncState=RETRY_WAIT');expect(url).not.toContain('tenantId');
 });
 it('sends one CSRF protected keyed event and never retries ambiguous writes',async()=>{
  const writes:RequestInit[]=[];const client=createApiClient(async(path,options)=>{if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});writes.push(options);throw new TypeError('offline');});
  await expect(client.simulateShipment(shipment.purchaseOrderId,shipmentEvent as never,'event-key')).rejects.toMatchObject({code:'NETWORK_ERROR'});
  expect(writes).toHaveLength(1);expect(new Headers(writes[0]!.headers).get('X-CSRF-TOKEN')).toBe('csrf');expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBe('event-key');
 });
 it('rejects consumer tracking data, fractional fen, tenant fields and missing versions before fetch',async()=>{
  let calls=0;const client=createApiClient(async()=>{calls++;return json(accepted);});
  await expect(client.simulateShipment(shipment.purchaseOrderId,{...shipmentEvent,trackingNumber:'SF123456'} as never,'key')).rejects.toMatchObject({code:'INVALID_REQUEST'});
  await expect(client.updateAftersale(aftersaleDetail.id,{action:'ACKNOWLEDGE',tenantId:'other'} as never,'key')).rejects.toMatchObject({code:'INVALID_REQUEST'});
  await expect(client.simulateSupplierAftersale(aftersaleDetail.id,{eventId:'MOCK-A',purchaseOrderId:shipment.purchaseOrderId,sourceVersion:1,state:'REFUNDED',refundAmountFen:0.5} as never,'key')).rejects.toMatchObject({code:'INVALID_REQUEST'});
  expect(calls).toBe(0);
 });
 it('rejects leaked credentials, invalid timestamps, unsafe integer responses and false net profit',async()=>{
  for(const value of [{...shipmentDetail,token:'secret'},{...shipmentDetail,updatedAt:'yesterday'},{...shipmentDetail,version:9007199254740992}]){
   const client=createApiClient(async()=>json(value));await expect(client.getShipment(shipment.id)).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  }
  const client=createApiClient(async()=>json({...report,actualNetProfitFen:3100}));await expect(client.getReportOverview()).rejects.toMatchObject({code:'INVALID_RESPONSE'});
 });
 it('retains independent supplier status after a local coordination request',async()=>{
  const client=createApiClient(async path=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json(aftersaleDetail));
  const value=await client.updateAftersale(aftersaleDetail.id,{action:'REQUEST_SUPPLIER_REFUND',version:0},'key');expect(value.upstreamState).toBe('REFUND_PENDING');expect(value.refunds).toHaveLength(0);
 });
});

it('sends only pagination on the order activity endpoint',async()=>{let url='';const client=createApiClient(async path=>{url=path;return json({items:[],total:0,limit:20,offset:0});});await client.listOrderActivity(shipment.salesOrderId,{storeId:shipment.storeId,state:'SHIPPED',limit:20,offset:0});expect(url).toBe(`/api/v1/orders/${shipment.salesOrderId}/activity?limit=20&offset=0`);});
