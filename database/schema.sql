-- SmartProcure v1: PostgreSQL 15+ DDL 草案
-- 此文件供Codex拆分成Flyway V1__*.sql，尚未在生产执行或迁移验证。
-- 业务金额单位: 人民币分（BIGINT）。UTC timestamptz。外部凭据只存secret引用。
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  name VARCHAR(80) NOT NULL,
  permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, name)
);
CREATE TABLE tenant_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID NOT NULL REFERENCES users(id),
  role_id UUID NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, user_id),
  FOREIGN KEY (tenant_id, role_id) REFERENCES roles(tenant_id,id)
);
CREATE TABLE stores (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  platform VARCHAR(24) NOT NULL,
  external_shop_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  auth_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  authorization_expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id,id), UNIQUE (tenant_id,platform,external_shop_id)
);
CREATE TABLE member_store_access (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  tenant_member_id UUID NOT NULL,
  store_id UUID NOT NULL,
  UNIQUE (tenant_id,tenant_member_id,store_id),
  FOREIGN KEY (tenant_id,tenant_member_id) REFERENCES tenant_members(tenant_id,id),
  FOREIGN KEY (tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE TABLE platform_authorizations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  store_id UUID NOT NULL,
  secret_reference VARCHAR(512),
  scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id),
  FOREIGN KEY (tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE TABLE supplier_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  provider VARCHAR(24) NOT NULL,
  external_account_id VARCHAR(128) NOT NULL,
  secret_reference VARCHAR(512),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id), UNIQUE (tenant_id,provider,external_account_id)
);
CREATE TABLE payment_mandates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  supplier_account_id UUID NOT NULL,
  provider VARCHAR(32) NOT NULL,
  mandate_reference VARCHAR(256),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  max_single_fen BIGINT,
  expires_at TIMESTAMPTZ,
  last_verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id,id),
  FOREIGN KEY (tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id)
);
CREATE TABLE payment_policies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id),
  mode VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
  max_single_fen BIGINT NOT NULL DEFAULT 0 CHECK(max_single_fen>=0),
  max_daily_fen BIGINT NOT NULL DEFAULT 0 CHECK(max_daily_fen>=0),
  min_estimated_profit_fen BIGINT NOT NULL DEFAULT 0,
  is_paused BOOLEAN NOT NULL DEFAULT true,
  settings JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CHECK (mode IN ('MANUAL','AUTO','RULE_BASED'))
);
CREATE TABLE sales_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  store_id UUID NOT NULL,
  external_order_id VARCHAR(128) NOT NULL,
  source_status VARCHAR(64) NOT NULL,
  local_status VARCHAR(64) NOT NULL DEFAULT 'NEW',
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  paid_amount_fen BIGINT NOT NULL DEFAULT 0 CHECK (paid_amount_fen>=0),
  paid_at TIMESTAMPTZ,
  promised_ship_at TIMESTAMPTZ,
  source_updated_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id,id), UNIQUE (tenant_id,store_id,external_order_id),
  FOREIGN KEY (tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE TABLE sales_order_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  sales_order_id UUID NOT NULL,
  external_item_id VARCHAR(128) NOT NULL,
  external_sku_id VARCHAR(128) NOT NULL,
  title VARCHAR(400) NOT NULL,
  attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
  qty INTEGER NOT NULL CHECK(qty>0),
  paid_allocated_fen BIGINT NOT NULL CHECK(paid_allocated_fen>=0),
  cancelled_qty INTEGER NOT NULL DEFAULT 0 CHECK(cancelled_qty>=0),
  fulfilled_qty INTEGER NOT NULL DEFAULT 0 CHECK(fulfilled_qty>=0),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,sales_order_id,external_item_id),
  FOREIGN KEY (tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id),
  CHECK(cancelled_qty+fulfilled_qty<=qty)
);
CREATE TABLE supplier_offers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  supplier_account_id UUID NOT NULL,
  external_offer_id VARCHAR(128) NOT NULL,
  external_sku_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(400) NOT NULL,
  last_unit_price_fen BIGINT CHECK(last_unit_price_fen>=0),
  last_quote_at TIMESTAMPTZ,
  fulfillment_capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
  state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,supplier_account_id,external_offer_id,external_sku_id),
  FOREIGN KEY (tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id)
);
CREATE TABLE sku_mappings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  store_id UUID NOT NULL,
  sales_sku_id VARCHAR(128) NOT NULL,
  supplier_offer_id UUID NOT NULL,
  priority INT NOT NULL DEFAULT 100,
  status VARCHAR(24) NOT NULL DEFAULT 'REVIEW',
  confirmed_by UUID REFERENCES users(id),
  confirmed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,store_id,sales_sku_id,supplier_offer_id),
  FOREIGN KEY (tenant_id,store_id) REFERENCES stores(tenant_id,id),
  FOREIGN KEY (tenant_id,supplier_offer_id) REFERENCES supplier_offers(tenant_id,id)
);
CREATE TABLE purchase_intents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  sales_order_item_id UUID NOT NULL,
  fulfillment_slice_no INT NOT NULL DEFAULT 1,
  mapping_id UUID NOT NULL,
  qty INTEGER NOT NULL CHECK(qty>0),
  state VARCHAR(32) NOT NULL DEFAULT 'NEW',
  estimated_total_fen BIGINT CHECK(estimated_total_fen>=0),
  quote_snapshot JSONB,
  policy_snapshot JSONB,
  quote_expires_at TIMESTAMPTZ,
  failure_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,sales_order_item_id,fulfillment_slice_no),
  FOREIGN KEY(tenant_id,sales_order_item_id) REFERENCES sales_order_items(tenant_id,id),
  FOREIGN KEY(tenant_id,mapping_id) REFERENCES sku_mappings(tenant_id,id)
);
CREATE TABLE purchase_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  purchase_intent_id UUID NOT NULL,
  supplier_account_id UUID NOT NULL,
  external_order_id VARCHAR(128),
  state VARCHAR(32) NOT NULL DEFAULT 'CREATING',
  total_fen BIGINT CHECK(total_fen>=0),
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,purchase_intent_id),
  UNIQUE(supplier_account_id,external_order_id),
  FOREIGN KEY(tenant_id,purchase_intent_id) REFERENCES purchase_intents(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id)
);
CREATE TABLE payment_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  purchase_order_id UUID NOT NULL,
  attempt_no INT NOT NULL CHECK(attempt_no>0),
  mode VARCHAR(24) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'NEW',
  amount_fen BIGINT NOT NULL CHECK(amount_fen>=0),
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  mandate_id UUID,
  provider_payment_reference VARCHAR(256),
  idempotency_key VARCHAR(160) NOT NULL,
  failure_code VARCHAR(64),
  started_at TIMESTAMPTZ,
  resolved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,purchase_order_id,attempt_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY (tenant_id,purchase_order_id) REFERENCES purchase_orders(tenant_id,id),
  FOREIGN KEY (tenant_id,mandate_id) REFERENCES payment_mandates(tenant_id,id)
);
CREATE TABLE payment_budget_reservations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  payment_attempt_id UUID NOT NULL,
  budget_date DATE NOT NULL,
  reserved_fen BIGINT NOT NULL CHECK(reserved_fen>=0),
  state VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,payment_attempt_id),
  FOREIGN KEY (tenant_id,payment_attempt_id) REFERENCES payment_attempts(tenant_id,id)
);
CREATE TABLE shipments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  purchase_order_id UUID NOT NULL,
  external_shipment_id VARCHAR(128),
  carrier_code VARCHAR(80),
  tracking_number VARCHAR(128),
  state VARCHAR(32) NOT NULL DEFAULT 'NOT_CREATED',
  shipped_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES purchase_orders(tenant_id,id)
);
CREATE TABLE shipment_syncs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL,
  sales_order_id UUID NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'NOT_SENT',
  platform_ack VARCHAR(256),
  last_error_code VARCHAR(64),
  retry_count INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,shipment_id,sales_order_id),
  FOREIGN KEY(tenant_id,shipment_id) REFERENCES shipments(tenant_id,id),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id)
);
CREATE TABLE aftersales_cases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  sales_order_id UUID NOT NULL,
  source_case_id VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
  requested_refund_fen BIGINT CHECK(requested_refund_fen>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,sales_order_id,source_case_id),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id)
);
CREATE TABLE refund_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  aftersales_case_id UUID NOT NULL,
  supplier_purchase_order_id UUID,
  amount_fen BIGINT NOT NULL CHECK(amount_fen>=0),
  state VARCHAR(32) NOT NULL,
  external_reference VARCHAR(256),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,aftersales_case_id) REFERENCES aftersales_cases(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_purchase_order_id) REFERENCES purchase_orders(tenant_id,id)
);
CREATE TABLE inbox_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider VARCHAR(32) NOT NULL,
  integration_ref VARCHAR(160) NOT NULL,
  event_id VARCHAR(256) NOT NULL,
  event_type VARCHAR(160) NOT NULL,
  tenant_id UUID REFERENCES tenants(id),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  state VARCHAR(24) NOT NULL DEFAULT 'NEW',
  UNIQUE(provider,integration_ref,event_id)
);
CREATE TABLE outbox_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type VARCHAR(128) NOT NULL,
  schema_version INT NOT NULL DEFAULT 1,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  aggregate_id UUID NOT NULL,
  correlation_id VARCHAR(160),
  payload JSONB NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE TABLE audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  actor_user_id UUID REFERENCES users(id),
  action VARCHAR(120) NOT NULL,
  entity_type VARCHAR(80),
  entity_id UUID,
  redacted_details JSONB NOT NULL DEFAULT '{}'::jsonb,
  request_id VARCHAR(160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE plans (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(160) NOT NULL,
  features JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE tenant_subscriptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  plan_id UUID NOT NULL REFERENCES plans(id),
  state VARCHAR(24) NOT NULL,
  starts_at TIMESTAMPTZ,
  ends_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id)
);
CREATE TABLE usage_counters (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  period_start DATE NOT NULL,
  metric_code VARCHAR(64) NOT NULL,
  quantity BIGINT NOT NULL DEFAULT 0 CHECK(quantity>=0),
  UNIQUE(tenant_id,period_start,metric_code)
);
CREATE TABLE feature_flags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  flag_name VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,flag_name)
);
CREATE INDEX idx_sales_orders_tenant_created ON sales_orders(tenant_id,created_at DESC);
CREATE INDEX idx_sales_orders_tenant_status ON sales_orders(tenant_id,local_status,updated_at);
CREATE INDEX idx_purchase_intents_tenant_state ON purchase_intents(tenant_id,state,updated_at);
CREATE INDEX idx_purchase_orders_tenant_state ON purchase_orders(tenant_id,state,updated_at);
CREATE INDEX idx_payments_tenant_state ON payment_attempts(tenant_id,state,created_at);
CREATE INDEX idx_outbox_state ON outbox_events(state,occurred_at);
CREATE INDEX idx_inbox_state ON inbox_events(state,received_at);
CREATE INDEX idx_shipments_order ON shipments(tenant_id,purchase_order_id);
CREATE INDEX idx_audit_tenant_time ON audit_logs(tenant_id,created_at DESC);

-- 后续迁移必须补齐：细粒度规则、通知、导出任务、审批记录、所有FK一致性、
-- Supabase等外部依赖不要直接引入；生产RLS可作为第二道防线，但不能替代服务端鉴权。
