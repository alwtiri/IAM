#!/usr/bin/env bash
# DEVELOPMENT PKI for the internal mTLS listener (PHASE-3-DESIGN §6). Creates, once, into ./secrets (git-ignored):
#   pki_ca_crt / pki_ca_key          internal CA (EC P-256, 5 years; the key never leaves the host)
#   pki_core_crt / pki_core_key      iam-core server certificate (SAN DNS:iam-core, 2 years)
#   pki_worker_crt / pki_worker_key  worker client certificate (CN=iam-worker, clientAuth, 2 years)
# Production uses Vault PKI with short-lived certificates (Phase 10). Idempotent: existing files are kept.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets && chmod 700 secrets
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

if [[ ! -s secrets/pki_ca_crt ]]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/ca.key"
  openssl req -x509 -new -key "$tmp/ca.key" -sha256 -days 1825 -subj "/CN=IAM Development Internal CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" -out secrets/pki_ca_crt
  openssl pkcs8 -topk8 -nocrypt -in "$tmp/ca.key" -out secrets/pki_ca_key
  chmod 600 secrets/pki_ca_key
  echo "created internal CA"
fi

issue() { # issue <name> <subject CN> <extensions>
  local name="$1" cn="$2" ext="$3"
  [[ -s "secrets/pki_${name}_crt" ]] && { echo "kept    pki_${name}"; return; }
  openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/$name.key"
  openssl req -new -key "$tmp/$name.key" -subj "/CN=$cn" -out "$tmp/$name.csr"
  printf '%s\n' "$ext" > "$tmp/$name.ext"
  openssl x509 -req -in "$tmp/$name.csr" -CA secrets/pki_ca_crt -CAkey secrets/pki_ca_key -CAcreateserial -CAserial "$tmp/ca.srl" \
    -days 730 -sha256 -extfile "$tmp/$name.ext" -out "secrets/pki_${name}_crt"
  openssl pkcs8 -topk8 -nocrypt -in "$tmp/$name.key" -out "secrets/pki_${name}_key"
  # 0644: bind-mounted Compose secrets must be readable by the non-root container users; secrets/ itself is 0700.
  chmod 644 "secrets/pki_${name}_crt" "secrets/pki_${name}_key"
  echo "created pki_${name} (CN=$cn)"
}

issue core iam-core "subjectAltName=DNS:iam-core,DNS:localhost
extendedKeyUsage=serverAuth
keyUsage=critical,digitalSignature"
issue worker iam-worker "extendedKeyUsage=clientAuth
keyUsage=critical,digitalSignature"
chmod 644 secrets/pki_ca_crt
