-- Repeatable synthetic fixture setup. Do not reset store edits, authorization or order state.
UPDATE roles SET permissions='["reports.read","system.read","demo.read","stores.read","stores.manage","orders.read","orders.simulate"]'::jsonb
WHERE id IN ('a0000000-0000-4000-8000-000000000001','b0000000-0000-4000-8000-000000000001')
AND permissions='["reports.read","system.read","demo.read"]'::jsonb;
UPDATE roles SET permissions='["demo.read","stores.read","orders.read"]'::jsonb
WHERE id='a0000000-0000-4000-8000-000000000002' AND permissions='["demo.read"]'::jsonb;
INSERT INTO stores(id,tenant_id,platform,external_shop_id,display_name,auth_status,authorization_expires_at,integration_id) VALUES
('a1000000-0000-4000-8000-000000000001','11111111-1111-4111-8111-111111111111','DOUYIN','MOCK-A-DOUYIN','青禾生活 · 抖店 Mock','ACTIVE',now()+interval '30 days','a1100000-0000-4000-8000-000000000001'),
('a1000000-0000-4000-8000-000000000002','11111111-1111-4111-8111-111111111111','TAOBAO','MOCK-A-TAOBAO','青禾生活 · 淘宝 Mock','ACTIVE',now()+interval '30 days','a1100000-0000-4000-8000-000000000002'),
('b1000000-0000-4000-8000-000000000001','22222222-2222-4222-8222-222222222222','DOUYIN','MOCK-B-DOUYIN','远山选物 · 抖店 Mock','ACTIVE',now()+interval '30 days','b1100000-0000-4000-8000-000000000001'),
('b1000000-0000-4000-8000-000000000002','22222222-2222-4222-8222-222222222222','TAOBAO','MOCK-B-TAOBAO','远山选物 · 淘宝 Mock','ACTIVE',now()+interval '30 days','b1100000-0000-4000-8000-000000000002') ON CONFLICT DO NOTHING;
INSERT INTO member_store_access(tenant_id,tenant_member_id,store_id)
SELECT tenant_id,id,'a1000000-0000-4000-8000-000000000001'::uuid FROM tenant_members
WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'
ON CONFLICT DO NOTHING;
