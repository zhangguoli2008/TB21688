#!/usr/bin/env python3
"""Real HTTP + OpenAPI acceptance for the synthetic M2 demo, never real trade.

Run only against a dedicated local demo. Uses fresh stores/orders, pauses its
mappings and restores offer settings afterwards. Historical synthetic records
remain for review. M2_BASE_URL defaults to M1_BASE_URL / localhost:8080/api/v1.
"""
import os
import time
import unittest

if os.environ.get("M2_BASE_URL"):
    os.environ["M1_BASE_URL"] = os.environ["M2_BASE_URL"]
from m1_http import HttpClient, unique


class M2Smoke(unittest.TestCase):
    def setUp(self):
        self.owner, self.other, self.support = (HttpClient(self) for _ in range(3))
        self.owner.login()
        self.other.login("OWNER_B")
        self.support.login("SUPPORT_A")
        self.changed_offers, self.mappings, self.original_policy = {}, [], None
        self.addCleanup(self.restore)
        self.store = self.owner.write("POST", "/stores", key=unique("m2_store"), json={
            "platform": "DOUYIN", "externalShopId": unique("M2_HTTP"),
            "displayName": "M2 HTTP 合成测试店"})["data"]
        self.offers = self.list_offers()
        self.blue = [offer for offer in self.offers if offer["compatibleSalesSkuId"] == "MOCK-SKU-BLUE"]
        self.assertGreaterEqual(len(self.blue), 2, "Repeatable fixtures need two independently reviewed suppliers")
        # Repeated runs must not exhaust the synthetic tenant's daily test budget.
        self.original_policy = self.owner.call("GET", "/procurement-policy")["data"]
        self.owner.write("PUT", "/procurement-policy", json={
            **{key: value for key, value in self.original_policy.items() if key != "mode"},
            "enabled": True, "maxSingleFen": 1000000000000, "maxDailyFen": 1000000000000,
            "maxShippingFen": 1000000000, "minProfitFen": 0, "platformFeeFen": 0, "reserveFen": 0})

    def list_offers(self, client=None):
        return (client or self.owner).call("GET", "/supplier-offers", params={"limit": 100})["data"]["items"]

    def offer(self, offer_id):
        return next(offer for offer in self.list_offers() if offer["id"] == offer_id)

    def configure(self, offer, **changes):
        current = self.offer(offer["id"])
        if offer["id"] not in self.changed_offers:
            self.changed_offers[offer["id"]] = current
        return self.owner.write("PATCH", f"/supplier-offers/{offer['id']}/mock-config",
            contract_path="/supplier-offers/{offerId}/mock-config",
            json={"version": current["version"], **changes})["data"]

    def restore(self):
        # Stop future planning before making intentionally faulty offers normal again.
        for mapping in self.mappings:
            page = self.owner.call("GET", "/sku-mappings", params={"storeId": mapping["storeId"], "limit": 100})["data"]
            current = next(item for item in page["items"] if item["id"] == mapping["id"])
            self.owner.write("PATCH", f"/sku-mappings/{current['id']}", contract_path="/sku-mappings/{mappingId}",
                             json={"version": current["version"], "status": "PAUSED"})
        mutable = ("unitPriceFen", "shippingFen", "stock", "minimumQty", "cipherPlatforms", "regions", "quoteTtlSeconds", "fault")
        for offer_id, original in self.changed_offers.items():
            current = self.offer(offer_id)
            self.owner.write("PATCH", f"/supplier-offers/{offer_id}/mock-config", contract_path="/supplier-offers/{offerId}/mock-config",
                             json={"version": current["version"], **{key: original[key] for key in mutable}})
        if self.original_policy is not None:
            current = self.owner.call("GET", "/procurement-policy")["data"]
            self.owner.write("PUT", "/procurement-policy", json={
                **{key: value for key, value in self.original_policy.items() if key != "mode"},
                "version": current["version"]})

    def mapping(self, offer=None, confirm=True, priority=100):
        offer = offer or self.blue[0]
        body = {"storeId": self.store["id"], "salesSkuId": offer["compatibleSalesSkuId"],
                "supplierOfferId": offer["id"], "priority": priority}
        key = unique("m2_mapping")
        mapping = self.owner.write("POST", "/sku-mappings", key=key, json=body)["data"]
        repeated = self.owner.write("POST", "/sku-mappings", key=key, json=body)["data"]
        self.assertEqual(mapping["id"], repeated["id"])
        self.assertEqual("REVIEW", mapping["status"])
        self.mappings.append(mapping)
        if confirm:
            mapping = self.owner.write("PATCH", f"/sku-mappings/{mapping['id']}", contract_path="/sku-mappings/{mappingId}",
                json={"version": mapping["version"], "status": "CONFIRMED", "reviewAcknowledged": True})["data"]
            self.assertIsNotNone(mapping["confirmedBy"])
            self.assertIsNotNone(mapping["confirmedAt"])
        return mapping

    def sales_item(self, scenario="PAID"):
        external = unique("M2_ORDER")
        self.owner.scenario(self.store["id"], scenario, external)
        order = self.owner.poll_order(self.store["id"], external)
        detail = self.owner.detail(order["id"])["data"]
        return next(item for item in detail["items"] if item["externalSkuId"] == "MOCK-SKU-BLUE")

    def evaluate(self, item):
        return self.owner.write("POST", "/purchase-intents/evaluate", json={"salesOrderItemId": item["id"]})["data"]

    def intents(self, client=None):
        return (client or self.owner).call("GET", "/purchase-intents", params={"storeId": self.store["id"]})["data"]["items"]

    def wait_intent(self, item, states, timeout=35):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matches = [intent for intent in self.intents() if intent["salesOrderItemId"] == item["id"]]
            self.assertLessEqual(len(matches), 1, "A sales item must never have duplicate purchase intents")
            if matches and matches[0]["state"] in states:
                return matches[0]
            time.sleep(0.2)
        self.fail(f"Mock procurement worker did not reach {states}")

    def assert_blocked(self, item):
        evaluation = self.evaluate(item)
        self.assertFalse(evaluation["allowed"])
        self.assertTrue(evaluation["reasons"])
        time.sleep(1.2)
        self.assertEqual([], self.intents())
        return evaluation

    def test_01_offers_quotes_integer_cents_and_tenant_scope(self):
        offer = self.configure(self.blue[0], unitPriceFen=1701, shippingFen=503, stock=100, fault="NORMAL")
        quote = self.owner.write("POST", f"/supplier-offers/{offer['id']}/quotes",
            contract_path="/supplier-offers/{offerId}/quotes", json={"storeId": self.store["id"], "qty": 3})["data"]
        self.assertEqual(5606, quote["totalFen"])
        self.assertEqual("CNY", quote["currency"])
        self.assertFalse({offer["id"] for offer in self.list_offers()} & {offer["id"] for offer in self.list_offers(self.other)})
        self.other.write("POST", f"/supplier-offers/{offer['id']}/quotes", 404,
            contract_path="/supplier-offers/{offerId}/quotes", json={"storeId": self.store["id"], "qty": 1})

    def test_02_confirmation_metadata_multiple_alternatives_and_cas(self):
        first, second = self.mapping(self.blue[0]), self.mapping(self.blue[1], priority=200)
        self.assertNotEqual(first["supplierOfferId"], second["supplierOfferId"])
        self.owner.write("PATCH", f"/sku-mappings/{first['id']}", 409, contract_path="/sku-mappings/{mappingId}",
                         json={"version": first["version"] - 1, "status": "PAUSED"})
        self.assertEqual(2, self.owner.call("GET", "/sku-mappings", params={"storeId": self.store["id"], "status": "CONFIRMED"})["data"]["total"])

    def test_03_no_mapping_no_purchase(self):
        self.assert_blocked(self.sales_item())

    def test_04_unreviewed_mapping_cannot_purchase(self):
        mapping = self.mapping(confirm=False)
        self.owner.write("PATCH", f"/sku-mappings/{mapping['id']}", 409, contract_path="/sku-mappings/{mappingId}",
                         json={"version": mapping["version"], "status": "CONFIRMED", "reviewAcknowledged": False})
        self.assert_blocked(self.sales_item())

    def test_05_stock_shortage_no_purchase(self):
        self.configure(self.blue[0], fault="OUT_OF_STOCK")
        self.mapping()
        self.assert_blocked(self.sales_item())

    def test_06_ciphertext_capability_is_platform_specific(self):
        self.configure(self.blue[0], cipherPlatforms=["TAOBAO"], fault="NORMAL")
        self.mapping()
        self.assert_blocked(self.sales_item())

    def test_07_automatic_procurement_has_one_intent_one_unpaid_order(self):
        self.configure(self.blue[0], unitPriceFen=1701, shippingFen=503, stock=100, fault="NORMAL")
        self.mapping()
        item = self.sales_item("DUPLICATE")
        intent = self.wait_intent(item, {"AWAITING_PAYMENT"})
        self.assertEqual(3905, intent["estimatedTotalFen"])
        purchase = self.owner.call("GET", f"/purchases/{intent['purchaseOrderId']}", contract_path="/purchases/{purchaseId}")["data"]
        self.assertEqual("AWAITING_PAYMENT", purchase["state"])
        self.assertEqual(3905, purchase["totalFen"])
        self.assertTrue(purchase["externalOrderId"].startswith("MOCK"))
        self.other.call("GET", f"/purchases/{purchase['id']}", 404, contract_path="/purchases/{purchaseId}")
        self.support.call("GET", f"/purchases/{purchase['id']}", 404, contract_path="/purchases/{purchaseId}")
        self.other.call("GET", f"/purchase-intents/{intent['id']}", 404, contract_path="/purchase-intents/{intentId}")
        # A new command cannot create a second intent for an already-procured item.
        result = self.owner.write("POST", "/purchase-intents", 202, key=unique("m2_repeat"),
                                 json={"salesOrderItemId": item["id"], "quoteId": intent["quote"]["id"]})["data"]
        self.assertEqual(intent["id"], result["id"])
        self.assertEqual(1, len(self.intents()))

    def test_08_timeout_after_create_recovers_by_query(self):
        self.configure(self.blue[0], fault="CREATE_TIMEOUT_AFTER", stock=100)
        self.mapping()
        item = self.sales_item()
        intent = self.wait_intent(item, {"AWAITING_PAYMENT"})
        page = self.owner.call("GET", "/purchases", params={"storeId": self.store["id"]})["data"]
        self.assertEqual(1, page["total"])
        self.assertEqual(intent["purchaseOrderId"], page["items"][0]["id"])

    def test_09_timeout_before_create_never_blindly_recreates(self):
        self.configure(self.blue[0], fault="CREATE_TIMEOUT_BEFORE", stock=100)
        self.mapping()
        item = self.sales_item()
        intent = self.wait_intent(item, {"REVIEW_REQUIRED"})
        self.assertIsNotNone(intent["failureCode"])
        time.sleep(1)
        self.assertEqual(intent["id"], self.wait_intent(item, {"REVIEW_REQUIRED"})["id"])
        purchase = self.owner.call("GET", f"/purchases/{intent['purchaseOrderId']}", contract_path="/purchases/{purchaseId}")["data"]
        self.assertIsNone(purchase["externalOrderId"])

    def test_10_policy_limits_refund_and_frontend_tenant_injection(self):
        path = f"/procurement-policies/{self.store['id']}"
        policy = self.owner.call("GET", path, contract_path="/procurement-policies/{storeId}")["data"]
        self.owner.write("PUT", path, contract_path="/procurement-policies/{storeId}",
                         json={**{key: value for key, value in policy.items() if key != "mode"}, "maxSingleFen": 1})
        self.configure(self.blue[0], fault="NORMAL", stock=100)
        self.mapping()
        self.assert_blocked(self.sales_item())
        self.owner.write("POST", "/purchase-intents/evaluate", 400,
                         json={"salesOrderItemId": self.sales_item("REFUNDED")["id"], "tenantId": "22222222-2222-4222-8222-222222222222"})

    def test_11_staff_cannot_change_sourcing_or_procurement_rules(self):
        self.support.call("GET", "/supplier-offers", 403)
        self.support.write("POST", "/sku-mappings", 403, key=unique("m2_forbidden"), json={
            "storeId": self.store["id"], "salesSkuId": "MOCK-SKU-BLUE", "supplierOfferId": self.blue[0]["id"]})
        self.support.write("POST", "/purchase-intents/evaluate", 403, json={"salesOrderItemId": self.sales_item()["id"]})

    def test_12_config_and_pagination_validation(self):
        self.owner.call("GET", "/supplier-offers", 400, params={"limit": 101})
        self.owner.call("GET", "/purchases", 400, params={"offset": 10001})
        offer = self.blue[0]
        self.owner.write("PATCH", f"/supplier-offers/{offer['id']}/mock-config", 400,
            contract_path="/supplier-offers/{offerId}/mock-config", json={"version": offer["version"], "unitPriceFen": 1.5})

    def test_13_quote_timeout_is_visible_and_cannot_procure(self):
        self.configure(self.blue[0], fault="QUOTE_TIMEOUT")
        self.mapping()
        self.owner.write("POST", f"/supplier-offers/{self.blue[0]['id']}/quotes", 503,
            contract_path="/supplier-offers/{offerId}/quotes", json={"storeId": self.store["id"], "qty": 2})
        self.assert_blocked(self.sales_item())

    def test_14_refunded_sale_never_creates_intent(self):
        self.configure(self.blue[0], fault="NORMAL", stock=100)
        self.mapping()
        self.assert_blocked(self.sales_item("REFUNDED"))


if __name__ == "__main__":
    print("M2 synthetic Mock-only acceptance; no real procurement or payment.", flush=True)
    unittest.main(verbosity=2)
