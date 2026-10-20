# 08 内部REST API与事件规范

完整可机器读取的起始合同见 `contracts/openapi.yaml`。这是**自有SaaS API**，不是淘宝、抖店、1688或拼多多官方接口。开发应契约优先；完成每个模块时扩充OpenAPI。

## 公共约定
- M0 已选本地服务端 `HttpSession`：`SPSESSION` HttpOnly / SameSite=Lax cookie，所有写请求携带从 `/auth/csrf` 取得的 `X-CSRF-TOKEN`，登录后重新获取。demo profile 仅环回 HTTP 时 cookie 允许非 Secure；正式身份系统未实现。演示单实例内存会话30分钟，退出立即撤销，重启即失效。
- M0 演示入口为 `POST /auth/login {persona: OWNER_A|SUPPORT_A|OWNER_B}`；`POST /auth/logout` 退出。仅 demo/test 配置开放，禁止部署公网。`GET /system/status` 返回实际依赖状态；`GET /demo/resources/{id}` 仅用于合成租户隔离验收。M0 不提供注册、刷新、密码重置或切租户接口。
- Base：`/api/v1`，UTF-8 JSON，HTTPS；会话HttpOnly Secure SameSite cookie或受控Bearer token（按正式身份方案确定），服务端统一鉴权。
- API请求Header：`X-Request-Id` 可选；**采购、确认、支付等副作用写接口必须 `Idempotency-Key`**。
- 商家当前租户来源于服务端已验证的用户会话及允许的租户切换，不能直接依赖任意Header传入。
- 响应成功：`{ "data": ..., "requestId": "uuid" }`；失败：`{ "code":"PURCHASE_BLOCKED", "message":"...", "requestId":"...", "details":{} }`。
- UTC ISO8601时间戳；币种 ISO4217；金额整型分（例如 `amountFen=3500`）；百分比使用基点bps。
- 列表 `cursor`, `limit`（默认20，最大100），返回 `nextCursor`；禁止任意无上限导出。

## 主要资源路由
| 动作 | HTTP | 路径 | 权限 |
|---|---|---|---|
| 获取当前用户租户权限 | GET | /me | 登录 |
| 商家店铺列表 | GET | /stores | stores.read |
| 发起受控店铺授权 | POST | /stores/authorizations/start | stores.manage |
| 获取授权状态 | GET | /stores/{storeId}/authorization | stores.read |
| 列订单 | GET | /orders?storeId=&status= | orders.read |
| 查订单 | GET | /orders/{orderId} | orders.read |
| 获取待匹配项 | GET | /sourcing/unmapped | sourcing.read |
| 查候选货源 | POST | /sourcing/candidates | sourcing.read |
| 确认SKU映射 | POST | /sku-mappings | sourcing.manage |
| 查采购意图 | GET | /purchase-intents | purchases.read |
| 发起采购审核 | POST | /purchase-intents/{id}/approve | purchases.approve |
| 下采购任务 | POST | /purchase-intents/{id}/submit | purchases.write |
| 上游采购列表 | GET | /purchases | purchases.read |
| 支付策略 | GET/PUT | /payment-policy | paymentPolicy.manage |
| 签约状态 | GET | /payment-mandates | payments.read |
| 获取官方收银台入口 | POST | /purchases/{id}/checkout-entry | payments.manual |
| 查询支付权威状态 | GET | /purchases/{id}/payment-status | payments.read |
| 售后列表 | GET | /aftersales | aftersales.read |
| 提交取消申请 | POST | /purchases/{id}/cancel-request | purchases.cancel |
| 物流包裹 | GET | /shipments | logistics.read |
| 经营报表 | GET | /reports/overview | reports.read |
| 操作日志 | GET | /audit-logs | audit.read |
| 平台回调 | POST | /webhooks/{platform} | 官方验签和安全网关 |

## 错误码（HTTP仅作外层）
- `UNAUTHENTICATED` 401、`FORBIDDEN` 403、`NOT_FOUND` 404。
- `VALIDATION_ERROR` 400、`STATE_CONFLICT` 409、`IDEMPOTENCY_CONFLICT` 409。
- `CAPABILITY_UNAVAILABLE` 422、`AUTHORIZATION_REQUIRED` 422。
- `PII_HANDOFF_UNAVAILABLE` 422、`MAPPING_UNCONFIRMED` 422。
- `QUOTE_EXPIRED` 422、`RISK_REVIEW_REQUIRED` 422。
- `PAYMENT_STATUS_UNKNOWN` 409；**禁止前端显示付款失败并开放再次支付**。
- `RATE_LIMITED` 429、`PROVIDER_TEMPORARY_FAILURE` 503。
- M0 基础设施或审计不可用：`SERVICE_UNAVAILABLE` 503，审计未持久化不提交成功响应，并撤销相关演示会话。DB `real_` 命名空间启用标志视为配置错误，当前作用域失败关闭（503 `CAPABILITY_UNAVAILABLE`），不能激活真实能力；全局标志作用全局，租户标志只作用所属租户。正常的未获准 Adapter 能力仍返回422。

## 事件总线定义
事件envelope：`eventId, eventType, schemaVersion, tenantId, aggregateType, aggregateId, occurredAt, correlationId, payload`；payload最小化，不包含消费者明文身份。
- `sales.order.paid.v1`
- `sales.order.cancelled.v1`
- `sourcing.mapping.confirmed.v1`
- `purchase.intent.approved.v1`
- `purchase.created.v1`
- `purchase.create_unknown.v1`
- `payment.attempt.started.v1`
- `payment.paid.v1`
- `payment.unknown.v1`
- `shipment.observed.v1`
- `shipment.downstream_ack.v1`
- `aftersale.opened.v1`

消费者必做eventId去重、版本校验、跨租户验证；不支持的schemaVersion进死信队列而非默默丢弃。

## 分页与权限
所有列表过滤服务端授权的租户和店铺集合。管理员跨租户查看严格独立的管理API，默认只显示匿名统计，不复用商家API的任意tenantId参数。
