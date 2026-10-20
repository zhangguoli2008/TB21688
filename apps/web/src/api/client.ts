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
        if (response.status === 403 && ["POST", "PATCH"].includes(options.method ?? "")) csrf = null;
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
