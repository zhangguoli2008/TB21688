import { describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { json, apiError } from './fixtures';
import { offer, mapping, policy, quote, intent, purchase, itemId } from './m2-fixtures';
import { store } from './m1-fixtures';
describe('M2 contract boundaries', () => {
  it('sends procurement key and CSRF once without silently replaying a network failure', async () => {
    const writes: RequestInit[]=[];
    const client=createApiClient(async(path,options)=>{if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});writes.push(options);throw new TypeError('lost after submission');});
    await expect(client.createPurchaseIntent({salesOrderItemId:itemId,quoteId:quote.id},'intent-key')).rejects.toMatchObject({code:'NETWORK_ERROR'});
    expect(writes).toHaveLength(1);expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBe('intent-key');expect(new Headers(writes[0]!.headers).get('X-CSRF-TOKEN')).toBe('csrf');
  });
  it('validates CAS and excludes untrusted tenant fields before writing',async()=>{
    const calls:RequestInit[]=[];const client=createApiClient(async(path,options)=>{calls.push(options);return path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json(mapping);});
    await client.updateMapping(mapping.id,{version:0,status:'CONFIRMED',reviewAcknowledged:true});
    expect(calls[1]!.method).toBe('PATCH');expect(JSON.parse(calls[1]!.body as string)).toEqual({version:0,status:'CONFIRMED',reviewAcknowledged:true});
    await expect(client.createMapping({storeId:store.id,salesSkuId:'MOCK-SKU-BLUE',supplierOfferId:offer.id,tenantId:'other'} as never,'key')).rejects.toMatchObject({code:'INVALID_REQUEST'});
    await expect(client.updateOffer(offer.id,{version:0,unitPriceFen:0.5})).rejects.toMatchObject({code:'INVALID_REQUEST'});
  });
  it('refreshes CSRF after a rejected policy PUT',async()=>{
    let csrf=0;let writes=0;const client=createApiClient(async(path)=>path.endsWith('/csrf')?(csrf++,json({token:`csrf-${csrf}`,headerName:'X-CSRF-TOKEN'})):(++writes===1?apiError(403):json(policy)));
    const {mode: _mode,...body}=policy;void _mode;
    await expect(client.updatePolicy(body as never)).rejects.toMatchObject({status:403});await client.updatePolicy(body as never);expect(csrf).toBe(2);
  });
  it.each([{...offer,unitPriceFen:1.1},{...offer,stock:-1},{...offer,token:'secret'},{...offer,mode:'REAL'}])('rejects malformed or unsafe offers',async(value)=>{
    const client=createApiClient(async()=>json({items:[value],total:1,limit:20,offset:0}));await expect(client.listOffers()).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  });
  it('accepts separate creation-unknown and verified paid purchase states but rejects non-domain states',async()=>{
    const client=createApiClient(async()=>json(purchase));expect((await client.getPurchase(purchase.id)).state).toBe('CREATE_UNKNOWN');
    const paid=createApiClient(async()=>json({...purchase,state:'PAID'}));expect((await paid.getPurchase(purchase.id)).state).toBe('PAID');
    const invalid=createApiClient(async()=>json({...purchase,state:'REAL_PAID'}));await expect(invalid.getPurchase(purchase.id)).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  });
  it('bounds pagination and strips unsupported filters',async()=>{
    let url='';const client=createApiClient(async(path)=>{url=path;return json({items:[intent],total:1,limit:100,offset:0});});await client.listIntents({limit:999,offset:-1,storeId:store.id,state:'APPROVED',tenantId:'other'} as never);
    expect(url).toContain('limit=100');expect(url).toContain('offset=0');expect(url).not.toContain('tenantId');
  });
});
