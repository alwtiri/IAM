#!/usr/bin/env python3
"""Fails if enums shared between Java and the published contracts drift apart (ADR-0012, ADR-0003). Requires PyYAML."""
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "shared-kernel/src/main/java/com/enterprise/iam/kernel"
SPI = ROOT / "provider-spi/src/main/java/com/enterprise/iam/provider/spi"
CORE = ROOT / "core/src/main/java/com/enterprise/iam/core"


def enum_constants(path: Path) -> list[str]:
    body = path.read_text(encoding="utf-8")
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//.*", "", body)
    start = re.search(r"\benum\s+\w+\s*\{", body)
    body = body[start.end():]
    body = re.split(r"[;}]", body)[0]
    body = re.sub(r"\([^)]*\)", "", body)
    return [c.strip() for c in body.split(",") if c.strip()]


def main() -> int:
    errors = []
    api = yaml.safe_load((ROOT / "contracts/openapi/iam-core-v1.yaml").read_text(encoding="utf-8"))
    schemas = api["components"]["schemas"]

    checks = [
        ("ErrorCode", enum_constants(JAVA / "ErrorCode.java"), schemas["ErrorCode"]["enum"]),
        ("CapabilityStatus", enum_constants(SPI / "CapabilityStatus.java"), schemas["CapabilityStatus"]["enum"]),
        ("IdentityState", enum_constants(CORE / "identity/domain/IdentityState.java"), schemas["IdentityState"]["enum"]),
        ("IdentityType", enum_constants(CORE / "identity/domain/IdentityType.java"), schemas["IdentityType"]["enum"]),
        ("OperationStatus", enum_constants(CORE / "operation/domain/OperationStatus.java"), schemas["OperationStatus"]["enum"]),
    ]
    command = json.loads((ROOT / "contracts/messaging/operation-command.schema.json").read_text(encoding="utf-8"))
    checks.append(("ProviderOperation", enum_constants(SPI / "ProviderOperation.java"), command["properties"]["operation"]["enum"]))

    for name, java, contract in checks:
        if java != contract:
            errors.append(f"{name}: java={java} contract={contract}")

    for e in errors:
        print(f"CONTRACT DRIFT: {e}", file=sys.stderr)
    if not errors:
        print("contracts in sync: " + ", ".join(f"{n} ({len(j)})" for n, j, _ in checks))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
