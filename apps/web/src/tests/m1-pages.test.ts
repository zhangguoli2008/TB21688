import { createPinia, setActivePinia } from 'pinia';
import { mount, flushPromises } from '@vue/test-utils';
import { createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppRouter } from '../router';
import { useSessionStore } from '../stores/session';
import { api } from '../api/client';
import StoresPage from '../pages/StoresPage.vue';
import OrdersPage from '../pages/OrdersPage.vue';
import OrderDetailPage from '../pages/OrderDetailPage.vue';
import { owner, support, json, apiError } from './fixtures';
import { store, order } from './m1-fixtures';
beforeEach(() => api.clearCsrf());
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });
async function setup(component: typeof StoresPage, path: string, persona = owner) {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.user = persona;
    session.checked = true;
    const router = createAppRouter(createMemoryHistory());
    await router.push(path);
    await router.isReady();
    return { wrapper: mount(component, { global: { plugins: [pinia, router, ElementPlus] } }), router, session };
}
const list = (items: unknown[]) => json({ items, total: items.length, limit: 20, offset: 0 });
describe('M1 views and permissions', () => {
    it('lets support read stores/orders but rejects direct management route', async () => {
        const pinia = createPinia();
        setActivePinia(pinia);
        const session = useSessionStore();
        session.user = support;
        session.checked = true;
        const router = createAppRouter(createMemoryHistory());
        await router.push('/stores');
        expect(router.currentRoute.value.path).toBe('/stores');
        await router.push('/orders');
        expect(router.currentRoute.value.path).toBe('/orders');
        await router.push('/stores/connect');
        expect(router.currentRoute.value.path).toBe('/403');
    });
    it('renders scoped stores without write controls for support', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => list([store])));
        const { wrapper } = await setup(StoresPage, '/stores', support);
        await flushPromises();
        expect(wrapper.text()).toContain('合成店铺 A');
        expect(wrapper.find('[data-testid="edit-store"]').exists()).toBe(false);
        expect(wrapper.find('[data-testid="connect-store"]').exists()).toBe(false);
        wrapper.unmount();
    });
    it('requires disable confirmation, submits version once and disables duplicate clicks', async () => {
        const writes: RequestInit[] = [];
        let finish!: (r: Response) => void;
        vi.stubGlobal('fetch', vi.fn(async (path: string, options: RequestInit) => {
            if (path.endsWith('/csrf'))
                return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
            if (options.method === 'PATCH') {
                writes.push(options);
                return new Promise<Response>(r => { finish = r; });
            }
            return list([store]);
        }));
        const { wrapper } = await setup(StoresPage, '/stores');
        await flushPromises();
        await wrapper.get('[data-testid="toggle-store"]').trigger('click');
        await flushPromises();
        expect(writes).toHaveLength(0);
        expect(wrapper.text()).toContain('确认禁用店铺');
        await wrapper.get('[data-testid="confirm-risk"]').trigger('click');
        await flushPromises();
        expect(JSON.parse(writes[0]!.body as string)).toEqual({ version: 0, enabled: false });
        expect(wrapper.get('[data-testid="confirm-risk"]').attributes('disabled')).toBeDefined();
        await wrapper.get('[data-testid="confirm-risk"]').trigger('click');
        expect(writes).toHaveLength(1);
        finish(json({ ...store, enabled: false, version: 1 }));
        await flushPromises();
        wrapper.unmount();
    });
    it('keeps filters in URL and honors back navigation and clear', async () => {
        const urls: string[] = [];
        vi.stubGlobal('fetch', vi.fn(async (path: string) => { urls.push(path); return list(path.includes('/stores') ? [store] : [order]); }));
        const { wrapper, router } = await setup(OrdersPage, '/orders?q=DEMO_1&sourceStatus=PAID');
        await flushPromises();
        expect(wrapper.get<HTMLInputElement>('[name="q"]').element.value).toBe('DEMO_1');
        await wrapper.get('[name="q"]').setValue('NEXT');
        await wrapper.get('[data-testid="apply-filters"]').trigger('submit');
        await flushPromises();
        expect(router.currentRoute.value.query.q).toBe('NEXT');
        router.back();
        await flushPromises();
        expect(wrapper.get<HTMLInputElement>('[name="q"]').element.value).toBe('DEMO_1');
        await wrapper.get('[data-testid="clear-filters"]').trigger('click');
        await flushPromises();
        expect(router.currentRoute.value.query.q).toBeUndefined();
        expect(urls.some(u => u.includes('q=NEXT'))).toBe(true);
        wrapper.unmount();
    });
    it('shows failures separately from empty orders and keeps simulation hidden for support', async () => {
        vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('/stores') ? list([store]) : apiError(503, 'ORDER_UNAVAILABLE')));
        const { wrapper } = await setup(OrdersPage, '/orders', support);
        await flushPromises();
        expect(wrapper.find('[role="alert"]').text()).toContain('ORDER_UNAVAILABLE');
        expect(wrapper.find('[data-testid="orders-empty"]').exists()).toBe(false);
        expect(wrapper.find('[data-testid="scenario-form"]').exists()).toBe(false);
        wrapper.unmount();
    });
    it('distinguishes pending orders from a successful empty result', async () => {
        let finish!: (r: Response) => void;
        vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('/stores') ? list([]) : new Promise<Response>(r => { finish = r; })));
        const { wrapper } = await setup(OrdersPage, '/orders');
        expect(wrapper.find('[data-testid="orders-loading"]').exists()).toBe(true);
        expect(wrapper.find('[data-testid="orders-empty"]').exists()).toBe(false);
        finish(list([]));
        await flushPromises();
        expect(wrapper.find('[data-testid="orders-empty"]').exists()).toBe(true);
        wrapper.unmount();
    });
    it('renders safe order items and timeline with explicit Mock and Shanghai time', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => json({ ...order, items: [{ id: '50000000-0000-0000-0000-000000000001', externalItemId: 'item-1', externalSkuId: 'sku-1', title: '合成商品', qty: 1, paidAllocatedFen: 19901, cancelledQty: 0, fulfilledQty: 0 }], timeline: [{ id: '60000000-0000-0000-0000-000000000001', eventId: 'evt-1', action: 'ORDER_IMPORTED', sourceVersion: 1, occurredAt: '2026-10-20T16:00:00Z' }] })));
        const { wrapper } = await setup(OrderDetailPage as typeof StoresPage, `/orders/${order.id}`);
        await flushPromises();
        expect(wrapper.text()).toContain('合成商品');
        expect(wrapper.text()).toContain('¥199.01');
        expect(wrapper.text()).toContain('2026/10/21 00:00:00');
        expect(wrapper.text()).toContain('Asia/Shanghai');
        expect(wrapper.text()).toContain('已记录 Mock 订单同步事件');
        expect(wrapper.text()).not.toContain('ORDER_IMPORTED');
        expect(wrapper.text()).toContain('最近 100 条');
        expect(wrapper.text()).not.toContain('去付款');
        wrapper.unmount();
    });
});
describe('M1 mutation behavior', () => {
    it('allows renewing an already active Mock authorization', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => list([store])));
        const { wrapper } = await setup(StoresPage, '/stores');
        await flushPromises();
        await wrapper.get('[data-testid="edit-store"]').trigger('click');
        expect(wrapper.findAll('button').find(b => b.text() === '设为有效 / 续期')!.attributes('disabled')).toBeUndefined();
        wrapper.unmount();
    });
    it('requires explicit confirmation before revoking Mock authorization', async () => {
        let write: RequestInit | undefined;
        vi.stubGlobal('fetch', vi.fn(async (path: string, options: RequestInit) => {
            if (path.endsWith('/csrf'))
                return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
            if (options.method === 'PATCH') {
                write = options;
                return json({ ...store, authStatus: 'REVOKED', version: 1 });
            }
            return list([store]);
        }));
        const { wrapper } = await setup(StoresPage, '/stores');
        await flushPromises();
        await wrapper.get('[data-testid="edit-store"]').trigger('click');
        await wrapper.findAll('button').find(b => b.text() === '撤销授权')!.trigger('click');
        await flushPromises();
        expect(write).toBeUndefined();
        await wrapper.get('[data-testid="confirm-risk"]').trigger('click');
        await flushPromises();
        expect(JSON.parse(write!.body as string)).toEqual({ version: 0, authStatus: 'REVOKED' });
        expect(wrapper.text()).toContain('授权已撤销');
        wrapper.unmount();
    });
    it('shows accepted asynchronous Mock results without claiming order completion', async () => {
        const writes: RequestInit[] = [];
        vi.stubGlobal('fetch', vi.fn(async (path: string, options: RequestInit) => {
            if (path.endsWith('/csrf'))
                return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
            if (options.method === 'POST') {
                writes.push(options);
                return json({ scenario: 'DELAYED', externalOrderId: 'demo_delayed', acceptedEvents: 1, mode: 'MOCK' }, 202);
            }
            return list(path.includes('/stores') ? [store] : []);
        }));
        const { wrapper } = await setup(OrdersPage, '/orders');
        await flushPromises();
        const form = wrapper.get('[data-testid="scenario-form"]');
        await form.find('select').setValue(store.id);
        await form.find('input').setValue('demo_delayed');
        await form.findAll('select')[1]!.setValue('DELAYED');
        await form.trigger('submit');
        await flushPromises();
        expect(writes).toHaveLength(1);
        expect(new Headers(writes[0]!.headers).get('Idempotency-Key')).toBeTruthy();
        expect(JSON.parse(writes[0]!.body as string)).toEqual({ scenario: 'DELAYED', externalOrderId: 'demo_delayed' });
        expect(wrapper.text()).toContain('后台异步处理中');
        expect(wrapper.text()).toContain('不代表真实平台收款');
        expect(wrapper.find('[data-testid="orders-empty"]').exists()).toBe(true);
        wrapper.unmount();
    });
    it('rejects an inverted UTC range before changing the URL', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => list([])));
        const { wrapper, router } = await setup(OrdersPage, '/orders');
        await flushPromises();
        await wrapper.get('[name="from"]').setValue('2026-10-21T16:00');
        await wrapper.get('[name="to"]').setValue('2026-10-20T16:00');
        await wrapper.get('[data-testid="apply-filters"]').trigger('submit');
        expect(wrapper.get('[role="alert"]').text()).toContain('开始时间必须早于结束时间');
        expect(router.currentRoute.value.query.from).toBeUndefined();
        wrapper.unmount();
    });
});


describe('sales timeline labels', () => {
    it.each([
        ['AUTHORITY_APPLIED', '已重查并同步 Mock 权威状态'],
        ['AUTHORITY_UNCHANGED', 'Mock 权威状态无变更'],
        ['STALE_AUTHORITY_IGNORED', '已忽略旧的 Mock 权威版本'],
        ['UNKNOWN_IMPLEMENTATION_ACTION', '已记录 Mock 订单同步事件'],
    ])('shows a safe user-facing label for %s', async (action, label) => {
        vi.stubGlobal('fetch', vi.fn(async () => json({ ...order, items: [], timeline: [{ id: '60000000-0000-0000-0000-000000000001', eventId: 'evt-1', action, sourceVersion: 1, occurredAt: '2026-10-20T16:00:00Z' }] })));
        const { wrapper } = await setup(OrderDetailPage as typeof StoresPage, `/orders/${order.id}`);
        await flushPromises();
        expect(wrapper.get('.event-timeline strong').text()).toBe(label);
        wrapper.unmount();
    });
});
