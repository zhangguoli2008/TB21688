#!/usr/bin/env python3
"""Run real HTTP M1 Mock acceptance against an already-running demo API.

Set M1_BASE_URL (or M0_BASE_URL) and M1_MOCK_WEBHOOK_SECRET if overridden.
Every run uses unique synthetic IDs. It creates a few synthetic stores/orders and
keeps them for inspection; it never calls a real e-commerce or payment service.
T-010/T-014/T-015/T-021 here cover M1 boundaries only: no M2 procurement object,
M3 payment-policy implementation, or production tenant switch is claimed.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import time
import unittest
import uuid

from m1_http import HttpClient, unique, utc_now


class M1Smoke(unittest.TestCase):
    def setUp(self):
        self.owner = HttpClient(self)
        self.owner.login()
        self.support = HttpClient(self)
        self.support.login("SUPPORT_A")
        self.other = HttpClient(self)
        self.other.login("OWNER_B")
        self.support_stores = self.support.stores()
        self.a_stores = self.owner.stores()
        self.b_stores = self.other.stores()
        self.assertEqual(1, len(self.support_stores), "M1 seed must grant SUPPORT exactly one store")
        self.store = self.support_stores[0]
        self.assertTrue(self.store["enabled"])
        self.assertEqual("ACTIVE", self.store["authStatus"])
        self.other_store = next(store for store in self.b_stores if store["enabled"] and store["authStatus"] == "ACTIVE" and store["platform"] == self.store["platform"])

    def create_store(self, key=None, **changes):
        body = {"platform": "DOUYIN", "externalShopId": unique("HTTP_SHOP"), "displayName": "HTTP 合成演示店"}
        body.update(changes)
        return self.owner.write("POST", "/stores", key=key or unique("store"), json=body)["data"], body

    def test_01_store_scope_and_cross_tenant_idor(self):
        """T-002/T-015 M1 scope: tenant/store objects, not future purchases."""
        self.assertGreaterEqual(len(self.a_stores), 2)
        self.assertGreaterEqual(len(self.b_stores), 2)
        a_ids = {store["id"] for store in self.a_stores}
        b_ids = {store["id"] for store in self.b_stores}
        self.assertFalse(a_ids & b_ids)
        self.assertIn(self.store["id"], a_ids)
        hidden = next(store for store in self.a_stores if store["id"] != self.store["id"] and store["enabled"] and store["authStatus"] == "ACTIVE")
        self.support.call("GET", f"/stores/{hidden['id']}", 404, contract_path="/stores/{storeId}")
        self.owner.call("GET", f"/stores/{self.other_store['id']}", 404, contract_path="/stores/{storeId}")
        external = unique("restricted_store")
        self.owner.scenario(hidden["id"], "PAID", external)
        order = self.owner.poll_order(hidden["id"], external, "PAID")
        self.assertEqual(0, self.support.orders(q=external)["total"])
        self.support.call("GET", "/orders", 404, params={"storeId": hidden["id"]})
        self.support.detail(order["id"], 404)

    def test_02_store_create_idempotency_and_conflicting_body(self):
        key = unique("create")
        store, body = self.create_store(key)
        repeated = self.owner.write("POST", "/stores", key=key, json=body)["data"]
        self.assertEqual(store["id"], repeated["id"])
        self.owner.write("POST", "/stores", 409, key=key, json={**body, "displayName": "同键不同内容"})

    def test_03_store_cas_auth_lifecycle_and_tenant_injection(self):
        store, body = self.create_store()
        path = f"/stores/{store['id']}"
        changed = self.owner.write("PATCH", path, contract_path="/stores/{storeId}",
                                   json={"version": store["version"], "displayName": "HTTP 已改名", "enabled": False})["data"]
        self.assertFalse(changed["enabled"])
        self.assertEqual(store["version"] + 1, changed["version"])
        self.assertEqual("AUTHORIZATION_UNAVAILABLE", self.owner.scenario(store["id"], "PAID", unique("disabled"), expected=409)["code"])
        self.owner.write("PATCH", path, 409, contract_path="/stores/{storeId}", json={"version": store["version"], "enabled": True})
        for auth in ("EXPIRED", "REVOKED", "ACTIVE"):
            changed = self.owner.write("PATCH", path, contract_path="/stores/{storeId}",
                                       json={"version": changed["version"], "authStatus": auth, "enabled": True})["data"]
            self.assertEqual(auth, changed["authStatus"])
            if auth != "ACTIVE":
                self.assertEqual("AUTHORIZATION_UNAVAILABLE", self.owner.scenario(store["id"], "PAID", unique("expired"), expected=409)["code"])
                event = {"eventId": unique("blocked"), "externalOrderId": unique("blocked_order"), "sourceVersion": 1, "eventOccurredAt": utc_now()}
                callback = HttpClient(self)
                self.assertEqual("AUTHORIZATION_UNAVAILABLE", callback.callback(store["integrationId"], event, 409)["code"])
        self.owner.write("POST", "/stores", 400, key=unique("injected"), json={**body, "tenantId": self.other.login("OWNER_B")["currentTenantId"]})
        self.owner.write("PATCH", path, 400, contract_path="/stores/{storeId}",
                         json={"version": changed["version"], "tenantId": str(uuid.uuid4())})

    def test_04_support_cannot_write_or_access_future_payment_policy(self):
        """T-014 permission boundary only; payment-policy business remains M3."""
        self.support.write("POST", "/stores", 403, key=unique("forbidden"),
                           json={"platform": "DOUYIN", "externalShopId": unique("shop"), "displayName": "无权注册"})
        self.support.write("PATCH", f"/stores/{self.store['id']}", 403, contract_path="/stores/{storeId}",
                           json={"version": self.store["version"], "enabled": False})
        self.support.write("PUT", "/payment-policy", 403, key=unique("policy"), json={
            "mode": "MANUAL", "maxSingleFen": 0, "maxDailyFen": 0, "minEstimatedProfitFen": 0, "isPaused": True})

    def test_05_paid_order_detail_items_and_timeline(self):
        external = unique("paid")
        accepted = self.owner.scenario(self.store["id"], "PAID", external)
        self.assertEqual("MOCK", accepted["mode"])
        order = self.owner.poll_order(self.store["id"], external, "PAID")
        detail = self.owner.detail(order["id"])["data"]
        self.assertEqual("VERIFIED_PAID", detail["localStatus"])
        self.assertEqual(1, detail["sourceVersion"])
        self.assertEqual(2, len(detail["items"]))
        self.assertGreaterEqual(len(detail["timeline"]), 1)
        self.assertEqual(detail["paidAmountFen"], sum(item["paidAllocatedFen"] for item in detail["items"]))
        self.assertEqual("MOCK", detail["mode"])

    def test_06_duplicate_ten_deliveries_create_one_sales_order(self):
        """T-001: ten Mock deliveries dedupe; procurement is not implemented."""
        external, key = unique("duplicate"), unique("duplicate_request")
        result = self.owner.scenario(self.store["id"], "DUPLICATE", external, key)
        self.assertEqual(10, result["acceptedEvents"])
        repeated = self.owner.scenario(self.store["id"], "DUPLICATE", external, key)
        self.assertEqual(result, repeated)
        order = self.owner.poll_order(self.store["id"], external, "PAID")
        self.assertEqual(1, self.owner.orders(storeId=self.store["id"], q=external)["total"])
        self.assertEqual(2, len(self.owner.detail(order["id"])["data"]["items"]))

    def test_07_same_external_order_across_tenants_is_isolated(self):
        """T-002 fully exercises same external order ID and both detail directions."""
        external = unique("shared")
        self.owner.scenario(self.store["id"], "PAID", external)
        self.other.scenario(self.other_store["id"], "PAID", external)
        a = self.owner.poll_order(self.store["id"], external, "PAID")
        b = self.other.poll_order(self.other_store["id"], external, "PAID")
        self.assertNotEqual(a["id"], b["id"])
        self.owner.detail(b["id"], 404)
        self.other.detail(a["id"], 404)
        self.assertEqual(1, self.owner.orders(q=external)["total"])
        self.assertEqual(1, self.other.orders(q=external)["total"])

    def test_08_refund_and_out_of_order_authority(self):
        """T-010 M1 boundary: authoritative refund blocks the sales eligibility."""
        external = unique("refund")
        self.owner.scenario(self.store["id"], "REFUNDED", external)
        refunded = self.owner.poll_order(self.store["id"], external, "REFUNDED")
        self.assertEqual("SUSPENDED", refunded["localStatus"])
        self.assertEqual(1, refunded["sourceVersion"])
        self.assertTrue(all(item["cancelledQty"] == item["qty"] for item in self.owner.detail(refunded["id"])["data"]["items"]))
        external = unique("outoforder")
        self.owner.scenario(self.store["id"], "OUT_OF_ORDER", external)
        order = self.owner.poll_order(self.store["id"], external, "REFUNDED", minimum_version=2)
        time.sleep(0.5)
        final = self.owner.detail(order["id"])["data"]
        self.assertEqual("REFUNDED", final["sourceStatus"])
        self.assertEqual(2, final["sourceVersion"])
        self.assertEqual("SUSPENDED", final["localStatus"])

    def test_09_delayed_event_is_not_synchronous(self):
        external = unique("delayed")
        self.owner.scenario(self.store["id"], "DELAYED", external)
        self.assertEqual(0, self.owner.orders(storeId=self.store["id"], q=external)["total"])
        self.owner.poll_order(self.store["id"], external, "PAID")

    def test_10_filters_pagination_and_validation(self):
        external = unique("filter")
        self.owner.scenario(self.store["id"], "PAID", external)
        self.owner.poll_order(self.store["id"], external, "PAID")
        now = datetime.now(timezone.utc)
        page = self.owner.orders(storeId=self.store["id"], q=external, sourceStatus="PAID", limit=1, offset=0,
                                 **{"from": (now - timedelta(minutes=5)).isoformat(), "to": (now + timedelta(minutes=5)).isoformat()})
        self.assertEqual(1, page["total"])
        self.assertEqual(external, page["items"][0]["externalOrderId"])
        self.assertEqual(0, self.owner.orders(storeId=self.store["id"], q=external, sourceStatus="CANCELLED")["total"])
        self.assertEqual([], self.owner.orders(q=external, limit=1, offset=1)["items"])
        for params in ({"limit": 101}, {"offset": -1}, {"sourceStatus": "UNKNOWN"}, {"from": "bad"}, {"q": "x" * 81}):
            self.owner.call("GET", "/orders", 400, params=params)
        self.owner.call("GET", "/stores", 400, params={"limit": 101})

    def test_11_signed_callback_signature_window_dedup_and_nonce(self):
        """T-016: actual HMAC HTTP boundary, including invalid signatures and replay."""
        external = unique("callback")
        self.owner.scenario(self.store["id"], "PAID", external)
        order = self.owner.poll_order(self.store["id"], external, "PAID")
        event = {"eventId": unique("event"), "externalOrderId": external, "sourceVersion": order["sourceVersion"], "eventOccurredAt": utc_now()}
        integration = self.store["integrationId"]
        callback = HttpClient(self)  # External callbacks authenticate by HMAC, without a session.
        callback.callback(integration, event, 401, signature="0" * 64)
        callback.callback(integration, event, 401, timestamp=int(time.time()) - 601)
        first = callback.callback(integration, event)["data"]
        self.assertFalse(first["duplicate"])
        self.assertTrue(callback.callback(integration, event)["data"]["duplicate"])
        callback.callback(integration, {**event, "sourceVersion": event["sourceVersion"] + 1}, 409)
        nonce = str(uuid.uuid4())
        callback.callback(integration, {**event, "eventId": unique("nonce1")}, nonce=nonce)
        callback.callback(integration, {**event, "eventId": unique("nonce2")}, 409, nonce=nonce)
        final = self.owner.detail(order["id"])["data"]
        self.assertEqual(order["sourceVersion"], final["sourceVersion"])

    def test_12_parallel_sessions_and_unopened_purchase_boundary(self):
        """T-021 separate sessions; T-015 future purchase route refusal, not object isolation."""
        a = self.owner.call("GET", "/me")["data"]["currentTenantId"]
        b = self.other.call("GET", "/me")["data"]["currentTenantId"]
        def one(index):
            client, expected, forged = (self.owner, a, b) if index % 2 == 0 else (self.other, b, a)
            isolated = HttpClient(self, register_cleanup=False)
            isolated.session.cookies.update(client.session.cookies)
            try:
                data = isolated.call("GET", "/me", headers={"X-Tenant-Id": forged}, params={"tenant_id": forged})["data"]
                return data["currentTenantId"], expected
            finally:
                isolated.session.close()
        with ThreadPoolExecutor(max_workers=4) as executor:
            for actual, expected in executor.map(one, range(12)):
                self.assertEqual(expected, actual)
        self.owner.call("GET", f"/purchases/{uuid.uuid4()}", 404, generic_error=True)
        previous = HttpClient(self)
        previous.session.cookies.update(self.owner.session.cookies)
        switched = self.owner.login("OWNER_B")  # Demo persona re-login, not a tenant-switch feature.
        self.assertEqual(b, switched["currentTenantId"])
        self.assertEqual(b, self.owner.call("GET", "/me", headers={"X-Tenant-Id": a})["data"]["currentTenantId"])
        previous.call("GET", "/me", 401)


if __name__ == "__main__":
    print("M1 Mock-only HTTP acceptance. T-010/014/015/021 cover documented M1 boundaries; later business flows remain unimplemented.")
    unittest.main(verbosity=2)
