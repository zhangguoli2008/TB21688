"""Three reproducible M3 demonstrations through local Mock HTTP, never a cashier."""
import json
import os
import unittest


def execute(case, mode):
    from m3_http import PaymentDemo
    demo = PaymentDemo(case)
    purchase = demo.purchase('QUERY_TIMEOUT' if mode == 'unknown' else 'NORMAL')
    if mode != 'manual':
        demo.set_mandate('ACTIVE')
    demo.set_policy(mode='MANUAL' if mode == 'manual' else 'AUTO', isPaused=False,
        newSupplierFirstOrderManual=False, minEstimatedProfitFen=0,
        maxSingleFen=100000, maxDailyFen=1000000, maxStoreSingleFen=100000, maxStoreDailyFen=1000000)
    steps = []
    if mode == 'manual':
        entry = demo.checkout(purchase)['data']
        steps.append({'step': '本地Mock人工页，无支付凭据或第三方链接', 'path': entry['url']})
        demo.receipt(entry['attemptId'], 'UNPAID')
        unpaid = demo.wait(purchase, {'WAIT_MERCHANT_PAYMENT'})
        case.assertEqual(0, unpaid['paidFen'])
        steps.append({'step': '模拟已完成操作但权威未付', 'state': unpaid['state'], 'paidFen': unpaid['paidFen']})
        demo.receipt(entry['attemptId'], 'PAID')
        result = demo.wait(purchase, {'PAID'})
        steps.append({'step': '合成权威已付回执，Worker再次查验', 'state': result['state']})
    elif mode == 'auto':
        result = demo.wait(purchase, {'PAID'})
        steps.append({'step': '有效Mock签约与规则通过，异步付款并权威核实', 'state': result['state']})
    else:
        result = demo.wait(purchase, {'UNKNOWN'})
        original = result['attempt']['id']
        case.assertEqual('PAYMENT_STATUS_UNKNOWN', demo.request(purchase, expected=409)['code'])
        case.assertEqual('PAYMENT_STATUS_UNKNOWN', demo.checkout(purchase, expected=409)['code'])
        demo.reconcile(purchase)
        case.assertEqual(original, demo.status(purchase)['attempt']['id'])
        steps.append({'step': '持续查单超时；自动/人工付款入口均409，只能查询同一attempt', 'state': result['state']})
    web = os.environ.get('M3_WEB_URL', 'http://127.0.0.1:4173').rstrip('/')
    return {'demo': mode, 'runtimeMode': 'MOCK', 'realPaymentPerformed': False,
        'storeId': demo.store['id'], 'purchaseId': purchase['id'],
        'paymentAttemptId': result['attempt']['id'], 'state': result['state'],
        'amountFen': result['amountFen'], 'paidFen': result['paidFen'], 'steps': steps,
        'detailUrl': f"{web}/purchases/{purchase['id']}"}


def run(mode):
    if os.environ.get('M3_BASE_URL'):
        os.environ['M1_BASE_URL'] = os.environ['M3_BASE_URL']

    class Demonstration(unittest.TestCase):
        def runTest(self):
            self.output = execute(self, mode)

    case = Demonstration()
    result = case.run()
    if not result.wasSuccessful():
        raise SystemExit('Mock demonstration failed:\n' + '\n'.join(trace for _, trace in result.errors + result.failures))
    print(json.dumps(case.output, ensure_ascii=False, indent=2))
