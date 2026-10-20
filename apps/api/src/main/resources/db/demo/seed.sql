-- Synthetic identities only. This script is executed exclusively by demo/test profiles.
-- No password authentication; hash value deliberately cannot authenticate a user.
INSERT INTO tenants(id,name) VALUES
('11111111-1111-4111-8111-111111111111','演示商家 A'),
('22222222-2222-4222-8222-222222222222','演示商家 B') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email,password_hash) VALUES
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','owner.a@example.invalid','!M0_DEMO_AUTH_DISABLED'),
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','support.a@example.invalid','!M0_DEMO_AUTH_DISABLED'),
('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','owner.b@example.invalid','!M0_DEMO_AUTH_DISABLED') ON CONFLICT DO NOTHING;
INSERT INTO roles(id,tenant_id,name,permissions) VALUES
('a0000000-0000-4000-8000-000000000001','11111111-1111-4111-8111-111111111111','OWNER','["reports.read","system.read","demo.read"]'),
('a0000000-0000-4000-8000-000000000002','11111111-1111-4111-8111-111111111111','SUPPORT','["demo.read"]'),
('b0000000-0000-4000-8000-000000000001','22222222-2222-4222-8222-222222222222','OWNER','["reports.read","system.read","demo.read"]') ON CONFLICT DO NOTHING;
INSERT INTO tenant_members(tenant_id,user_id,role_id) VALUES
('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','a0000000-0000-4000-8000-000000000001'),
('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','a0000000-0000-4000-8000-000000000002'),
('22222222-2222-4222-8222-222222222222','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','b0000000-0000-4000-8000-000000000001') ON CONFLICT DO NOTHING;
