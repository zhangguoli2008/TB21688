-- Only permissions, never pre-completed fulfillment/refund transactions.
UPDATE roles SET permissions=permissions || '["logistics.read","logistics.manage","aftersales.read","aftersales.manage","aftersales.simulate","reports.read","reports.manage","notifications.read"]'::jsonb
WHERE name='OWNER' AND tenant_id IN ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222') AND NOT permissions ? 'reports.manage';
UPDATE roles SET permissions=permissions || '["logistics.read","aftersales.read","aftersales.manage","notifications.read"]'::jsonb
WHERE name='SUPPORT' AND tenant_id='11111111-1111-4111-8111-111111111111' AND NOT permissions ? 'aftersales.manage';
