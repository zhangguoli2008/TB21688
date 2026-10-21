#!/usr/bin/env python3
"""M4 actual HTTP/OpenAPI acceptance, only for a dedicated synthetic Mock demo."""
import os
import time
import unittest
import uuid

if os.environ.get('M4_BASE_URL'):
    os.environ['M1_BASE_URL'] = os.environ['M4_BASE_URL']
from m1_http import HttpClient, unique
from m3_http import PaymentDemo


def synthetic(prefix):
    return prefix + uuid.uuid4().hex


class M4Smoke(unittest.TestCase):
    def setUp(self):
        self.demo = PaymentDemo(self)
        self.client = self.demo.owner

    def poll(self, read, predicate, message, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = read()
            if predicate(value):
                return value
            time.sleep(.2)
        self.fail(message)

    def enable(self):
        self.demo.set_mandate('ACTIVE')
        self.demo.set_policy(mode='AUTO', isPaused=False, newSupplierFirstOrderManual=False,
            maxSingleFen=100000, maxDailyFen=1000000, maxStoreSingleFen=100000,
            maxStoreDailyFen=1000000, minEstimatedProfitFen=0)

    def purchase(self, paid=True, behavior='NORMAL'):
        purchase = self.demo.purchase(behavior)
        if paid:
            self.enable()
            self.demo.request(purchase)
            self.demo.wait(purchase, {'PAID'})
        return purchase

    def shipment_event(self, purchase, package=None, version=1, behavior='NORMAL', tracking=None, event=None):
        body = dict(eventId=event or synthetic('MOCK-'), externalShipmentId=package or synthetic('MOCK-PKG-'),
            sourceVersion=version, qty=1, carrierCode='MOCK_EXPRESS',
            trackingNumber=tracking or synthetic('MOCK-TRACK-'), sourceState='SHIPPED', behavior=behavior)
        self.client.write('POST', f"/mock/purchases/{purchase['id']}/shipment-events", 202,
            contract_path='/mock/purchases/{purchaseId}/shipment-events', key=unique('m4_ship'), json=body)
        return body

    def shipments(self, purchase):
        return self.client.call('GET', '/shipments', params={'purchaseOrderId': purchase['id']})['data']

    def shipment(self, shipment_id):
        return self.client.call('GET', f'/shipments/{shipment_id}', contract_path='/shipments/{shipmentId}')['data']

    def sales(self, purchase):
        item_id = purchase['salesOrderItemId']
        order = next(o for o in self.client.orders(storeId=self.demo.store['id'])['items']
                     if any(i['id'] == item_id for i in self.client.detail(o['id'])['data']['items']))
        return order, item_id

    def source_event(self, order, item, case, state='REFUNDED', version=1, event=None, kind='REFUND_ONLY'):
        body = dict(eventId=event or synthetic('MOCK-'), sourceCaseId=case, type=kind,
            sourceState=state, sourceVersion=version, salesOrderItemId=item, qty=1, requestedRefundFen=4300)
        return self.client.write('POST', f"/mock/orders/{order['id']}/aftersale-events", 202,
            contract_path='/mock/orders/{orderId}/aftersale-events', key=unique('m4_source'), json=body)['data']

    def cases(self, order):
        return self.client.call('GET', '/aftersales', params={'salesOrderId': order['id']})['data']

    def case(self, case_id):
        return self.client.call('GET', f'/aftersales/{case_id}', contract_path='/aftersales/{caseId}')['data']

    def await_case(self, order):
        return self.poll(lambda: self.cases(order), lambda p: p['total'] == 1, 'Mock aftersale was not projected')['items'][0]

    def supplier_event(self, case_id, purchase, version, state, amount, event=None):
        return self.client.write('POST', f'/mock/aftersales/{case_id}/supplier-events', 202,
            contract_path='/mock/aftersales/{caseId}/supplier-events', key=unique('m4_supplier'),
            json=dict(eventId=event or synthetic('MOCK-'), purchaseOrderId=purchase['id'],
                      sourceVersion=version, state=state, refundAmountFen=amount))['data']

    def report(self):
        return self.client.call('GET', '/reports/overview', params={'storeId': self.demo.store['id']})['data']

    def test_01_t012_two_packages_and_tracking_revision_are_independent_and_idempotent(self):
        purchase = self.purchase()
        first = self.shipment_event(purchase)
        self.shipment_event(purchase)
        page = self.poll(lambda: self.shipments(purchase),
            lambda p: p['total'] == 2 and all(s['currentSync']['state'] == 'ACKED' for s in p['items']),
            'Two packages did not receive separate Mock authoritative acknowledgements')
        shipment = next(s for s in page['items'] if s['externalShipmentId'] == first['externalShipmentId'])
        self.shipment_event(purchase, first['externalShipmentId'], 2)
        updated = self.poll(lambda: self.shipment(shipment['id']),
            lambda s: s['trackingVersion'] == 2 and s['currentSync']['state'] == 'ACKED', 'Tracking revision was not acknowledged')
        self.assertEqual(2, len(updated['versions']))
        self.assertEqual(2, len(updated['syncs']))
        self.assertEqual(2, self.shipments(purchase)['total'])

    def test_02_t013_failed_sync_requires_authoritative_compensation(self):
        purchase = self.purchase()
        self.shipment_event(purchase, behavior='SUBMIT_5XX')
        page = self.poll(lambda: self.shipments(purchase),
            lambda p: p['total'] == 1 and p['items'][0]['currentSync']['state'] in {'RETRY_WAIT', 'BLOCKED'},
            'Failed submission was incorrectly acknowledged')
        shipment = self.shipment(page['items'][0]['id'])
        self.assertIsNone(shipment['currentSync']['platformAck'])
        self.client.write('PATCH', f"/mock/shipments/{shipment['id']}/sync-config",
            contract_path='/mock/shipments/{shipmentId}/sync-config', key=unique('m4_config'),
            json={'behavior': 'NORMAL', 'version': shipment['config']['version']})
        self.client.write('POST', f"/shipments/{shipment['id']}/sync-retry",
            contract_path='/shipments/{shipmentId}/sync-retry', key=unique('m4_retry'))
        final = self.poll(lambda: self.shipment(shipment['id']), lambda s: s['currentSync']['state'] == 'ACKED',
                          'Compensation did not query a valid acknowledgement')
        self.assertTrue(final['currentSync']['platformAck'].startswith('MOCK-ACK-'))
        self.assertEqual(1, len(final['syncs']))

    def test_03_t011_t019_refunds_preserve_gross_receipts_and_payment_and_separate_cashflow(self):
        purchase = self.purchase()
        order, item = self.sales(purchase)
        source = synthetic('MOCK-CASE-')
        self.source_event(order, item, source, 'REQUESTED')
        case = self.await_case(order)
        self.supplier_event(case['id'], purchase, 1, 'REFUND_PENDING', 0)
        self.source_event(order, item, source, 'REFUNDED', 2)
        half = purchase['totalFen'] // 2
        before = self.poll(self.report, lambda r: r['salesRefundedFen'] == 4300 and r['projectionPendingCount'] == 0,
                           'Source refund report did not catch up')
        self.assertEqual(12900, before['salesPaidFen'])
        self.assertEqual(purchase['totalFen'], before['purchasePaidFen'])
        self.assertEqual(0, before['supplierRefundedFen'])
        self.assertEqual(half, before['pendingSupplierRefundFen'])
        event = synthetic('MOCK-')
        first = self.supplier_event(case['id'], purchase, 2, 'REFUNDED', half, event)
        replay = self.supplier_event(case['id'], purchase, 2, 'REFUNDED', half, event)
        self.assertEqual(first['jobId'], replay['jobId'])
        after = self.poll(self.report, lambda r: r['supplierRefundedFen'] == half and r['projectionPendingCount'] == 0,
                          'Supplier refund report did not catch up')
        self.assertEqual(12900 - 4300 - purchase['totalFen'] + half, after['netCashFlowFen'])
        self.assertIsNone(after['actualNetProfitFen'])
        self.assertEqual('INCOMPLETE_COSTS', after['profitStatus'])
        self.assertEqual('PAID', self.demo.status(purchase)['state'])
        refunds = self.case(case['id'])['refunds']
        self.assertEqual(4300, sum(r['amountFen'] for r in refunds if r['direction'] == 'SALES'))
        self.assertEqual(half, sum(r['amountFen'] for r in refunds if r['direction'] == 'SUPPLIER'))

    def test_04_unknown_payment_stays_frozen_in_aftersales_coordination(self):
        purchase = self.purchase(False, 'QUERY_TIMEOUT')
        self.enable()
        self.demo.request(purchase)
        self.demo.wait(purchase, {'UNKNOWN', 'MANUAL_RECONCILIATION'})
        order, item = self.sales(purchase)
        self.source_event(order, item, synthetic('MOCK-CASE-'))
        case = self.await_case(order)
        self.assertEqual('QUERY_PAYMENT', case['upstreamState'])
        self.assertNotEqual('RESOLVED', case['state'])
        self.assertEqual(0, self.demo.status(purchase)['paidFen'])
        self.demo.request(purchase, expected=409)
        self.assertFalse(any(r['direction'] == 'SUPPLIER' for r in self.case(case['id'])['refunds']))

    def test_05_real_existing_objects_hidden_and_safe_event_validation(self):
        purchase = self.purchase()
        self.shipment_event(purchase)
        row = self.poll(lambda: self.shipments(purchase), lambda p: p['total'] == 1, 'No shipment')['items'][0]
        order, item = self.sales(purchase)
        self.source_event(order, item, synthetic('MOCK-CASE-'), 'REQUESTED')
        case = self.await_case(order)
        for persona in ['OWNER_B', 'SUPPORT_A']:
            other = HttpClient(self)
            other.login(persona)
            other.call('GET', f"/shipments/{row['id']}", 404, contract_path='/shipments/{shipmentId}')
            other.call('GET', f"/aftersales/{case['id']}", 404, contract_path='/aftersales/{caseId}')
        self.client.call('GET', '/shipments', 400, params={'limit': 101})
        self.client.call('GET', '/aftersales', 400, params={'offset': 10001})
        self.client.call('POST', f"/shipments/{row['id']}/sync-retry", 403,
            contract_path='/shipments/{shipmentId}/sync-retry', headers={'Idempotency-Key': unique('no_csrf')})

    def test_06_notifications_read_and_activity_and_report_period_contract(self):
        purchase = self.purchase()
        self.shipment_event(purchase)
        self.poll(lambda: self.shipments(purchase), lambda p: p['total'] == 1 and p['items'][0]['currentSync']['state'] == 'ACKED',
                  'Shipment not acknowledged')
        order, _ = self.sales(purchase)
        page = self.client.call('GET', '/notifications', params={'storeId': self.demo.store['id'], 'unread': True})['data']
        self.assertGreaterEqual(page['total'], 1)
        notification = page['items'][0]
        result = self.client.write('POST', f"/notifications/{notification['id']}/read",
            contract_path='/notifications/{notificationId}/read', key=unique('m4_read'))['data']
        self.assertIsNotNone(result['readAt'])
        activity = self.client.call('GET', f"/orders/{order['id']}/activity", contract_path='/orders/{orderId}/activity')['data']
        self.assertTrue(any(a['action'] == 'MOCK_SHIPMENT_ACKED' for a in activity['items']))
        self.client.call('GET', '/reports/overview', 400,
            params={'from': '2020-01-01T00:00:00Z', 'to': '2022-01-01T00:00:00Z'})
        self.client.call('GET', '/reports/orders', params={'storeId': self.demo.store['id']})


if __name__ == '__main__':
    unittest.main(verbosity=2)
