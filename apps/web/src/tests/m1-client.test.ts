import { describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { parseOrderFilters, orderQuery, formatFen, shanghaiTime } from '../domain/m1';
import { json } from './fixtures';
import { store, order } from './m1-fixtures';
describe('M1 API contract and safe rendering', () => {
    it('sends CSRF/idempotency and only the allowed store fields; never replays a write', async () => {
        const requests: {
            path: string;
            options: RequestInit;
        }[] = [];
        const client = createApiClient(async (path, options) => { requests.push({ path, options }); return path.endsWith('/csrf') ? json({ token: 'safe-test', headerName: 'X-CSRF-TOKEN' }) : json(store); });
        await client.createStore({ platform: 'TAOBAO', externalShopId: 'shop_a', displayName: '合成店铺 A' }, 'create-1');
        expect(requests.map(r => r.path)).toEqual(['/api/v1/auth/csrf', '/api/v1/stores']);
        const write = requests[1]!.options;
        expect(new Headers(write.headers).get('Idempotency-Key')).toBe('create-1');
        expect(new Headers(write.headers).get('X-CSRF-TOKEN')).toBe('safe-test');
        expect(JSON.parse(write.body as string)).toEqual({ platform: 'TAOBAO', externalShopId: 'shop_a', displayName: '合成店铺 A' });
    });
    it.each([{ ...store, webhookSecret: 'forbidden' }, { ...store, mode: 'OFFICIAL' }, { ...store, version: 0.5 }])('rejects unsafe or malformed stores', async (value) => {
        const client = createApiClient(async () => json({ items: [value], total: 1, limit: 20, offset: 0 }));
        await expect(client.listStores()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    });
    it.each([1.5, Number.MAX_SAFE_INTEGER + 1, -1])('rejects unsafe money %s', async (paidAmountFen) => {
        const client = createApiClient(async () => json({ items: [{ ...order, paidAmountFen }], total: 1, limit: 20, offset: 0 }));
        await expect(client.listOrders({ limit: 20, offset: 0 })).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    });
    it('uses PATCH with CAS version and rejects body fields outside the contract', async () => {
        const requests: RequestInit[] = [];
        const client = createApiClient(async (path, options) => { requests.push(options); return path.endsWith('/csrf') ? json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) : json({ ...store, version: 1, enabled: false }); });
        await client.updateStore(store.id, { version: 0, enabled: false });
        expect(requests[1]?.method).toBe('PATCH');
        expect(JSON.parse(requests[1]?.body as string)).toEqual({ version: 0, enabled: false });
        await expect(client.updateStore(store.id, { version: 1, tenantId: 'other' } as never)).rejects.toMatchObject({ code: 'INVALID_REQUEST' });
    });
    it('rejects a stale response after a session rotation', async () => {
        let resolve!: (response: Response) => void;
        const client = createApiClient(() => new Promise(r => { resolve = r; }));
        const pending = client.listStores();
        client.clearCsrf();
        resolve(json({ items: [store], total: 1, limit: 20, offset: 0 }));
        await expect(pending).rejects.toMatchObject({ code: 'SESSION_CHANGED' });
    });
    it('preserves supported deep-link filters and bounds pagination', () => {
        const filters = parseOrderFilters({ storeId: store.id, sourceStatus: 'PAID', q: 'DEMO_1', limit: '500', offset: '-3', from: '2026-10-20T16:00:00Z', to: '2026-10-21T16:00:00Z' });
        expect(filters).toEqual({ storeId: store.id, sourceStatus: 'PAID', q: 'DEMO_1', limit: 100, offset: 0, from: '2026-10-20T16:00:00.000Z', to: '2026-10-21T16:00:00.000Z' });
        expect(orderQuery(parseOrderFilters({}))).toEqual({ limit: '20', offset: '0' });
        expect(parseOrderFilters({ sourceStatus: 'PAYING', tenantId: 'other', q: ['x', 'y'] })).toEqual({ limit: 20, offset: 0 });
    });
    it('renders integer fen and Shanghai day boundary without rounding business amounts', () => {
        expect(formatFen(19901)).toBe('¥199.01');
        expect(formatFen(0)).toBe('¥0.00');
        expect(shanghaiTime('2026-10-20T16:00:00Z')).toContain('2026/10/21');
        expect(shanghaiTime('2026-10-20T16:00:00Z')).toContain('00:00:00');
    });
});
it('serializes only the allowed order query keys and excludes untrusted tenant IDs', () => {
    expect(orderQuery({ limit: 20, offset: 0, q: 'DEMO_1', tenantId: 'other' } as never)).toEqual({ limit: '20', offset: '0', q: 'DEMO_1' });
});
it('never retries an ambiguous Mock write and retains a structured error', async () => {
    let writes = 0;
    const client = createApiClient(async (path) => {
        if (path.endsWith('/csrf'))
            return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
        writes++;
        throw new TypeError('connection lost after submission');
    });
    await expect(client.simulateOrders(store.id, { scenario: 'PAID', externalOrderId: 'DEMO_1' }, 'event-1')).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(writes).toBe(1);
});
