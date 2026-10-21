-- Synthetic payment defaults are paused; activation requires an explicit owner action.
UPDATE roles SET permissions=permissions || '["payments.read","payments.manual","payments.manage","paymentPolicy.manage"]'::jsonb
WHERE name='OWNER' AND tenant_id IN ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222') AND NOT permissions ? 'paymentPolicy.manage';
UPDATE roles SET permissions=permissions || '["payments.read"]'::jsonb
WHERE name='SUPPORT' AND tenant_id='11111111-1111-4111-8111-111111111111' AND NOT permissions ? 'payments.read';
INSERT INTO payment_policies(tenant_id,mode,max_single_fen,max_daily_fen,min_estimated_profit_fen,is_paused,max_store_single_fen,max_store_daily_fen)
SELECT id,'MANUAL',50000,200000,0,true,50000,200000 FROM tenants WHERE id IN ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222') ON CONFLICT DO NOTHING;
INSERT INTO payment_mandates(tenant_id,supplier_account_id,provider,status,max_single_fen)
SELECT tenant_id,id,'MOCK_PAYMENT','PENDING',50000 FROM supplier_accounts WHERE provider='MOCK_SUPPLIER' AND external_account_id LIKE 'MOCK-%' ON CONFLICT DO NOTHING;
