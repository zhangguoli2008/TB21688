import schemas from './m2-contract.json';
import { uuid } from './m1';
export type * from './m2-types';
export const mappingStates = ['REVIEW', 'CONFIRMED', 'PAUSED'] as const;
export const intentStates = ['APPROVED', 'CREATING', 'CREATE_UNKNOWN', 'AWAITING_PAYMENT', 'REVIEW_REQUIRED'] as const;
export const purchaseStates = ['CREATING', 'CREATE_UNKNOWN', 'AWAITING_PAYMENT', 'REVIEW_REQUIRED'] as const;
export const offerFaults = ['NORMAL', 'OUT_OF_STOCK', 'PRICE_RISE', 'QUOTE_TIMEOUT', 'CREATE_TIMEOUT_BEFORE', 'CREATE_TIMEOUT_AFTER', 'QUERY_TIMEOUT'] as const;
export const stateNames: Record<string,string> = { REVIEW:'待人工核验',CONFIRMED:'已确认',PAUSED:'已暂停',APPROVED:'已接受 · 待异步创单',CREATING:'Mock 创单处理中',CREATE_UNKNOWN:'创单结果未知 · 冻结重下',AWAITING_PAYMENT:'Mock 待付款',REVIEW_REQUIRED:'需人工处理' };
export const faultNames: Record<string,string> = {NORMAL:'正常',OUT_OF_STOCK:'缺货',PRICE_RISE:'涨价',QUOTE_TIMEOUT:'报价超时',CREATE_TIMEOUT_BEFORE:'创单前超时',CREATE_TIMEOUT_AFTER:'创单后超时',QUERY_TIMEOUT:'查询超时'};
type Schema = { $ref?:string; nullable?:boolean; type?:string; enum?:unknown[]; required?:string[]; properties?:Record<string,Schema>; additionalProperties?:boolean; items?:Schema; minimum?:number; maximum?:number; minLength?:number; maxLength?:number; pattern?:string; format?:string };
function matches(value:unknown,schema:Schema):boolean {
  if(value===null)return schema.nullable===true;
  if(schema.$ref)return matches(value,(schemas as Record<string,Schema>)[schema.$ref.split('/').at(-1)!]!);
  if(schema.enum&&!schema.enum.includes(value))return false;
  if(schema.type==='object'){
    if(typeof value!=='object'||value===null||Array.isArray(value))return false;
    const object=value as Record<string,unknown>;
    return (schema.required??[]).every(key=>Object.hasOwn(object,key))&&Object.entries(object).every(([key,item])=>schema.properties?.[key]?matches(item,schema.properties[key]!):schema.additionalProperties!==false);
  }
  if(schema.type==='array')return Array.isArray(value)&&value.every(item=>matches(item,schema.items!));
  if(schema.type==='integer')return typeof value==='number'&&Number.isSafeInteger(value)&&(schema.minimum===undefined||value>=schema.minimum)&&(schema.maximum===undefined||value<=schema.maximum);
  if(schema.type==='boolean')return typeof value==='boolean';
  if(schema.type==='string')return typeof value==='string'&&(schema.minLength===undefined||value.length>=schema.minLength)&&(schema.maxLength===undefined||value.length<=schema.maxLength)&&(!schema.pattern||new RegExp(schema.pattern).test(value))&&(schema.format!=='uuid'||uuid(value))&&(schema.format!=='date-time'||(/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value)&&Number.isFinite(Date.parse(value))));
  return false;
}
export const contract = <T>(name:keyof typeof schemas) => (value:unknown):value is T => matches(value,schemas[name] as Schema);
export interface M2Filters { limit:number; offset:number; storeId?:string; salesSkuId?:string; status?:string; state?:string; }
export function parseM2Filters(value:Record<string,unknown>):M2Filters {
  const bound=(v:unknown,fallback:number,min:number,max:number)=>typeof v==='string'&&/^\d+$/.test(v)?Math.min(max,Math.max(min,Number(v))):fallback;
  return {limit:bound(value.limit,20,1,100),offset:bound(value.offset,0,0,10000),...(uuid(value.storeId)?{storeId:value.storeId}:{}),...(typeof value.salesSkuId==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(value.salesSkuId)?{salesSkuId:value.salesSkuId}:{}),...(typeof value.status==='string'&&mappingStates.includes(value.status as never)?{status:value.status}:{}),...(typeof value.state==='string'&&intentStates.includes(value.state as never)?{state:value.state}:{})};
}
export function m2Query(value:M2Filters, allowed:string[]=['storeId','salesSkuId','status','state']) {
  return Object.fromEntries(Object.entries({...value,limit:Math.min(100,Math.max(1,value.limit)),offset:Math.min(10000,Math.max(0,value.offset))}).filter(([key,item])=>['limit','offset',...allowed].includes(key)&&item!==undefined&&item!=='').map(([key,item])=>[key,String(item)]));
}
export const signedFen = (value:number) => { const fen=BigInt(value);const absolute=fen<0n?-fen:fen;return `${fen<0n?'-':''}¥${absolute/100n}.${String(absolute%100n).padStart(2,'0')}`; };
