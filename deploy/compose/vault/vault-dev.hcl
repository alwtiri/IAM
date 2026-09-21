# Vault — DEVELOPMENT configuration (ADR-0005). Not for production:
#  - TLS is disabled on the internal dev network only; compose.prod.yaml enables TLS with certificates.
#  - Single-node integrated storage (Raft); production uses a 3/5-node cluster with auto-unseal (Phase 10).
ui            = false
disable_mlock = true   # recommended with integrated storage; avoids needing the IPC_LOCK capability

storage "raft" {
  path    = "/vault/file"
  node_id = "vault-dev-1"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

api_addr     = "http://vault:8200"
cluster_addr = "http://vault:8201"
