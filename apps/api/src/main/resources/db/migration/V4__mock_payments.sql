-- M3 is a synthetic payment simulator. No real provider credential or payment endpoint is stored.
ALTER TABLE payment_policies ADD COLUMN max_store_single_fen BIGINT NOT NULL DEFAULT 50000 CHECK(max_store_single_fen BETWEEN 0 AND 1000000000000);
ALTER TABLE payment_policies ADD COLUMN max_store_daily_fen BIGINT NOT NULL DEFAULT 200000 CHECK(max_store_daily_fen BETWEEN 0 AND 1000000000000);
ALTER TABLE payment_policies ADD COLUMN new_supplier_first_order_manual BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE payment_policies ADD CONSTRAINT ck_payment_policy_amounts CHECK(max_single_fen<=1000000000000 AND max_daily_fen<=1000000000000 AND min_estimated_profit_fen BETWEEN 0 AND 1000000000000);
ALTER TABLE payment_policies ADD CONSTRAINT ck_payment_policy_version CHECK(version BETWEEN 0 AND 9007199254740991);
ALTER TABLE payment_mandates ADD CONSTRAINT ck_payment_mandate_status CHECK(status IN ('PENDING','ACTIVE','EXPIRED','REVOKED'));
ALTER TABLE payment_mandates ADD CONSTRAINT ck_payment_mandate_amount CHECK(max_single_fen IS NULL OR max_single_fen<=1000000000000);
CREATE UNIQUE INDEX uq_payment_mandate_account ON payment_mandates(tenant_id,supplier_account_id);
ALTER TABLE payment_attempts ADD COLUMN policy_version BIGINT NOT NULL DEFAULT 0 CHECK(policy_version>=0);
ALTER TABLE payment_attempts ADD COLUMN last_verified_at TIMESTAMPTZ;
ALTER TABLE payment_attempts ADD COLUMN reconciliation_count INTEGER NOT NULL DEFAULT 0 CHECK(reconciliation_count>=0);
ALTER TABLE payment_attempts ADD COLUMN checkout_expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '30 minutes';
ALTER TABLE payment_attempts ADD CONSTRAINT ck_payment_attempt_mode CHECK(mode IN ('MANUAL','AUTO','RULE_BASED'));
ALTER TABLE payment_attempts ADD CONSTRAINT ck_payment_attempt_state CHECK(state IN ('NEW','PAYING','UNKNOWN','PAID','FAILED','MANUAL_RECONCILIATION'));
ALTER TABLE payment_attempts ADD CONSTRAINT ck_payment_attempt_amount CHECK(amount_fen<=1000000000000 AND currency='CNY');
-- M3 permits one effect per purchase. FAILED is terminal; a future reviewed retry design must preserve this safety boundary.
CREATE UNIQUE INDEX uq_payment_attempt_purchase ON payment_attempts(tenant_id,purchase_order_id);
ALTER TABLE payment_budget_reservations ADD CONSTRAINT ck_payment_budget_state CHECK(state IN ('RESERVED','CONSUMED','RELEASED'));
CREATE INDEX idx_payment_budget_day ON payment_budget_reservations(tenant_id,budget_date,state);
ALTER TABLE purchase_orders ADD COLUMN payment_decision_code VARCHAR(64);
CREATE TABLE mock_payment_configs (
 tenant_id UUID NOT NULL, purchase_order_id UUID NOT NULL,
 behavior VARCHAR(32) NOT NULL DEFAULT 'NORMAL' CHECK(behavior IN ('NORMAL','DECLINED','PAY_TIMEOUT_BEFORE','PAY_TIMEOUT_AFTER','QUERY_TIMEOUT','AUTHORITY_MISMATCH')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,purchase_order_id), FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES purchase_orders(tenant_id,id)
);
CREATE TABLE mock_payment_authority (
 tenant_id UUID NOT NULL, purchase_order_id UUID NOT NULL, supplier_account_id UUID NOT NULL,
 external_order_id VARCHAR(128) NOT NULL, provider_key VARCHAR(160) NOT NULL,
 amount_fen BIGINT NOT NULL CHECK(amount_fen BETWEEN 0 AND 1000000000000), currency CHAR(3) NOT NULL CHECK(currency='CNY'),
 state VARCHAR(16) NOT NULL CHECK(state IN ('UNPAID','PAID','FAILED')),
 payment_reference VARCHAR(160), pay_calls INTEGER NOT NULL DEFAULT 0 CHECK(pay_calls>=0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,purchase_order_id), UNIQUE(tenant_id,provider_key),
 FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES purchase_orders(tenant_id,id), FOREIGN KEY(tenant_id,supplier_account_id) REFERENCES supplier_accounts(tenant_id,id)
);
CREATE TABLE payment_reconciliations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, purchase_order_id UUID NOT NULL, payment_attempt_id UUID NOT NULL,
 outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('PAID','FAILED','UNPAID','UNKNOWN','MISMATCH')), local_state VARCHAR(32) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES purchase_orders(tenant_id,id), FOREIGN KEY(tenant_id,payment_attempt_id) REFERENCES payment_attempts(tenant_id,id)
);
CREATE INDEX idx_payment_reconciliation_tenant ON payment_reconciliations(tenant_id,created_at DESC,id DESC);
CREATE UNIQUE INDEX uq_outbox_payment ON outbox_events(tenant_id,aggregate_id) WHERE event_type='M3_PAYMENT';
