#!/usr/bin/env python3
"""Basic source checks; not a replacement for a dependency or production security audit."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
rules = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "cloud access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "GitHub credential": re.compile(r"\b(?:ghp|gho|ghs)_[A-Za-z0-9]{30,}\b"),
    "external commerce endpoint in application": re.compile(
        r"https?://[^\s\"']*\b(?:taobao|1688|pinduoduo|alipay|douyin)\.com", re.I),
}
failures = []
count = 0
for scope in ("apps/api/src", "apps/web/src", "infra", ".github", "contracts"):
    for path in (root / scope).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".ts", ".vue", ".yml", ".yaml", ".sql", ".conf"}:
            continue
        count += 1
        value = path.read_text(encoding="utf-8")
        for name, rule in rules.items():
            if rule.search(value):
                failures.append(f"{path.relative_to(root)}: {name}")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print(f"Basic safety scan: {count} source/config files checked, 0 findings.")
