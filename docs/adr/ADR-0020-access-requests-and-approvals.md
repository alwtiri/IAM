# ADR-0020: Access requests, approvals, policy decisions and SoD (Phase 4)

| Field | Value |
|---|---|
| Status | Accepted (Phase 4) |
| Related | ADR-0013 (policy model), DOMAIN-MODEL §5 and §8 |

## Decision

- **Policy** (`policy` module): policies are rows (`policy.policy`) with a target (request type, role codes, identity types), an effect (ALLOW/DENY) and obligations (ordered approval steps `MANAGER` / `ROLE:<code>`, justification, maximum duration). The evaluator is a pure function: deny-overrides, no matching ALLOW means DENY, obligations of all matching ALLOW policies are combined, the shortest maximum duration wins. Errors deny (fail closed). Administrators can enable or disable policies (step-up); authoring new policies is a later increment.
- **SoD** (`sod` module): symmetric role pairs with PREVENTIVE (blocks) or DETECTIVE (reported) mode.
- **Requests and approvals** (`request` module): the approval case lives with the request (`request.approval_step`) instead of the separate `approval` module, to avoid a request↔approval dependency cycle while there is one workflow type. The `approval` module remains reserved for reusable workflow definitions (reviews, emergency access).
- Invariants: requester and beneficiary never approve; one person decides at most one step; approving needs recent MFA and records `acr`; one open request per beneficiary and role; policy and SoD are evaluated at submission and again before the grant.
- **Fulfilment**: an approved role request becomes a time-bound role assignment (source REQUEST, granted by the system identity). The existing expiry job ends it; the request follows (EXPIRED/REVOKED).
- Missing approvers never block silently: no manager → IAM administrators; no other holder of the required role → platform administrators; the routing is recorded on the step.

## Consequences

- Phase 4 covers role requests for oneself. Requests on behalf of others, account-level requests (e.g. enabling a server account for a period), access reviews and emergency access use the same model in later increments.
