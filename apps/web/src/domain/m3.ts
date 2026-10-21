import schemas from './m3-contract.json';
import { uuid } from './m1';
export type * from './m3-types';
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

export const paymentModes=['MANUAL','AUTO','RULE_BASED'] as const;
export const paymentStates=['WAIT_MERCHANT_PAYMENT','PAYING','UNKNOWN','MANUAL_RECONCILIATION','PAID','FAILED','BLOCKED','NEW'] as const;
export const paymentStateNames:Record<string,string>={NEW:'待发起',WAIT_MERCHANT_PAYMENT:'待商家付款',PAYING:'Mock 付款处理中',UNKNOWN:'结果未知 · 禁止再付',MANUAL_RECONCILIATION:'待人工对账 · 冻结再付',PAID:'Mock 权威已付',FAILED:'Mock 明确失败',BLOCKED:'付款已阻塞'};
export const modeNames:Record<string,string>={MANUAL:'人工付款',AUTO:'自动付款',RULE_BASED:'按规则付款'};
export const paymentFaults=['NORMAL','DECLINED','PAY_TIMEOUT_BEFORE','PAY_TIMEOUT_AFTER','QUERY_TIMEOUT','AUTHORITY_MISMATCH'] as const;
export const faultNames:Record<string,string>={NORMAL:'正常',DECLINED:'明确拒付',PAY_TIMEOUT_BEFORE:'付款前超时',PAY_TIMEOUT_AFTER:'付款成功后超时',QUERY_TIMEOUT:'付款后持续查单超时',AUTHORITY_MISMATCH:'权威金额不一致'};
export const frozen=(state:string)=>['UNKNOWN','MANUAL_RECONCILIATION'].includes(state);
export interface PaymentFilters {limit:number;offset:number;storeId?:string;state?:string;}
export function paymentQuery(filters:PaymentFilters,withState=true){
 const raw={limit:Math.min(100,Math.max(1,Number.isFinite(filters.limit)?Math.trunc(filters.limit):20)),offset:Math.min(10000,Math.max(0,Number.isFinite(filters.offset)?Math.trunc(filters.offset):0)),...(uuid(filters.storeId)?{storeId:filters.storeId}:{}),...(withState&&paymentStates.includes(filters.state as never)?{state:filters.state}:{})};
 return Object.fromEntries(Object.entries(raw).map(([key,value])=>[key,String(value)]));
}
export function parsePaymentFilters(query:Record<string,unknown>):PaymentFilters {
 const number=(v:unknown,fallback:number)=>typeof v==='string'&&/^\d+$/.test(v)?Number(v):fallback;
 const parsed=paymentQuery({limit:number(query.limit,20),offset:number(query.offset,0),storeId:String(query.storeId??''),state:String(query.state??'')});
 return {...parsed,limit:Number(parsed.limit),offset:Number(parsed.offset)};
}
