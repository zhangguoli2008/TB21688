"""Reusable real-HTTP fixtures for M3. All records and receipts are synthetic."""
import time
from m1_http import HttpClient, unique

POLICY_FIELDS = ('mode', 'maxSingleFen', 'maxDailyFen', 'maxStoreSingleFen', 'maxStoreDailyFen',
                 'minEstimatedProfitFen', 'isPaused', 'newSupplierFirstOrderManual', 'version')


class PaymentDemo:
    """Use only in a dedicated demo; keeps records but restores configuration."""
    def __init__(self, testcase):
        self.test = testcase
        self.owner = HttpClient(testcase)
        self.owner.login()
        self.original_policy = self.policy()
        self.original_procurement = self.owner.call('GET', '/procurement-policy')['data']
        self.changed_mandates = {}
        self.mappings = []
        self.test.addCleanup(self.restore)
        self.set_policy(mode='MANUAL', isPaused=True)
        self.owner.write('PUT', '/procurement-policy', json={
            **{key: value for key, value in self.original_procurement.items() if key != 'mode'},
            'enabled': True, 'maxSingleFen': 1000000000000, 'maxDailyFen': 1000000000000,
            'maxShippingFen': 1000000000, 'minProfitFen': 0, 'platformFeeFen': 0, 'reserveFen': 0})
        self.store = self.owner.write('POST', '/stores', key=unique('m3_store'), json={
            'platform': 'DOUYIN', 'externalShopId': unique('M3_SYNTHETIC'),
            'displayName': 'M3 HTTP 合成付款验收店'})['data']
        offers = self.owner.call('GET', '/supplier-offers', params={'limit': 100})['data']['items']
        self.offer = next(o for o in offers if o['compatibleSalesSkuId'] == 'MOCK-SKU-BLUE' and o['fault'] == 'NORMAL'
                          and 'DOUYIN' in o['cipherPlatforms'] and o['stock'] >= 10)
        self.mandate = next(m for m in self.owner.call('GET', '/payment-mandates', params={'limit': 100})['data']['items']
                            if m['supplierAccountId'] == self.offer['supplierAccountId'])
        mapping = self.owner.write('POST', '/sku-mappings', key=unique('m3_mapping'), json={
            'storeId': self.store['id'], 'salesSkuId': 'MOCK-SKU-BLUE',
            'supplierOfferId': self.offer['id'], 'priority': 1})['data']
        self.mappings.append(mapping)
        self.owner.write('PATCH', f"/sku-mappings/{mapping['id']}", contract_path='/sku-mappings/{mappingId}',
                         json={'version': mapping['version'], 'status': 'CONFIRMED', 'reviewAcknowledged': True})

    def policy(self):
        return self.owner.call('GET', '/payment-policy')['data']

    def set_policy(self, **changes):
        current = self.policy()
        body = {name: current[name] for name in POLICY_FIELDS}
        body.update(changes)
        return self.owner.write('PUT', '/payment-policy', key=unique('m3_policy'), json=body)['data']

    def set_mandate(self, status):
        page = self.owner.call('GET', '/payment-mandates', params={'limit': 100})['data']
        current = next(m for m in page['items'] if m['id'] == self.mandate['id'])
        self.changed_mandates.setdefault(current['id'], current)
        self.mandate = self.owner.write('PATCH', f"/payment-mandates/{current['id']}/mock-state",
            contract_path='/payment-mandates/{mandateId}/mock-state',
            json={'status': status, 'maxSingleFen': 100000, 'version': current['version']})['data']
        return self.mandate

    def purchase(self, behavior='NORMAL'):
        external = unique('M3_ORDER')
        self.owner.scenario(self.store['id'], 'PAID', external)
        sales = self.owner.poll_order(self.store['id'], external, 'PAID')
        detail = self.owner.detail(sales['id'])['data']
        item = next(i for i in detail['items'] if i['externalSkuId'] == 'MOCK-SKU-BLUE')
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            page = self.owner.call('GET', '/purchase-intents', params={'storeId': self.store['id']})['data']
            rows = [i for i in page['items'] if i['salesOrderItemId'] == item['id']]
            self.test.assertLessEqual(len(rows), 1)
            if rows and rows[0]['state'] == 'AWAITING_PAYMENT':
                purchase = self.owner.call('GET', f"/purchases/{rows[0]['purchaseOrderId']}",
                                          contract_path='/purchases/{purchaseId}')['data']
                config = self.owner.call('GET', f"/mock/purchases/{purchase['id']}/payment-config",
                                         contract_path='/mock/purchases/{purchaseId}/payment-config')['data']
                self.owner.write('PATCH', f"/mock/purchases/{purchase['id']}/payment-config",
                    contract_path='/mock/purchases/{purchaseId}/payment-config',
                    json={'behavior': behavior, 'version': config['version']})
                return purchase
            time.sleep(0.2)
        self.test.fail('Mock purchase did not reach AWAITING_PAYMENT')

    def status(self, purchase, client=None):
        return (client or self.owner).call('GET', f"/purchases/{purchase['id']}/payment-status",
                                          contract_path='/purchases/{purchaseId}/payment-status')['data']

    def wait(self, purchase, states, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = self.status(purchase)
            if value['state'] in states:
                return value
            time.sleep(0.2)
        self.test.fail(f'Mock payment did not reach {states}')

    def request(self, purchase, key=None, expected=202, client=None):
        return (client or self.owner).write('POST', f"/purchases/{purchase['id']}/payments", expected,
            contract_path='/purchases/{purchaseId}/payments', key=key or unique('m3_pay'))

    def checkout(self, purchase, key=None, expected=200, client=None):
        return (client or self.owner).write('POST', f"/purchases/{purchase['id']}/checkout-entry", expected,
            contract_path='/purchases/{purchaseId}/checkout-entry', key=key or unique('m3_entry'))

    def receipt(self, attempt, outcome, key=None):
        return self.owner.write('POST', f"/mock/payment-attempts/{attempt}/receipt", 202,
            contract_path='/mock/payment-attempts/{attemptId}/receipt', key=key or unique('m3_receipt'),
            json={'outcome': outcome})['data']

    def reconcile(self, purchase):
        return self.owner.write('POST', f"/purchases/{purchase['id']}/payment-reconcile", 202,
            contract_path='/purchases/{purchaseId}/payment-reconcile', key=unique('m3_readonly'))['data']

    def restore(self):
        # Pause all new tenant payments before restoring signing or procurement settings.
        self.set_policy(isPaused=True, mode='MANUAL')
        for original in self.mappings:
            page = self.owner.call('GET', '/sku-mappings', params={'storeId': original['storeId']})['data']
            row = next(m for m in page['items'] if m['id'] == original['id'])
            self.owner.write('PATCH', f"/sku-mappings/{row['id']}", contract_path='/sku-mappings/{mappingId}',
                             json={'version': row['version'], 'status': 'PAUSED'})
        for original in self.changed_mandates.values():
            current = next(m for m in self.owner.call('GET', '/payment-mandates', params={'limit': 100})['data']['items'] if m['id'] == original['id'])
            # PENDING cannot be selected by the mock update API; restore it conservatively as REVOKED.
            status = original['status'] if original['status'] in {'ACTIVE', 'EXPIRED', 'REVOKED'} else 'REVOKED'
            self.owner.write('PATCH', f"/payment-mandates/{current['id']}/mock-state",
                contract_path='/payment-mandates/{mandateId}/mock-state',
                json={'status': status, 'maxSingleFen': original['maxSingleFen'], 'version': current['version']})
        current = self.owner.call('GET', '/procurement-policy')['data']
        self.owner.write('PUT', '/procurement-policy', json={
            **{key: value for key, value in self.original_procurement.items() if key != 'mode'}, 'version': current['version']})
        body = {name: self.original_policy[name] for name in POLICY_FIELDS}
        body['version'] = self.policy()['version']
        # Keep test-only historical unpaid purchases from being picked up after cleanup.
        body.update(mode='MANUAL', isPaused=True)
        self.owner.write('PUT', '/payment-policy', key=unique('m3_restore'), json=body)
