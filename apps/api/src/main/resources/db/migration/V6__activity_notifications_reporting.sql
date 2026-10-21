-- M4 local event projection and safe in-app activity; never a general ledger or actual net profit.
CREATE TABLE order_activities (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 sales_order_id UUID NOT NULL, store_id UUID NOT NULL, actor_id UUID REFERENCES users(id),
 source VARCHAR(40) NOT NULL, action VARCHAR(100) NOT NULL, entity_type VARCHAR(80) NOT NULL, entity_id UUID NOT NULL,
 code VARCHAR(100), event_key VARCHAR(200) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,event_key),
 FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id), FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE INDEX idx_activity_order ON order_activities(tenant_id,sales_order_id,occurred_at DESC,id DESC);
CREATE TABLE notifications (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 sales_order_id UUID NOT NULL, store_id UUID NOT NULL, category VARCHAR(40) NOT NULL, code VARCHAR(100) NOT NULL,
 event_key VARCHAR(200) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,event_key),
 FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id), FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE INDEX idx_notification_tenant ON notifications(tenant_id,created_at DESC,id DESC);
CREATE TABLE notification_reads (
 tenant_id UUID NOT NULL REFERENCES tenants(id), notification_id UUID NOT NULL, user_id UUID NOT NULL REFERENCES users(id), read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,notification_id,user_id), FOREIGN KEY(tenant_id,notification_id) REFERENCES notifications(tenant_id,id)
);
CREATE TABLE report_order_projections (
 tenant_id UUID NOT NULL REFERENCES tenants(id), sales_order_id UUID NOT NULL, store_id UUID NOT NULL,
 external_order_id VARCHAR(128) NOT NULL, order_created_at TIMESTAMPTZ NOT NULL,
 sales_paid_fen BIGINT NOT NULL CHECK(sales_paid_fen>=0), purchase_paid_fen BIGINT NOT NULL CHECK(purchase_paid_fen>=0),
 estimated_purchase_fen BIGINT NOT NULL CHECK(estimated_purchase_fen>=0), estimated_profit_fen BIGINT NOT NULL,
 sales_refunded_fen BIGINT NOT NULL CHECK(sales_refunded_fen>=0), supplier_refunded_fen BIGINT NOT NULL CHECK(supplier_refunded_fen>=0),
 unreconciled_count BIGINT NOT NULL CHECK(unreconciled_count>=0), unreconciled_payment_fen BIGINT NOT NULL CHECK(unreconciled_payment_fen>=0),
 pending_supplier_refund_fen BIGINT NOT NULL CHECK(pending_supplier_refund_fen>=0), as_of TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,sales_order_id), FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_orders(tenant_id,id), FOREIGN KEY(tenant_id,store_id) REFERENCES stores(tenant_id,id)
);
CREATE INDEX idx_report_created ON report_order_projections(tenant_id,order_created_at DESC,sales_order_id DESC);
