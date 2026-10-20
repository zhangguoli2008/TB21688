#!/usr/bin/env python3
"""Exercise a running M0 API over HTTP and validate responses against the contract."""
import os
from pathlib import Path
import unittest

from jsonschema import Draft4Validator, FormatChecker
from referencing import Registry, Resource
import requests
import yaml

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())
BASE = os.environ.get("M0_BASE_URL", "http://127.0.0.1:8080/api/v1").rstrip("/")
A = "11111111-1111-4111-8111-111111111111"
B = "22222222-2222-4222-8222-222222222222"
REGISTRY = Registry().with_resource("urn:smartprocure:contract", Resource.from_contents(
    {"$schema": "http://json-schema.org/draft-04/schema#", **CONTRACT}))


class M0Smoke(unittest.TestCase):
    def setUp(self):
        self.session = requests.Session()
        self.addCleanup(self.session.close)

    def call(self, method, path, expected=200, contract_path=None, **kwargs):
        response = self.session.request(method, BASE + path, timeout=20, **kwargs)
        self.assertEqual(expected, response.status_code, f"{method} {path}: unexpected HTTP status")
        payload = response.json()
        response_definition = CONTRACT["paths"][contract_path or path][method.lower()]["responses"][str(expected)]
        if "$ref" in response_definition:
            pointer = response_definition["$ref"][1:]
        else:
            pointer = "/paths/" + (contract_path or path).replace("~", "~0").replace("/", "~1") + f"/{method.lower()}/responses/{expected}"
        # Resolve local component references using the exact checked-in contract.
        validator = Draft4Validator({"$ref": "urn:smartprocure:contract#" + pointer + "/content/application~1json/schema"},
            registry=REGISTRY, format_checker=FormatChecker())
        validator.validate(payload)
        self.assertEqual(payload["requestId"], response.headers.get("X-Request-Id"))
        return payload

    def csrf(self):
        data = self.call("GET", "/auth/csrf")["data"]
        return {data["headerName"]: data["token"]}

    def login(self, persona="OWNER_A"):
        return self.call("POST", "/auth/login", json={"persona": persona}, headers=self.csrf())["data"]

    def test_unauthenticated_me(self):
        self.assertEqual("UNAUTHENTICATED", self.call("GET", "/me", 401)["code"])

    def test_login_and_me_have_server_tenant(self):
        self.assertEqual(A, self.login()["currentTenantId"])
        me = self.call("GET", "/me", headers={"X-Tenant-Id": B})["data"]
        self.assertEqual(A, me["currentTenantId"])
        self.assertEqual("MOCK", me["mode"])

    def test_login_without_csrf_is_denied(self):
        self.assertEqual("FORBIDDEN", self.call("POST", "/auth/login", 403, json={"persona": "OWNER_A"})["code"])

    def test_unknown_persona_is_rejected(self):
        self.assertEqual("VALIDATION_ERROR", self.call("POST", "/auth/login", 400,
            json={"persona": "UNKNOWN"}, headers=self.csrf())["code"])

    def test_cross_tenant_resource_is_hidden(self):
        self.login()
        self.assertEqual(A, self.call("GET", f"/demo/resources/{A}", contract_path="/demo/resources/{id}")["data"]["tenantId"])
        self.assertEqual("NOT_FOUND", self.call("GET", f"/demo/resources/{B}", 404,
            contract_path="/demo/resources/{id}", headers={"X-Tenant-Id": B})["code"])

    def test_support_cannot_read_system_status(self):
        self.login("SUPPORT_A")
        self.assertEqual("FORBIDDEN", self.call("GET", "/system/status", 403)["code"])

    def test_real_channels_closed_and_services_reported(self):
        self.login()
        data = self.call("GET", "/system/status")["data"]
        self.assertFalse(data["realChannelsEnabled"])
        self.assertEqual({"api", "postgresql", "redis", "rabbitmq"}, {x["name"] for x in data["services"]})
        if os.environ.get("M0_REQUIRE_HEALTHY") == "1":
            self.assertEqual({"UP"}, {x["status"] for x in data["services"]}, "Compose must connect to every dependency")

    def test_logout_revokes_session(self):
        self.login()
        self.call("POST", "/auth/logout", headers=self.csrf())
        self.call("GET", "/me", 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
