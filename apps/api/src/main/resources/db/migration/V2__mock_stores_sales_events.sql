-- M1 only. V1 is deliberately immutable; no purchase or payment behavior is enabled.
ALTER TABLE stores ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE stores ADD COLUMN integration_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE;
ALTER TABLE stores ADD CONSTRAINT ck_store_platform CHECK(platform IN ('DOUYIN','TAOBAO'));
ALTER TABLE stores ADD CONSTRAINT ck_store_auth CHECK(auth_status IN ('PENDING','ACTIVE','EXPIRED','REVOKED'));
ALTER TABLE sales_orders ADD COLUMN source_version BIGINT NOT NULL DEFAULT 0 CHECK(source_version>=0);
ALTER TABLE sales_orders ADD COLUMN source_digest CHAR(64);
CREATE INDEX idx_orders_tenant_external ON sales_orders(tenant_id,external_order_id);
CREATE INDEX idx_orders_tenant_source_time ON sales_orders(tenant_id,source_updated_at);
CREATE INDEX idx_orders_tenant_store_created ON sales_orders(tenant_id,store_id,created_at DESC,id DESC);
CREATE TABLE mock_order_authority (
  tenant_id UUID NOT NULL, store_id UUID NOT NULL, external_order_id VARCHAR(80) NOT NULL,
  source_version BIGINT NOT NULL CHECK(source_version>0), snapshot JSONB NOT NULL,
  snapshot_digest CHAR(64) NOT NULL,
  PRIMARY KEY(tenant_id,store_id,external_order_id),
  FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id),
  CHECK(jsonb_typeof(snapshot)='object')
);
CREATE TABLE sales_order_timeline (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
  sales_order_id UUID NOT NULL, event_id VARCHAR(128) NOT NULL,
  action VARCHAR(64) NOT NULL, source_version BIGINT NOT NULL CHECK(source_version>0),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,sales_order_id,event_id),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id)
);
CREATE INDEX idx_timeline_order ON sales_order_timeline(tenant_id,sales_order_id,occurred_at,id);
CREATE TABLE m1_command_keys (
  tenant_id UUID NOT NULL REFERENCES tenants(id), command_key VARCHAR(160) NOT NULL,
  operation VARCHAR(200) NOT NULL, request_digest CHAR(64) NOT NULL,
  response JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,command_key), CHECK(response IS NULL OR jsonb_typeof(response)='object')
);
ALTER TABLE inbox_events ADD COLUMN store_id UUID;
ALTER TABLE inbox_events ADD COLUMN external_order_id VARCHAR(80);
ALTER TABLE inbox_events ADD COLUMN source_version BIGINT;
ALTER TABLE inbox_events ADD COLUMN event_occurred_at TIMESTAMPTZ;
ALTER TABLE inbox_events ADD COLUMN payload_digest CHAR(64);
ALTER TABLE inbox_events ADD COLUMN nonce UUID;
ALTER TABLE inbox_events ADD CONSTRAINT fk_inbox_store FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id);
CREATE UNIQUE INDEX uq_inbox_nonce ON inbox_events(provider,integration_ref,nonce) WHERE nonce IS NOT NULL;
CREATE UNIQUE INDEX uq_inbox_tenant_id ON inbox_events(tenant_id,id);
ALTER TABLE outbox_events ADD COLUMN inbox_id UUID;
ALTER TABLE outbox_events ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE outbox_events ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN lease_token UUID;
ALTER TABLE outbox_events ADD COLUMN last_error_code VARCHAR(64);
ALTER TABLE outbox_events ADD CONSTRAINT fk_outbox_inbox FOREIGN KEY(tenant_id,inbox_id) REFERENCES inbox_events(tenant_id,id);
CREATE UNIQUE INDEX uq_outbox_inbox ON outbox_events(tenant_id,inbox_id) WHERE inbox_id IS NOT NULL;
CREATE INDEX idx_outbox_m1_due ON outbox_events(event_type,state,available_at,lease_until);
