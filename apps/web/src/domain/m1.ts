export const sourceStatuses = ['UNPAID', 'PAID', 'CANCELLED', 'REFUNDING', 'REFUNDED'] as const;
export type SourceStatus = typeof sourceStatuses[number];
export type AuthStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';
export type Scenario = 'PAID' | 'DUPLICATE' | 'OUT_OF_ORDER' | 'DELAYED' | 'REFUNDED';
export interface Store {
    id: string;
    platform: 'DOUYIN' | 'TAOBAO';
    externalShopId: string;
    displayName: string;
    enabled: boolean;
    authStatus: AuthStatus;
    authorizationExpiresAt: string | null;
    integrationId: string;
    version: number;
    mode: 'MOCK';
}
export interface StoreCreate {
    platform: Store['platform'];
    externalShopId: string;
    displayName: string;
}
export interface StoreUpdate {
    version: number;
    displayName?: string;
    enabled?: boolean;
    authStatus?: AuthStatus;
}
export interface SalesOrder {
    id: string;
    storeId: string;
    storeName: string;
    platform: Store['platform'];
    externalOrderId: string;
    sourceStatus: SourceStatus;
    localStatus: 'NEW' | 'VERIFIED_PAID' | 'CANCELLED' | 'SUSPENDED';
    currency: 'CNY';
    paidAmountFen: number;
    paidAt: string | null;
    promisedShipAt: string | null;
    sourceUpdatedAt: string;
    sourceVersion: number;
    createdAt: string;
    version: number;
    mode: 'MOCK';
}
export interface SalesOrderItem {
    id: string;
    externalItemId: string;
    externalSkuId: string;
    title: string;
    qty: number;
    paidAllocatedFen: number;
    cancelledQty: number;
    fulfilledQty: number;
}
export interface TimelineEntry {
    id: string;
    eventId: string;
    action: string;
    sourceVersion: number;
    occurredAt: string;
}
export interface SalesOrderDetail extends SalesOrder {
    items: SalesOrderItem[];
    timeline: TimelineEntry[];
}
export interface Page<T> {
    items: T[];
    total: number;
    limit: number;
    offset: number;
}
export interface ScenarioRequest {
    scenario: Scenario;
    externalOrderId: string;
}
export interface ScenarioResult extends ScenarioRequest {
    acceptedEvents: number;
    mode: 'MOCK';
}
export interface OrderFilters {
    limit: number;
    offset: number;
    storeId?: string;
    sourceStatus?: SourceStatus;
    q?: string;
    from?: string;
    to?: string;
}
export const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const integer = (v: unknown, min = 0): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min;
const obj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const keys = (v: Record<string, unknown>, allowed: string[]) => Object.keys(v).every(k => allowed.includes(k));
const text = (v: unknown, max = Number.MAX_SAFE_INTEGER): v is string => typeof v === 'string' && v.length > 0 && v.length <= max;
const oneOf = (v: unknown, choices: readonly string[]) => typeof v === 'string' && choices.includes(v);
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(v) && Number.isFinite(Date.parse(v));
const nullableDate = (v: unknown) => v === null || date(v);
const storeKeys = ['id', 'platform', 'externalShopId', 'displayName', 'enabled', 'authStatus', 'authorizationExpiresAt', 'integrationId', 'version', 'mode'];
export const isStore = (v: unknown): v is Store => obj(v) && keys(v, storeKeys) && uuid(v.id) && oneOf(v.platform, ['DOUYIN', 'TAOBAO']) && text(v.externalShopId, 128) && text(v.displayName, 160) && typeof v.enabled === 'boolean' && oneOf(v.authStatus, ['ACTIVE', 'EXPIRED', 'REVOKED']) && nullableDate(v.authorizationExpiresAt) && uuid(v.integrationId) && integer(v.version) && v.mode === 'MOCK';
const orderKeys = ['id', 'storeId', 'storeName', 'platform', 'externalOrderId', 'sourceStatus', 'localStatus', 'currency', 'paidAmountFen', 'paidAt', 'promisedShipAt', 'sourceUpdatedAt', 'sourceVersion', 'createdAt', 'version', 'mode'];
function orderFields(v: Record<string, unknown>) { return uuid(v.id) && uuid(v.storeId) && text(v.storeName) && oneOf(v.platform, ['DOUYIN', 'TAOBAO']) && text(v.externalOrderId) && oneOf(v.sourceStatus, sourceStatuses) && oneOf(v.localStatus, ['NEW', 'VERIFIED_PAID', 'CANCELLED', 'SUSPENDED']) && v.currency === 'CNY' && integer(v.paidAmountFen) && nullableDate(v.paidAt) && nullableDate(v.promisedShipAt) && date(v.sourceUpdatedAt) && integer(v.sourceVersion, 1) && date(v.createdAt) && integer(v.version) && v.mode === 'MOCK'; }
export const isOrder = (v: unknown): v is SalesOrder => obj(v) && keys(v, orderKeys) && orderFields(v);
export const isOrderDetail = (v: unknown): v is SalesOrderDetail => obj(v) && keys(v, [...orderKeys, 'items', 'timeline']) && orderFields(v) && Array.isArray(v.items) && v.items.every(i => obj(i) && keys(i, ['id', 'externalItemId', 'externalSkuId', 'title', 'qty', 'paidAllocatedFen', 'cancelledQty', 'fulfilledQty']) && uuid(i.id) && text(i.externalItemId) && text(i.externalSkuId) && text(i.title) && integer(i.qty, 1) && integer(i.paidAllocatedFen) && integer(i.cancelledQty) && integer(i.fulfilledQty)) && Array.isArray(v.timeline) && v.timeline.every(i => obj(i) && keys(i, ['id', 'eventId', 'action', 'sourceVersion', 'occurredAt']) && uuid(i.id) && text(i.eventId) && text(i.action) && integer(i.sourceVersion, 1) && date(i.occurredAt));
export const isPage = <T>(check: (v: unknown) => v is T) => (v: unknown): v is Page<T> => obj(v) && keys(v, ['items', 'total', 'limit', 'offset']) && Array.isArray(v.items) && v.items.every(check) && integer(v.total) && integer(v.limit, 1) && v.limit <= 100 && integer(v.offset) && v.offset <= 10000;
export const isStoreCreate = (v: unknown): v is StoreCreate => obj(v) && keys(v, ['platform', 'externalShopId', 'displayName']) && oneOf(v.platform, ['TAOBAO', 'DOUYIN']) && text(v.externalShopId, 128) && /^[A-Za-z0-9_-]+$/.test(v.externalShopId) && text(v.displayName, 160) && v.displayName.trim().length > 0;
export const isStoreUpdate = (v: unknown): v is StoreUpdate => obj(v) && keys(v, ['version', 'displayName', 'enabled', 'authStatus']) && integer(v.version) && Object.keys(v).length > 1 && (v.displayName === undefined || (text(v.displayName, 160) && v.displayName.trim().length > 0)) && (v.enabled === undefined || typeof v.enabled === 'boolean') && (v.authStatus === undefined || oneOf(v.authStatus, ['ACTIVE', 'EXPIRED', 'REVOKED']));
export const isScenario = (v: unknown): v is ScenarioRequest => obj(v) && keys(v, ['scenario', 'externalOrderId']) && oneOf(v.scenario, ['PAID', 'DUPLICATE', 'OUT_OF_ORDER', 'DELAYED', 'REFUNDED']) && text(v.externalOrderId, 80) && /^[A-Za-z0-9_-]+$/.test(v.externalOrderId);
export const isScenarioResult = (v: unknown): v is ScenarioResult => obj(v) && keys(v, ['scenario', 'externalOrderId', 'acceptedEvents', 'mode']) && oneOf(v.scenario, ['PAID', 'DUPLICATE', 'OUT_OF_ORDER', 'DELAYED', 'REFUNDED']) && text(v.externalOrderId) && integer(v.acceptedEvents, 1) && v.mode === 'MOCK';
export function parseOrderFilters(value: Record<string, unknown>): OrderFilters {
    const bound = (v: unknown, fallback: number, min: number, max: number) => typeof v === 'string' && /^-?\d+$/.test(v) ? Math.min(max, Math.max(min, Number(v))) : fallback;
    const out: OrderFilters = { limit: bound(value.limit, 20, 1, 100), offset: bound(value.offset, 0, 0, 10000) };
    if (uuid(value.storeId))
        out.storeId = value.storeId;
    if (oneOf(value.sourceStatus, sourceStatuses))
        out.sourceStatus = value.sourceStatus as SourceStatus;
    if (text(value.q, 80))
        out.q = value.q;
    if (date(value.from))
        out.from = new Date(value.from).toISOString();
    if (date(value.to))
        out.to = new Date(value.to).toISOString();
    return out;
}
export function orderQuery(value: OrderFilters): Record<string, string> { return Object.fromEntries(Object.entries(value).filter(([k, v]) => ['limit', 'offset', 'storeId', 'sourceStatus', 'q', 'from', 'to'].includes(k) && v !== undefined && v !== '').map(([k, v]) => [k, String(v)])); }
export function formatFen(value: number | bigint): string { if (typeof value === 'bigint' ? value < 0n : !integer(value))
    return '金额不可用'; const fen = BigInt(value); return `¥${fen / 100n}.${String(fen % 100n).padStart(2, '0')}`; }
export function shanghaiTime(value: string | null): string { return value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value)) : '—'; }
export const platformName = (platform: Store['platform']) => platform === 'TAOBAO' ? '淘宝 · Mock' : '抖店 · Mock';
export const statusNames: Record<string, string> = { UNPAID: '未付款', PAID: '已付款（Mock）', CANCELLED: '已取消', REFUNDING: '退款中', REFUNDED: '已退款（Mock）', NEW: '新订单', VERIFIED_PAID: '已核实付款（Mock）', SUSPENDED: '已挂起', ACTIVE: '授权有效', EXPIRED: '授权过期', REVOKED: '授权已撤销' };
