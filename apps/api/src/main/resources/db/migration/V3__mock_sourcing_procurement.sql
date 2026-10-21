-- M2 synthetic sourcing and procurement only; V1/V2 remain immutable.
ALTER TABLE supplier_offers ADD COLUMN compatible_sales_sku_id VARCHAR(128) NOT NULL DEFAULT 'MOCK-UNREVIEWED';
ALTER TABLE supplier_offers ADD COLUMN specification VARCHAR(400) NOT NULL DEFAULT '合成规格，待人工核对';
ALTER TABLE supplier_offers ADD COLUMN stock_qty INTEGER NOT NULL DEFAULT 100 CHECK(stock_qty BETWEEN 0 AND 1000000);
ALTER TABLE supplier_offers ADD COLUMN min_order_qty INTEGER NOT NULL DEFAULT 1 CHECK(min_order_qty BETWEEN 1 AND 1000000);
ALTER TABLE supplier_offers ADD COLUMN shipping_fen BIGINT NOT NULL DEFAULT 0 CHECK(shipping_fen>=0);
ALTER TABLE supplier_offers ADD COLUMN fee_fen BIGINT NOT NULL DEFAULT 0 CHECK(fee_fen>=0);
ALTER TABLE supplier_offers ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY');
ALTER TABLE supplier_offers ADD COLUMN regions JSONB NOT NULL DEFAULT '["CN"]'::jsonb CHECK(jsonb_typeof(regions)='array');
ALTER TABLE supplier_offers ADD COLUMN ciphertext_platforms JSONB NOT NULL DEFAULT '["DOUYIN","TAOBAO"]'::jsonb CHECK(jsonb_typeof(ciphertext_platforms)='array');
ALTER TABLE supplier_offers ADD COLUMN quote_valid_seconds INTEGER NOT NULL DEFAULT 300 CHECK(quote_valid_seconds BETWEEN 1 AND 3600);
ALTER TABLE supplier_offers ADD COLUMN mock_behavior VARCHAR(32) NOT NULL DEFAULT 'NORMAL' CHECK(mock_behavior IN ('NORMAL','OUT_OF_STOCK','PRICE_RISE','QUOTE_TIMEOUT','CREATE_TIMEOUT_BEFORE','CREATE_TIMEOUT_AFTER','QUERY_TIMEOUT'));
ALTER TABLE supplier_offers ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0);
CREATE INDEX idx_offers_tenant_sku ON supplier_offers(tenant_id,compatible_sales_sku_id,id);
ALTER TABLE sku_mappings ADD COLUMN review_acknowledged BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sku_mappings ADD CONSTRAINT ck_mapping_status CHECK(status IN ('REVIEW','CONFIRMED','PAUSED'));
ALTER TABLE sku_mappings ADD CONSTRAINT ck_mapping_confirmation CHECK(status<>'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND review_acknowledged));
ALTER TABLE sku_mappings ADD CONSTRAINT ck_mapping_priority CHECK(priority BETWEEN 0 AND 10000);
CREATE INDEX idx_mapping_lookup ON sku_mappings(tenant_id,store_id,sales_sku_id,status,priority,id);
CREATE TABLE procurement_policies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), store_id UUID,
  enabled BOOLEAN NOT NULL DEFAULT true,
  max_single_fen BIGINT NOT NULL CHECK(max_single_fen BETWEEN 0 AND 1000000000000),
  max_daily_fen BIGINT NOT NULL CHECK(max_daily_fen BETWEEN 0 AND 1000000000000),
  max_shipping_fen BIGINT NOT NULL DEFAULT 10000 CHECK(max_shipping_fen BETWEEN 0 AND 1000000000),
  min_profit_fen BIGINT NOT NULL DEFAULT 0 CHECK(min_profit_fen BETWEEN 0 AND 1000000000),
  platform_fee_fen BIGINT NOT NULL DEFAULT 0 CHECK(platform_fee_fen BETWEEN 0 AND 1000000000),
  reserve_fen BIGINT NOT NULL DEFAULT 0 CHECK(reserve_fen BETWEEN 0 AND 1000000000),
  region VARCHAR(8) NOT NULL DEFAULT 'CN' CHECK(region='CN'),
  version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE UNIQUE INDEX uq_procurement_tenant_policy ON procurement_policies(tenant_id) WHERE store_id IS NULL;
CREATE UNIQUE INDEX uq_procurement_store_policy ON procurement_policies(tenant_id,store_id) WHERE store_id IS NOT NULL;
CREATE TABLE supplier_quotes (
  id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), store_id UUID NOT NULL,
  supplier_offer_id UUID NOT NULL, supplier_account_id UUID NOT NULL, qty INTEGER NOT NULL CHECK(qty>0),
  total_fen BIGINT NOT NULL CHECK(total_fen>=0),currency CHAR(3) NOT NULL CHECK(currency='CNY'),
  offer_version BIGINT NOT NULL CHECK(offer_version>=0), expires_at TIMESTAMPTZ NOT NULL,
  snapshot JSONB NOT NULL CHECK(jsonb_typeof(snapshot)='object'), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_offer_id) REFERENCES supplier_offers(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id)
);
CREATE INDEX idx_quotes_tenant_offer ON supplier_quotes(tenant_id,supplier_offer_id,created_at DESC,id);
ALTER TABLE purchase_intents ADD COLUMN quote_id UUID;
ALTER TABLE purchase_intents ADD COLUMN evaluation_snapshot JSONB CHECK(evaluation_snapshot IS NULL OR jsonb_typeof(evaluation_snapshot)='object');
ALTER TABLE purchase_intents ADD COLUMN mapping_version BIGINT;
ALTER TABLE purchase_intents ADD COLUMN sales_item_version BIGINT;
ALTER TABLE purchase_intents ADD COLUMN sales_source_version BIGINT;
ALTER TABLE purchase_intents ADD COLUMN supplier_offer_id UUID;
ALTER TABLE purchase_intents ADD COLUMN supplier_account_id UUID;
ALTER TABLE purchase_intents ADD COLUMN client_reference VARCHAR(160);
ALTER TABLE purchase_intents ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY');
ALTER TABLE purchase_intents ADD CONSTRAINT fk_intent_quote FOREIGN KEY(tenant_id,quote_id) REFERENCES supplier_quotes(tenant_id,id);
ALTER TABLE purchase_intents ADD CONSTRAINT fk_intent_offer FOREIGN KEY(tenant_id,supplier_offer_id) REFERENCES supplier_offers(tenant_id,id);
ALTER TABLE purchase_intents ADD CONSTRAINT fk_intent_account FOREIGN KEY(tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id);
CREATE UNIQUE INDEX uq_intent_client_reference ON purchase_intents(tenant_id,supplier_account_id,client_reference) WHERE client_reference IS NOT NULL;
CREATE INDEX idx_intents_tenant_created ON purchase_intents(tenant_id,created_at DESC,id DESC);
ALTER TABLE purchase_orders ADD COLUMN client_reference VARCHAR(160);
CREATE TABLE mock_purchase_authority (
  tenant_id UUID NOT NULL, supplier_account_id UUID NOT NULL, client_reference VARCHAR(160) NOT NULL,
  external_order_id VARCHAR(128) NOT NULL, offer_id UUID NOT NULL, qty INTEGER NOT NULL CHECK(qty>0),
  total_fen BIGINT NOT NULL CHECK(total_fen>=0),currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
  state VARCHAR(32) NOT NULL DEFAULT 'AWAITING_PAYMENT' CHECK(state='AWAITING_PAYMENT'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,supplier_account_id,client_reference),
  UNIQUE(tenant_id,supplier_account_id,external_order_id),
  FOREIGN KEY(tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id),
  FOREIGN KEY(tenant_id,offer_id) REFERENCES supplier_offers(tenant_id,id)
);
CREATE UNIQUE INDEX uq_outbox_m2_intent ON outbox_events(tenant_id,aggregate_id) WHERE event_type='M2_PURCHASE_CREATE';
