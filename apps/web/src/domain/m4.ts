import schemas from './m4-contract.json';
import { uuid } from './m1';
export type * from './m4-types';
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

export interface M4Filters {limit:number;offset:number;storeId?:string;purchaseOrderId?:string;salesOrderId?:string;state?:string;syncState?:string;type?:string;sourceState?:string;upstreamState?:string;unread?:string;from?:string;to?:string;}
export const shipmentStates=['NOT_CREATED','LABEL_CREATED','SHIPPED','DELIVERED','LOST','EXCEPTION'] as const;
export const syncStates=['NOT_SENT','SENDING','ACKED','RETRY_WAIT','BLOCKED'] as const;
export const aftersaleStates=['REQUESTED','UNDER_REVIEW','SUPPLIER_COORDINATION','RETURN_IN_TRANSIT','REFUND_PENDING','RESOLVED','REJECTED','MANUAL_REVIEW'] as const;
export const aftersaleTypes=['CANCEL','REFUND_ONLY','RETURN_REFUND'] as const;
export const sourceStates=['REQUESTED','CANCELLED','REFUNDING','REFUNDED','REJECTED','ARBITRATION'] as const;
export const upstreamStates=['NO_PURCHASE','PENDING','CANCEL_REQUESTED','CANCELLED','REFUND_PENDING','REFUNDED','RETURN_REQUIRED','RETURN_RECEIVED','MANUAL_REVIEW','QUERY_PAYMENT','UNKNOWN','REJECTED'] as const;
export const syncBehaviors=['NORMAL','SUBMIT_5XX','SUBMIT_TIMEOUT_AFTER','QUERY_TIMEOUT','AUTHORITY_MISMATCH','CORRECTION_BLOCKED'] as const;
export const actionNames:Record<string,string>={ACKNOWLEDGE:'接单审核',RETURN_SENT:'登记退货已寄出',REQUEST_SUPPLIER_CANCEL:'申请供应商取消',REQUEST_SUPPLIER_REFUND:'申请供应商退款'};
export const m4Names:Record<string,string>={NOT_CREATED:'尚未创建',LABEL_CREATED:'已生成面单',SHIPPED:'已发货',DELIVERED:'已签收',LOST:'丢件',EXCEPTION:'物流异常',NOT_SENT:'尚未提交',SENDING:'回填处理中',ACKED:'Mock 权威确认',RETRY_WAIT:'等待补偿',BLOCKED:'需人工处理',REQUESTED:'已申请',UNDER_REVIEW:'审核中',SUPPLIER_COORDINATION:'供应商协调中',RETURN_IN_TRANSIT:'退货运输中',REFUND_PENDING:'退款处理中',RESOLVED:'本地工单已解决',REJECTED:'已拒绝',MANUAL_REVIEW:'需人工核对',CANCEL:'取消',REFUND_ONLY:'仅退款',RETURN_REFUND:'退货退款',REFUNDING:'来源退款中',REFUNDED:'Mock 权威已退款',CANCELLED:'权威已取消',ARBITRATION:'平台仲裁中',NO_PURCHASE:'无关联采购',PENDING:'待协调',CANCEL_REQUESTED:'已申请取消',RETURN_REQUIRED:'等待退货',RETURN_RECEIVED:'已收到退货',QUERY_PAYMENT:'先核验付款',UNKNOWN:'权威状态未知',NORMAL:'正常',SUBMIT_5XX:'回填 5xx',SUBMIT_TIMEOUT_AFTER:'提交后超时',QUERY_TIMEOUT:'查单超时',AUTHORITY_MISMATCH:'权威信息不符',CORRECTION_BLOCKED:'更改单号受限'};
export function m4Query(value:Partial<M4Filters>={},kind:'shipment'|'aftersale'|'report'|'notification'|'activity'='shipment',paged=true):Record<string,string>{
 const result:Record<string,string>={};
 if(paged){result.limit=String(Math.min(100,Math.max(1,Number.isFinite(Number(value.limit))?Math.trunc(Number(value.limit)):20)));result.offset=String(Math.min(10000,Math.max(0,Number.isFinite(Number(value.offset))?Math.trunc(Number(value.offset)):0)));}
 const ids=kind==='shipment'?['storeId','purchaseOrderId','salesOrderId']:kind==='aftersale'?['storeId','salesOrderId']:kind==='activity'?[]:['storeId'];
 for(const key of ids){const v=value[key as keyof M4Filters];if(uuid(v))result[key]=v;}
 const enums:Record<string,readonly string[]>=kind==='shipment'?{state:shipmentStates,syncState:syncStates}:kind==='aftersale'?{state:aftersaleStates,type:aftersaleTypes,sourceState:sourceStates,upstreamState:upstreamStates}:kind==='notification'?{unread:['true','false']}:{};
 for(const [key,allowed] of Object.entries(enums)){const v=value[key as keyof M4Filters];if(typeof v==='string'&&allowed.includes(v))result[key]=v;}
 if(kind==='report')for(const key of ['from','to'] as const){const v=value[key];if(v&&/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(v)&&Number.isFinite(Date.parse(v)))result[key]=v;}
 return result;
}
export function parseM4Filters(value:Record<string,unknown>,kind:Parameters<typeof m4Query>[1]='shipment'):M4Filters{const query=m4Query(value as Partial<M4Filters>,kind);return {...query,limit:Number(query.limit),offset:Number(query.offset)};}
