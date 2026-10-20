#!/usr/bin/env python3
"""Create local synthetic infrastructure credentials without overwriting existing data."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / ".env")
args = parser.parse_args()
try:
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    parser.exit(1, "Existing environment file preserved; choose another --output or reuse it.\n")
with os.fdopen(fd, "w") as stream:
    stream.write("# LOCAL MOCK DEMO ONLY. Never use production credentials.\n")
    for name in ("POSTGRES_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_PASSWORD"):
        stream.write(f"{name}={secrets.token_urlsafe(32)}\n")
print(f"Created {args.output}; values are intentionally hidden.")
