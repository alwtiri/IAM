#!/usr/bin/env python3
"""Fails if enums shared between Java and the published contracts drift apart (ADR-0012, ADR-0003)."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def java_enum(path: Path, pattern: str) -> list[str]:
    return re.findall(pattern, path.read_text(encoding="utf-8"), re.M)


def main() -> int:
    errors = []
    error_codes = java_enum(ROOT / "shared-kernel/src/main/java/com/enterprise/iam/kernel/ErrorCode.java", r"^\s{4}([A-Z_]+)\(\d+")
    openapi = (ROOT / "contracts/openapi/iam-core-v1.yaml").read_text(encoding="utf-8")
    block = openapi.split("    ErrorCode:")[1].split("    ApiError:")[0]
    if error_codes != re.findall(r"-\s+([A-Z_]+)", block):
        errors.append("ErrorCode enum differs between shared-kernel and OpenAPI contract")

    operations = java_enum(ROOT / "provider-spi/src/main/java/com/enterprise/iam/provider/spi/ProviderOperation.java", r"^\s{4}([A-Z_]+)\(Capability")
    command = json.loads((ROOT / "contracts/messaging/operation-command.schema.json").read_text(encoding="utf-8"))
    if operations != command["properties"]["operation"]["enum"]:
        errors.append("ProviderOperation enum differs between provider-spi and operation-command schema")

    statuses = java_enum(ROOT / "provider-spi/src/main/java/com/enterprise/iam/provider/spi/CapabilityStatus.java", r"^\s{4}([A-Z_]+),?\s*$")
    cap_block = openapi.split("    CapabilityStatus:")[1].split("    CapabilityCatalog:")[0]
    oa_statuses = re.findall(r"enum: \[(.*?)\]", cap_block)[0].replace(" ", "").split(",")
    if statuses != oa_statuses:
        errors.append(f"CapabilityStatus differs: java={statuses} openapi={oa_statuses}")

    for e in errors:
        print(f"CONTRACT DRIFT: {e}", file=sys.stderr)
    if not errors:
        print(f"contracts in sync: {len(error_codes)} error codes, {len(operations)} operations, {len(statuses)} capability statuses")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
