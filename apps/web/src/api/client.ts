import * as m4 from '../domain/m4';
import { contract as m3Contract, paymentQuery, type PaymentFilters, type Policy as PaymentPolicy, type PolicyUpdate as PaymentPolicyUpdate, type MandatePage, type Mandate, type MandateUpdate, type PaymentPage, type PaymentView, type CheckoutEntry, type MockCheckout, type ReceiptRequest, type Config as PaymentConfig, type ConfigUpdate as PaymentConfigUpdate, type ReconciliationPage } from '../domain/m3';
import { contract, m2Query, type M2Filters, type Offer, type OfferPage, type OfferConfig, type Quote, type QuoteRequest, type Mapping, type MappingPage, type MappingCreate, type MappingUpdate, type Policy, type PolicyUpdate, type Evaluation, type Intent, type IntentPage, type IntentCreate, type Purchase, type PurchasePage } from '../domain/m2';
import { isStore, isOrder, isOrderDetail, isPage, isStoreCreate, isStoreUpdate, isScenario, isScenarioResult, orderQuery, uuid, type StoreCreate, type StoreUpdate, type OrderFilters, type ScenarioRequest } from "../domain/m1";
export type Persona = "OWNER_A" | "SUPPORT_A" | "OWNER_B";
export interface Me {
  userId: string;
  email: string;
  tenantIds: string[];
  currentTenantId: string;
  tenantName: string;
  roles: string[];
  permissions: string[];
  mode: "MOCK";
}
export interface SystemStatus {
  mode: "MOCK";
  realChannelsEnabled: false;
  services: {
    name: "api" | "postgresql" | "redis" | "rabbitmq";
    status: "UP" | "DOWN" | "UNKNOWN";
  }[];
}
interface Csrf {
  token: string;
  headerName: "X-CSRF-TOKEN";
}
type Fetcher = (path: string, options: RequestInit) => Promise<Response>;

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status = 0,
    public requestId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function strings(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.every((item) => typeof item === "string")
  );
}
function isMe(value: unknown): value is Me {
  return (
    record(value) &&
    typeof value.userId === "string" &&
    typeof value.email === "string" &&
    typeof value.currentTenantId === "string" &&
    typeof value.tenantName === "string" &&
    strings(value.tenantIds) &&
    value.tenantIds.includes(value.currentTenantId) &&
    strings(value.roles) &&
    strings(value.permissions) &&
    value.mode === "MOCK"
  );
}
function isStatus(value: unknown): value is SystemStatus {
  return (
    record(value) &&
    value.mode === "MOCK" &&
    value.realChannelsEnabled === false &&
    Array.isArray(value.services) &&
    value.services.length === 4 &&
    new Set(
      value.services.map((service: unknown) =>
        record(service) ? service.name : undefined,
      ),
    ).size === 4 &&
    value.services.every(
      (service: unknown) =>
        record(service) &&
        ["api", "postgresql", "redis", "rabbitmq"].includes(
          String(service.name),
        ) &&
        ["UP", "DOWN", "UNKNOWN"].includes(String(service.status)),
    )
  );
}
function isCsrf(value: unknown): value is Csrf {
  return (
    record(value) &&
    typeof value.token === "string" &&
    value.token.length > 0 &&
    value.headerName === "X-CSRF-TOKEN"
  );
}

export function createApiClient(
  fetcher: Fetcher = (path, options) => fetch(path, options),
) {
  let csrf: Csrf | null = null;
  let sessionEpoch = 0;
  let unauthorizedHandler: (() => void) | undefined;

  async function request<T>(
    path: string,
    valid: (value: unknown) => value is T,
    options: RequestInit = {},
  ): Promise<T> {
    const requestEpoch = sessionEpoch;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetcher(`/api/v1${path}`, {
        ...options,
        credentials: "same-origin",
        signal: controller.signal,
        headers: { Accept: "application/json", ...options.headers },
      });
      if (requestEpoch !== sessionEpoch) throw new ApiError("SESSION_CHANGED", "会话已变化，请刷新当前页面。");
      if (response.status === 401) {
        csrf = null;
        unauthorizedHandler?.();
      }
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        throw new ApiError(
          "INVALID_RESPONSE",
          "服务返回了无法识别的响应，请稍后重试。",
          response.status,
        );
      }
      if (!response.ok) {
        if (response.status === 403 && ["POST", "PATCH", "PUT"].includes(options.method ?? "")) csrf = null;
        throw new ApiError(
          record(body) && typeof body.code === "string"
            ? body.code
            : "HTTP_ERROR",
          record(body) && typeof body.message === "string"
            ? body.message
            : "请求失败，请稍后重试。",
          response.status,
          record(body) && typeof body.requestId === "string"
            ? body.requestId
            : undefined,
        );
      }
      if (
        !record(body) ||
        typeof body.requestId !== "string" ||
        !valid(body.data)
      ) {
        throw new ApiError(
          "INVALID_RESPONSE",
          "服务响应与演示合同不符。",
          response.status,
          record(body) && typeof body.requestId === "string"
            ? body.requestId
            : undefined,
        );
      }
      if (requestEpoch !== sessionEpoch) throw new ApiError("SESSION_CHANGED", "会话已变化，请刷新当前页面。");
      return body.data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted)
        throw new ApiError("REQUEST_TIMEOUT", "请求超时，请检查服务后重试。");
      throw new ApiError(
        "NETWORK_ERROR",
        "无法连接服务，请检查网络与本地 API。",
      );
    } finally {
      clearTimeout(timeout);
    }
  }

  async function refreshCsrf() {
    csrf = await request("/auth/csrf", isCsrf);
  }
  async function post<T>(
    path: string,
    valid: (value: unknown) => value is T,
    body?: unknown,
    extraHeaders: Record<string, string> = {},
    method = "POST",
  ) {
    if (!csrf) await refreshCsrf();
    return request(path, valid, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...extraHeaders,
        [csrf!.headerName]: csrf!.token,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  return {
    setUnauthorizedHandler(handler: () => void) {
      unauthorizedHandler = handler;
    },
    clearCsrf() {
      sessionEpoch++;
      csrf = null;
    },
    listStores: (limit = 20, offset = 0) => request(`/stores?limit=${Math.min(100, Math.max(1, limit))}&offset=${Math.min(10000, Math.max(0, offset))}`, isPage(isStore)),
    getStore: (id: string) => request(`/stores/${encodeURIComponent(id)}`, isStore),
    async createStore(body: StoreCreate, key: string) {
      if (!isStoreCreate(body) || !key) throw new ApiError("INVALID_REQUEST", "请填写有效的 Mock 店铺信息。");
      return post("/stores", isStore, body, { "Idempotency-Key": key });
    },
    async updateStore(id: string, body: StoreUpdate) {
      if (!uuid(id) || !isStoreUpdate(body)) throw new ApiError("INVALID_REQUEST", "店铺更新信息不符合合同。");
      return post(`/stores/${id}`, isStore, body, {}, "PATCH");
    },
    listOrders: (filters: OrderFilters = { limit: 20, offset: 0 }) => request(`/orders?${new URLSearchParams(orderQuery(filters))}`, isPage(isOrder)),
    getOrder: (id: string) => request(`/orders/${encodeURIComponent(id)}`, isOrderDetail),
    async simulateOrders(id: string, body: ScenarioRequest, key: string) {
      if (!uuid(id) || !isScenario(body) || !key) throw new ApiError("INVALID_REQUEST", "请输入有效的合成订单号和模拟场景。");
      return post(`/mock/stores/${id}/scenarios`, isScenarioResult, body, { "Idempotency-Key": key });
    },
    listOffers: (filters: M2Filters = {limit:20,offset:0}) => request(`/supplier-offers?${new URLSearchParams(m2Query(filters,[]))}`, contract<OfferPage>('M2OfferPage')),
    async updateOffer(id:string, body:OfferConfig) {
      if(!uuid(id)||!contract<OfferConfig>('M2OfferConfig')(body)) throw new ApiError('INVALID_REQUEST','Mock 货源配置不符合合同，请使用整数分。');
      return post(`/supplier-offers/${id}/mock-config`,contract<Offer>('M2Offer'),body,{},'PATCH');
    },
    async quoteOffer(id:string,body:QuoteRequest) {
      if(!uuid(id)||!contract<QuoteRequest>('M2QuoteRequest')(body))throw new ApiError('INVALID_REQUEST','请选择店铺并填写有效的整数数量。');
      return post(`/supplier-offers/${id}/quotes`,contract<Quote>('M2Quote'),body);
    },
    listMappings: (filters:M2Filters={limit:20,offset:0})=>request(`/sku-mappings?${new URLSearchParams(m2Query(filters,['storeId','salesSkuId','status']))}`,contract<MappingPage>('M2MappingPage')),
    async createMapping(body:MappingCreate,key:string) {
      if(!contract<MappingCreate>('M2MappingCreate')(body)||!key)throw new ApiError('INVALID_REQUEST','映射信息不符合合同。');
      return post('/sku-mappings',contract<Mapping>('M2Mapping'),body,{'Idempotency-Key':key});
    },
    async updateMapping(id:string,body:MappingUpdate) {
      if(!uuid(id)||!contract<MappingUpdate>('M2MappingUpdate')(body))throw new ApiError('INVALID_REQUEST','映射审核信息不符合合同。');
      return post(`/sku-mappings/${id}`,contract<Mapping>('M2Mapping'),body,{},'PATCH');
    },
    getPolicy: (storeId?:string)=>request(storeId?`/procurement-policies/${encodeURIComponent(storeId)}`:'/procurement-policy',contract<Policy>('M2Policy')),
    async updatePolicy(body:PolicyUpdate,storeId?:string) {
      if(!contract<PolicyUpdate>('M2PolicyUpdate')(body)||(storeId&&!uuid(storeId)))throw new ApiError('INVALID_REQUEST','采购规则不符合合同，请填写非负整数分。');
      return post(storeId?`/procurement-policies/${storeId}`:'/procurement-policy',contract<Policy>('M2Policy'),body,{},'PUT');
    },
    async evaluateProcurement(salesOrderItemId:string) {
      if(!uuid(salesOrderItemId))throw new ApiError('INVALID_REQUEST','请选择有效的销售子项。');
      return post('/purchase-intents/evaluate',contract<Evaluation>('M2Evaluation'),{salesOrderItemId});
    },
    async createPurchaseIntent(body:IntentCreate,key:string) {
      if(!contract<IntentCreate>('M2IntentCreate')(body)||!key)throw new ApiError('INVALID_REQUEST','采购意图必须引用有效的子项与报价。');
      return post('/purchase-intents',contract<Intent>('M2Intent'),body,{'Idempotency-Key':key});
    },
    listIntents: (filters:M2Filters={limit:20,offset:0})=>request(`/purchase-intents?${new URLSearchParams(m2Query(filters,['storeId','state']))}`,contract<IntentPage>('M2IntentPage')),
    getIntent: (id:string)=>request(`/purchase-intents/${encodeURIComponent(id)}`,contract<Intent>('M2Intent')),
    listPurchases: (filters:M2Filters={limit:20,offset:0})=>request(`/purchases?${new URLSearchParams(m2Query(filters,['storeId','state']))}`,contract<PurchasePage>('M2PurchasePage')),
    getPurchase: (id:string)=>request(`/purchases/${encodeURIComponent(id)}`,contract<Purchase>('M2Purchase')),
    getPaymentPolicy:()=>request('/payment-policy',m3Contract<PaymentPolicy>('M3Policy')),
    async updatePaymentPolicy(body:PaymentPolicyUpdate,key:string) {
      if(!m3Contract<PaymentPolicyUpdate>('M3PolicyUpdate')(body)||!key)throw new ApiError('INVALID_REQUEST','付款策略需非负整数分和当前版本。');
      return post('/payment-policy',m3Contract<PaymentPolicy>('M3Policy'),body,{'Idempotency-Key':key},'PUT');
    },
    listMandates:(limit=20,offset=0)=>request(`/payment-mandates?${new URLSearchParams(paymentQuery({limit,offset},false))}`,m3Contract<MandatePage>('M3MandatePage')),
    async updateMandate(id:string,body:MandateUpdate) {
      if(!uuid(id)||!m3Contract<MandateUpdate>('M3MandateUpdate')(body))throw new ApiError('INVALID_REQUEST','Mock 签约状态更新不符合合同。');
      return post(`/payment-mandates/${id}/mock-state`,m3Contract<Mandate>('M3Mandate'),body,{},'PATCH');
    },
    listPayments:(filters:PaymentFilters={limit:20,offset:0})=>request(`/payment-queue?${new URLSearchParams(paymentQuery(filters))}`,m3Contract<PaymentPage>('M3PaymentPage')),
    getPaymentStatus:(id:string)=>request(`/purchases/${encodeURIComponent(id)}/payment-status`,m3Contract<PaymentView>('M3PaymentView')),
    async requestPayment(id:string,key:string) {
      if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效采购单。');
      return post(`/purchases/${id}/payments`,m3Contract<PaymentView>('M3PaymentView'),undefined,{'Idempotency-Key':key});
    },
    async getCheckoutEntry(id:string,key:string) {
      if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效采购单。');
      const entry=await post(`/purchases/${id}/checkout-entry`,m3Contract<CheckoutEntry>('M3CheckoutEntry'),undefined,{'Idempotency-Key':key});
      if(entry.purchaseId!==id||entry.url!==`/payments/mock/${entry.attemptId}`)throw new ApiError('INVALID_RESPONSE','Mock 付款入口关联不符。');
      return entry;
    },
    getMockCheckout:(id:string)=>request(`/payment-attempts/${encodeURIComponent(id)}/mock-checkout`,m3Contract<MockCheckout>('M3MockCheckout')),
    async simulatePaymentReceipt(id:string,body:ReceiptRequest,key:string) {
      if(!uuid(id)||!m3Contract<ReceiptRequest>('M3ReceiptRequest')(body)||!key)throw new ApiError('INVALID_REQUEST','仅允许合成 Mock 回执。');
      return post(`/mock/payment-attempts/${id}/receipt`,m3Contract<PaymentView>('M3PaymentView'),body,{'Idempotency-Key':key});
    },
    async reconcilePayment(id:string,key:string) {
      if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效采购单。');
      return post(`/purchases/${id}/payment-reconcile`,m3Contract<PaymentView>('M3PaymentView'),undefined,{'Idempotency-Key':key});
    },
    getPaymentConfig:(id:string)=>request(`/mock/purchases/${encodeURIComponent(id)}/payment-config`,m3Contract<PaymentConfig>('M3Config')),
    async updatePaymentConfig(id:string,body:PaymentConfigUpdate) {
      if(!uuid(id)||!m3Contract<PaymentConfigUpdate>('M3ConfigUpdate')(body))throw new ApiError('INVALID_REQUEST','Mock 付款故障配置需版本。');
      return post(`/mock/purchases/${id}/payment-config`,m3Contract<PaymentConfig>('M3Config'),body,{},'PATCH');
    },
    listReconciliations:(filters:PaymentFilters={limit:20,offset:0})=>request(`/payment-reconciliations?${new URLSearchParams(paymentQuery(filters,false))}`,m3Contract<ReconciliationPage>('M3ReconciliationPage')),
    listShipments:(filters:Partial<m4.M4Filters>={})=>request(`/shipments?${new URLSearchParams(m4.m4Query(filters,'shipment'))}`,m4.contract<m4.ShipmentPage>('M4ShipmentPage')),
    listAftersales:(filters:Partial<m4.M4Filters>={})=>request(`/aftersales?${new URLSearchParams(m4.m4Query(filters,'aftersale'))}`,m4.contract<m4.AftersalePage>('M4AftersalePage')),
    listReportOrders:(filters:Partial<m4.M4Filters>={})=>request(`/reports/orders?${new URLSearchParams(m4.m4Query(filters,'report'))}`,(value):value is m4.ReportOrderPage=>m4.contract<m4.ReportOrderPage>('M4ReportOrderPage')(value)&&value.items.every(item=>item.actualNetProfitFen===null)),
    listNotifications:(filters:Partial<m4.M4Filters>={})=>request(`/notifications?${new URLSearchParams(m4.m4Query(filters,'notification'))}`,m4.contract<m4.NotificationPage>('M4NotificationPage')),
    getShipment:(id:string)=>request(`/shipments/${encodeURIComponent(id)}`,m4.contract<m4.ShipmentDetail>('M4ShipmentDetail')),
    getAftersale:(id:string)=>request(`/aftersales/${encodeURIComponent(id)}`,m4.contract<m4.AftersaleDetail>('M4AftersaleDetail')),
    getReportOverview:(filters:Partial<m4.M4Filters>={})=>request(`/reports/overview?${new URLSearchParams(m4.m4Query(filters,'report',false))}`,(value):value is m4.ReportOverview=>m4.contract<m4.ReportOverview>('M4ReportOverview')(value)&&value.actualNetProfitFen===null),
    listOrderActivity:(id:string,filters:Partial<m4.M4Filters>={})=>request(`/orders/${encodeURIComponent(id)}/activity?${new URLSearchParams(m4.m4Query(filters,'activity'))}`,m4.contract<m4.ActivityPage>('M4ActivityPage')),
    async simulateShipment(id:string,body:m4.ShipmentEventRequest,key:string){
      if(!uuid(id)||!key||!m4.contract<m4.ShipmentEventRequest>('M4ShipmentEventRequest')(body))throw new ApiError('INVALID_REQUEST','仅支持有效的合成 Mock 请求、整数分和当前版本。');
      return post(`/mock/purchases/${id}/shipment-events`,m4.contract<m4.EventAccepted>('M4EventAccepted'),body,{'Idempotency-Key':key},'POST');
    },
    async configureShipmentSync(id:string,body:m4.SyncConfigRequest,key:string){
      if(!uuid(id)||!key||!m4.contract<m4.SyncConfigRequest>('M4SyncConfigRequest')(body))throw new ApiError('INVALID_REQUEST','仅支持有效的合成 Mock 请求、整数分和当前版本。');
      return post(`/mock/shipments/${id}/sync-config`,m4.contract<m4.ShipmentDetail>('M4ShipmentDetail'),body,{'Idempotency-Key':key},'PATCH');
    },
    async updateAftersale(id:string,body:m4.AftersaleActionRequest,key:string){
      if(!uuid(id)||!key||!m4.contract<m4.AftersaleActionRequest>('M4AftersaleActionRequest')(body))throw new ApiError('INVALID_REQUEST','仅支持有效的合成 Mock 请求、整数分和当前版本。');
      return post(`/aftersales/${id}`,m4.contract<m4.AftersaleDetail>('M4AftersaleDetail'),body,{'Idempotency-Key':key},'PATCH');
    },
    async simulateAftersale(id:string,body:m4.AftersaleEventRequest,key:string){
      if(!uuid(id)||!key||!m4.contract<m4.AftersaleEventRequest>('M4AftersaleEventRequest')(body))throw new ApiError('INVALID_REQUEST','仅支持有效的合成 Mock 请求、整数分和当前版本。');
      return post(`/mock/orders/${id}/aftersale-events`,m4.contract<m4.EventAccepted>('M4EventAccepted'),body,{'Idempotency-Key':key},'POST');
    },
    async simulateSupplierAftersale(id:string,body:m4.SupplierAftersaleEventRequest,key:string){
      if(!uuid(id)||!key||!m4.contract<m4.SupplierAftersaleEventRequest>('M4SupplierAftersaleEventRequest')(body))throw new ApiError('INVALID_REQUEST','仅支持有效的合成 Mock 请求、整数分和当前版本。');
      return post(`/mock/aftersales/${id}/supplier-events`,m4.contract<m4.EventAccepted>('M4EventAccepted'),body,{'Idempotency-Key':key},'POST');
    },
    async retryShipmentSync(id:string,key:string){if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效记录。');return post(`/shipments/${id}/sync-retry`,m4.contract<m4.ShipmentDetail>('M4ShipmentDetail'),undefined,{'Idempotency-Key':key});},
    async reconcileAftersale(id:string,key:string){if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效记录。');return post(`/aftersales/${id}/reconcile`,m4.contract<m4.AftersaleDetail>('M4AftersaleDetail'),undefined,{'Idempotency-Key':key});},
    async readNotification(id:string,key:string){if(!uuid(id)||!key)throw new ApiError('INVALID_REQUEST','请选择有效记录。');return post(`/notifications/${id}/read`,m4.contract<m4.Notification>('M4Notification'),undefined,{'Idempotency-Key':key});},
    async refreshReports(key:string){if(!key)throw new ApiError('INVALID_REQUEST','缺少幂等键。');return post('/reports/refresh',m4.contract<m4.RefreshAccepted>('M4RefreshAccepted'),undefined,{'Idempotency-Key':key});},
    getMe: () => request("/me", isMe),
    getSystemStatus: () => request("/system/status", isStatus),
    async login(persona: Persona) {
      const me = await post("/auth/login", isMe, { persona });
      csrf = null;
      await refreshCsrf();
      return me;
    },
    async logout() {
      await post(
        "/auth/logout",
        (value): value is { loggedOut: true } =>
          record(value) && value.loggedOut === true,
      );
      csrf = null;
    },
  };
}

export const api = createApiClient();

export function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError("UNEXPECTED_ERROR", "操作未完成，请稍后重试。");
}
