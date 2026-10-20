# 08 内部REST API与事件规范

完整可机器读取的起始合同见 `contracts/openapi.yaml`。这是**自有SaaS API**，不是淘宝、抖店、1688或拼多多官方接口。开发应契约优先；完成每个模块时扩充OpenAPI。

## 公共约定
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
