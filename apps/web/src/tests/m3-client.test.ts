import { describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { json, apiError } from './fixtures';
import { paymentPolicy, payment, mandate, attempt, checkout } from './m3-fixtures';
describe('M3 payment contract safety',()=>{
 it('reads the independent payment and financial state',async()=>{
  const client=createApiClient(async()=>json(payment));expect(await client.getPaymentStatus(payment.purchaseId)).toEqual(payment);
 });
 it('submits a single keyed asynchronous request without retrying ambiguous network errors',async()=>{
  const calls:RequestInit[]=[];const client=createApiClient(async(path,options)=>{if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});calls.push(options);throw new TypeError('connection lost');});
  await expect(client.requestPayment(payment.purchaseId,'payment-key-1')).rejects.toMatchObject({code:'NETWORK_ERROR'});expect(calls).toHaveLength(1);expect(new Headers(calls[0]!.headers).get('Idempotency-Key')).toBe('payment-key-1');expect(new Headers(calls[0]!.headers).get('X-CSRF-TOKEN')).toBe('csrf');
 });
 it('validates integer limits, version and extra tenant fields before any write',async()=>{
  let calls=0;const client=createApiClient(async()=>{calls++;return json(paymentPolicy);});const {globalPaused:_g,usedDailyFen:_u,runtimeMode:_m,...input}=paymentPolicy;void _g;void _u;void _m;
  await expect(client.updatePaymentPolicy({...input,maxSingleFen:0.5},'policy-key')).rejects.toMatchObject({code:'INVALID_REQUEST'});
  await expect(client.updatePaymentPolicy({...input,tenantId:'other'} as never,'policy-key')).rejects.toMatchObject({code:'INVALID_REQUEST'});expect(calls).toBe(0);
 });
 it('accepts only local Mock checkout URLs and never a production-looking link',async()=>{
  for(const url of ['https://pay.example.com/fake','//malicious.invalid/pay','/payments/mock/../../other']){
   const client=createApiClient(async(path)=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json({...checkout,url}));await expect(client.getCheckoutEntry(payment.purchaseId,'checkout-key')).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  }
  const client=createApiClient(async(path)=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json(checkout));expect((await client.getCheckoutEntry(payment.purchaseId,'checkout-key')).url).toBe(checkout.url);
 });
 it('keeps UNKNOWN an error and never substitutes a second payment action',async()=>{
  const paths:string[]=[];const client=createApiClient(async(path)=>{paths.push(path);return path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):apiError(409,'PAYMENT_STATUS_UNKNOWN');});await expect(client.getCheckoutEntry(payment.purchaseId,'old-entry-key')).rejects.toMatchObject({code:'PAYMENT_STATUS_UNKNOWN'});expect(paths.filter(path=>!path.endsWith('/csrf'))).toHaveLength(1);
 });
 it('bounds queue filters and strips tenant input',async()=>{
  let url='';const client=createApiClient(async(path)=>{url=path;return json({items:[payment],total:1,limit:100,offset:0});});await client.listPayments({limit:999,offset:-1,state:'UNKNOWN',tenantId:'other'} as never);expect(url).toContain('limit=100');expect(url).toContain('offset=0');expect(url).not.toContain('tenantId');
 });
 it('rejects wrong amount/currency/runtime and credential-bearing responses',async()=>{
  for(const changed of [{amountFen:1.5},{runtimeMode:'REAL'},{currency:'USD'},{token:'secret'}]){const client=createApiClient(async()=>json({...payment,...changed}));await expect(client.getPaymentStatus(payment.purchaseId)).rejects.toMatchObject({code:'INVALID_RESPONSE'});}
 });
 it('sends Mock receipt outcomes without accepting account or payment credentials',async()=>{
  const writes:RequestInit[]=[];const client=createApiClient(async(path,options)=>{if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'});writes.push(options);return json({...payment,attempt});});await client.simulatePaymentReceipt(attempt.id,{outcome:'UNPAID'},'receipt-key');expect(JSON.parse(writes[0]!.body as string)).toEqual({outcome:'UNPAID'});
  await expect(client.updateMandate(mandate.id,{status:'ACTIVE',maxSingleFen:10000,version:0,password:'forbidden'} as never)).rejects.toMatchObject({code:'INVALID_REQUEST'});
 });
});
