#!/usr/bin/env python3
"""M3 real HTTP/OpenAPI acceptance. Mock only, dedicated demo environment only."""
import os
import time
import unittest

if os.environ.get('M3_BASE_URL'):
    os.environ['M1_BASE_URL'] = os.environ['M3_BASE_URL']
from m1_http import HttpClient, unique
from m3_http import PaymentDemo, POLICY_FIELDS


class M3Smoke(unittest.TestCase):
    def setUp(self):
        self.demo = PaymentDemo(self)
        self.other = HttpClient(self)
        self.other.login('OWNER_B')
        self.support = HttpClient(self)
        self.support.login('SUPPORT_A')

    def enable(self, mode='AUTO', **changes):
        settings = dict(mode=mode, isPaused=False, newSupplierFirstOrderManual=False,
            maxSingleFen=100000, maxDailyFen=1000000, maxStoreSingleFen=100000,
            maxStoreDailyFen=1000000, minEstimatedProfitFen=0)
        settings.update(changes)
        return self.demo.set_policy(**settings)

    def test_01_policy_integer_cas_permission_and_idempotency(self):
        d = self.demo
        policy = d.policy()
        body = {key: policy[key] for key in POLICY_FIELDS}
        key = unique('m3_policy_replay')
        saved = d.owner.write('PUT', '/payment-policy', key=key, json=body)['data']
        self.assertEqual(saved, d.owner.write('PUT', '/payment-policy', key=key, json=body)['data'])
        d.owner.write('PUT', '/payment-policy', 409, key=unique('stale'), json=body)
        self.support.write('PUT', '/payment-policy', 403, key=unique('forbidden'), json=body)
        d.owner.write('PUT', '/payment-policy', 400, key=unique('decimal'), json={**body, 'maxSingleFen': 1.25})
        d.owner.write('PUT', '/payment-policy', 400, key=unique('tenant_injection'), json={**body, 'tenantId': 'other'})

    def test_02_manual_unpaid_receipt_never_claims_paid_then_authority_resolves(self):
        d = self.demo
        purchase = d.purchase()
        self.enable('MANUAL')
        key = unique('manual_entry')
        entry = d.checkout(purchase, key)['data']
        self.assertEqual(f"/payments/mock/{entry['attemptId']}", entry['url'])
        self.assertEqual(entry, d.checkout(purchase, key)['data'])
        self.assertEqual('MOCK', entry['mode'])
        checkout = d.owner.call('GET', f"/payment-attempts/{entry['attemptId']}/mock-checkout",
                               contract_path='/payment-attempts/{attemptId}/mock-checkout')['data']
        self.assertEqual('UNPAID', checkout['authorityState'])
        d.receipt(entry['attemptId'], 'UNPAID')
        self.assertEqual('WAIT_MERCHANT_PAYMENT', d.wait(purchase, {'WAIT_MERCHANT_PAYMENT'})['state'])
        self.assertEqual(0, d.status(purchase)['paidFen'])
        d.receipt(entry['attemptId'], 'PAID')
        paid = d.wait(purchase, {'PAID'})
        self.assertEqual(purchase['totalFen'], paid['paidFen'])
        self.assertTrue(paid['attempt']['providerPaymentReference'].startswith('MOCK'))
        self.assertEqual('PAID', d.owner.call('GET', f"/purchases/{purchase['id']}", contract_path='/purchases/{purchaseId}')['data']['state'])

    def test_03_auto_without_mandate_waits_then_valid_mandate_can_pay(self):
        d = self.demo
        d.set_mandate('REVOKED')
        purchase = d.purchase()
        self.enable()
        self.assertEqual('WAIT_MERCHANT_PAYMENT', d.request(purchase)['data']['state'])
        self.assertIsNone(d.status(purchase)['attempt'])
        d.set_mandate('ACTIVE')
        paid = d.wait(purchase, {'PAID'})
        self.assertEqual('AUTO', paid['attempt']['mode'])
        self.assertEqual(purchase['totalFen'], paid['paidFen'])

    def test_04_unknown_blocks_new_payment_checkout_and_old_keys(self):
        d = self.demo
        purchase = d.purchase('QUERY_TIMEOUT')
        d.set_mandate('ACTIVE')
        self.enable()
        unknown = d.wait(purchase, {'UNKNOWN'})
        self.assertEqual(0, unknown['paidFen'])
        for result in [d.request(purchase, expected=409), d.checkout(purchase, expected=409)]:
            self.assertEqual('PAYMENT_STATUS_UNKNOWN', result['code'])
        before = unknown['attempt']['id']
        d.reconcile(purchase)
        self.assertEqual(before, d.status(purchase)['attempt']['id'])
        self.assertNotEqual('PAID', d.status(purchase)['state'])

    def test_05_revoke_and_emergency_pause_still_allow_readonly_reconciliation(self):
        d = self.demo
        purchase = d.purchase('QUERY_TIMEOUT')
        d.set_mandate('ACTIVE')
        self.enable()
        before = d.wait(purchase, {'UNKNOWN'})
        d.set_mandate('REVOKED')
        d.set_policy(isPaused=True)
        d.reconcile(purchase)
        config = d.owner.call('GET', f"/mock/purchases/{purchase['id']}/payment-config",
                              contract_path='/mock/purchases/{purchaseId}/payment-config')['data']
        d.owner.write('PATCH', f"/mock/purchases/{purchase['id']}/payment-config",
            contract_path='/mock/purchases/{purchaseId}/payment-config', json={'behavior': 'NORMAL', 'version': config['version']})
        d.reconcile(purchase)
        after = d.wait(purchase, {'PAID'})
        self.assertEqual(before['attempt']['id'], after['attempt']['id'])
        self.assertTrue(d.policy()['isPaused'])

    def test_06_known_objects_hidden_from_other_tenant_and_unassigned_support(self):
        d = self.demo
        purchase = d.purchase()
        self.enable('MANUAL')
        entry = d.checkout(purchase)['data']
        d.status(purchase)  # positive control: the object really exists
        for client in [self.other, self.support]:
            client.call('GET', f"/purchases/{purchase['id']}/payment-status", 404,
                        contract_path='/purchases/{purchaseId}/payment-status')
            client.call('GET', f"/payment-attempts/{entry['attemptId']}/mock-checkout",
                        404 if client is self.other else 403,
                        contract_path='/payment-attempts/{attemptId}/mock-checkout')
        self.support.write('POST', f"/purchases/{purchase['id']}/checkout-entry", 403,
            contract_path='/purchases/{purchaseId}/checkout-entry', key=unique('support_entry'))

    def test_07_rule_based_profit_filter_then_eligible_payment(self):
        d = self.demo
        purchase = d.purchase()
        d.set_mandate('ACTIVE')
        self.enable('RULE_BASED')
        d.set_policy(minEstimatedProfitFen=1000000000000)
        view = d.request(purchase)['data']
        self.assertEqual('BLOCKED', view['state'])
        self.assertEqual('PROFIT_BELOW_MINIMUM', view['decisionCode'])
        self.assertIsNone(view['attempt'])
        d.set_policy(minEstimatedProfitFen=0)
        self.assertEqual('RULE_BASED', d.wait(purchase, {'PAID'})['attempt']['mode'])

    def test_08_queue_filters_caps_and_reconciliation_audit_contract(self):
        d = self.demo
        purchase = d.purchase()
        d.set_mandate('ACTIVE')
        self.enable()
        d.wait(purchase, {'PAID'})
        d.reconcile(purchase)
        page = d.owner.call('GET', '/payment-queue', params={'storeId': d.store['id'], 'state': 'PAID', 'limit': 1})['data']
        self.assertEqual(1, page['total'])
        self.assertEqual(purchase['id'], page['items'][0]['purchaseId'])
        d.owner.call('GET', '/payment-queue', 400, params={'limit': 101})
        d.owner.call('GET', '/payment-reconciliations', 400, params={'offset': 10001})
        # The readonly request is asynchronous; poll for its persisted audited result.
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            logs = d.owner.call('GET', '/payment-reconciliations', params={'storeId': d.store['id']})['data']
            if logs['items']:
                self.assertTrue(all(row['purchaseId'] == purchase['id'] for row in logs['items']))
                return
            time.sleep(0.2)
        self.fail('Readonly reconciliation did not create a safe result record')

    def test_09_competing_orders_cannot_exceed_shared_store_daily_limit(self):
        d = self.demo
        first, second = d.purchase(), d.purchase()
        self.assertEqual(first['totalFen'], second['totalFen'])
        d.set_mandate('ACTIVE')
        self.enable(maxStoreDailyFen=first['totalFen'])
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            values = [d.status(first), d.status(second)]
            if any(row['state'] == 'PAID' for row in values):
                break
            time.sleep(0.2)
        self.assertEqual(1, sum(row['state'] == 'PAID' for row in values))
        self.assertLessEqual(sum(row['paidFen'] for row in values), first['totalFen'])
        self.assertEqual(1, sum(row['attempt'] is not None for row in values))
        blocked = next(row for row in values if row['attempt'] is None)
        self.assertEqual('STORE_DAILY_LIMIT_EXCEEDED', blocked['decisionCode'])

    def test_10_expired_mandate_blocks_and_missing_csrf_is_rejected(self):
        d = self.demo
        purchase = d.purchase()
        d.set_mandate('EXPIRED')
        self.enable()
        self.assertEqual('WAIT_MERCHANT_PAYMENT', d.request(purchase)['data']['state'])
        self.assertIsNone(d.status(purchase)['attempt'])
        d.owner.call('POST', f"/purchases/{purchase['id']}/payments", 403,
                     contract_path='/purchases/{purchaseId}/payments',
                     headers={'Idempotency-Key': unique('no_csrf')})


if __name__ == '__main__':
    unittest.main(verbosity=2)
