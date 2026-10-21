"""HTTP helpers for M1 acceptance; no browser simulation or real provider calls."""
import copy
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import time
import uuid

from jsonschema import Draft4Validator, FormatChecker
from referencing import Registry, Resource
import requests
import yaml

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("M1_BASE_URL", os.environ.get("M0_BASE_URL", "http://127.0.0.1:8080/api/v1")).rstrip("/")
CONTRACT = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())


def json_schema(value):
    """Translate OAS 3.0 nullable to Draft 4 without weakening other constraints."""
    if isinstance(value, list):
        return [json_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: json_schema(item) for key, item in value.items() if key != "nullable"}
    if value.get("nullable") is True:
        if "$ref" in result:
            return {"anyOf": [result, {"type": "null"}]}
        if isinstance(result.get("type"), str):
            result["type"] = [result["type"], "null"]
        if "enum" in result and None not in result["enum"]:
            result["enum"].append(None)
    return result


SCHEMA = {"$schema": "http://json-schema.org/draft-04/schema#", **json_schema(copy.deepcopy(CONTRACT))}
REGISTRY = Registry().with_resource("urn:smartprocure:m1-contract", Resource.from_contents(SCHEMA))


def webhook_secret():
    explicit = os.environ.get("M1_MOCK_WEBHOOK_SECRET")
    if explicit:
        return explicit
    # init-demo-env.py emits simple KEY=value lines. Never evaluate shell input.
    env_file = ROOT / ".env"
    if env_file.is_file():
        for line in env_file.read_text().splitlines():
            if line.startswith("M1_MOCK_WEBHOOK_SECRET="):
                value = line.partition("=")[2].strip()
                if value:
                    return value
    return "MOCK_ONLY_LOCAL_WEBHOOK_KEY_NOT_FOR_PRODUCTION"


def unique(prefix):
    return f"{prefix}_{uuid.uuid4().hex}"


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class HttpClient:
    def __init__(self, testcase, register_cleanup=True):
        self.testcase = testcase
        self.session = requests.Session()
        if register_cleanup:
            testcase.addCleanup(self.session.close)

    def call(self, method, path, expected=200, contract_path=None, generic_error=False, **kwargs):
        response = self.session.request(method, BASE + path, timeout=20, **kwargs)
        self.testcase.assertEqual(expected, response.status_code, f"{method} {path}: unexpected HTTP status")
        payload = response.json()
        if generic_error:
            # Reserved future routes have no implemented success contract in M1.
            self.testcase.assertGreaterEqual(expected, 400)
            pointer = "/components/responses/ErrorResponse"
        else:
            path_key = contract_path or path
            definition = CONTRACT["paths"][path_key][method.lower()]["responses"][str(expected)]
            pointer = definition.get("$ref", "")[1:] or (
                "/paths/" + path_key.replace("~", "~0").replace("/", "~1")
                + f"/{method.lower()}/responses/{expected}"
            )
        Draft4Validator({"$ref": "urn:smartprocure:m1-contract#" + pointer + "/content/application~1json/schema"},
                        registry=REGISTRY, format_checker=FormatChecker()).validate(payload)
        self.testcase.assertEqual(payload["requestId"], response.headers.get("X-Request-Id"))
        return payload

    def csrf(self):
        data = self.call("GET", "/auth/csrf")["data"]
        return {data["headerName"]: data["token"]}

    def login(self, persona="OWNER_A"):
        return self.call("POST", "/auth/login", json={"persona": persona}, headers=self.csrf())["data"]

    def write(self, method, path, expected=200, key=None, **kwargs):
        headers = self.csrf()
        if key:
            headers["Idempotency-Key"] = key
        headers.update(kwargs.pop("headers", {}))
        return self.call(method, path, expected, headers=headers, **kwargs)

    def stores(self):
        offset, stores = 0, []
        while True:
            page = self.call("GET", "/stores", params={"limit": 100, "offset": offset})["data"]
            stores.extend(page["items"])
            if len(stores) >= page["total"]:
                return stores
            offset += 100
            self.testcase.assertLessEqual(offset, 10000, "Smoke environment exceeds supported pagination")

    def scenario(self, store_id, scenario, external_order_id, key=None, expected=202):
        payload = self.write("POST", f"/mock/stores/{store_id}/scenarios", expected,
                             contract_path="/mock/stores/{storeId}/scenarios", key=key or unique("scenario"),
                             json={"scenario": scenario, "externalOrderId": external_order_id})
        return payload["data"] if expected < 400 else payload

    def orders(self, **params):
        return self.call("GET", "/orders", params=params)["data"]

    def poll_order(self, store_id, external_order_id, status=None, minimum_version=1, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            page = self.orders(storeId=store_id, q=external_order_id)
            self.testcase.assertLessEqual(page["total"], 1, "Duplicate sales orders were created")
            if page["items"]:
                order = page["items"][0]
                if (status is None or order["sourceStatus"] == status) and order["sourceVersion"] >= minimum_version:
                    return order
            time.sleep(0.25)
        self.testcase.fail(f"Worker did not produce expected synthetic order: status={status}, minVersion={minimum_version}")

    def detail(self, order_id, expected=200):
        return self.call("GET", f"/orders/{order_id}", expected, contract_path="/orders/{orderId}")

    def callback(self, integration_id, event, expected=200, timestamp=None, nonce=None, signature=None):
        stamp = str(int(time.time()) if timestamp is None else timestamp)
        nonce = nonce or str(uuid.uuid4())
        body = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        secret = webhook_secret()
        signed = f"{stamp}.{nonce}.{integration_id}.{body}".encode()
        signature = signature or hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        return self.call("POST", f"/webhooks/mock/stores/{integration_id}", expected,
                         contract_path="/webhooks/mock/stores/{integrationId}", data=body.encode(), headers={
                             "Content-Type": "application/json", "X-Mock-Timestamp": stamp,
                             "X-Mock-Nonce": nonce, "X-Mock-Signature": signature})
