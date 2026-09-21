#!/usr/bin/env python3
"""Container hardening policy gate (ADR-0009, THREAT-MODEL T19).

Usage: docker compose -f deploy/compose/compose.yaml config --format json | ci/check-compose-policy.py
Fails on: privileged containers, Docker socket mounts, host network/pid/ipc, added capabilities other than
NET_BIND_SERVICE, root users, missing cap_drop ALL or no-new-privileges. Services listed in READ_ONLY_EXCEPTIONS
may run with a writable root filesystem; each exception is documented in compose.yaml.
"""
import json
import sys

READ_ONLY_EXCEPTIONS = {"keycloak", "rabbitmq"}
ALLOWED_CAPS = {"NET_BIND_SERVICE"}


def main() -> int:
    config = json.load(sys.stdin)
    errors = []
    for name, svc in config.get("services", {}).items():
        if svc.get("privileged"):
            errors.append(f"{name}: privileged is forbidden")
        for key in ("network_mode", "pid", "ipc"):
            if svc.get(key) == "host":
                errors.append(f"{name}: {key}=host is forbidden")
        extra = set(svc.get("cap_add", []) or []) - ALLOWED_CAPS
        if extra:
            errors.append(f"{name}: cap_add {sorted(extra)} not allowed")
        if "ALL" not in (svc.get("cap_drop") or []):
            errors.append(f"{name}: must cap_drop ALL")
        if not any("no-new-privileges" in o for o in (svc.get("security_opt") or [])):
            errors.append(f"{name}: must set no-new-privileges")
        user = str(svc.get("user", ""))
        if not user or user.split(":")[0] in ("0", "root"):
            errors.append(f"{name}: must run as an explicit non-root user")
        if not svc.get("read_only") and name not in READ_ONLY_EXCEPTIONS:
            errors.append(f"{name}: read_only root filesystem required (or documented exception)")
        for vol in svc.get("volumes", []) or []:
            src = vol.get("source", "") if isinstance(vol, dict) else str(vol)
            if "docker.sock" in src:
                errors.append(f"{name}: Docker socket mount is forbidden")
    for e in errors:
        print(f"POLICY VIOLATION: {e}", file=sys.stderr)
    if not errors:
        print(f"compose policy OK for {len(config.get('services', {}))} services")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
