/** Types mirroring contracts/openapi/iam-core-v1.yaml (Phase 2 subset used by the UI). */
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
  limit: number;
}

export interface ScopeElement {
  type: string;
  value: string;
}

export interface EffectiveAccess {
  identityId: string;
  username: string;
  displayName: string;
  authenticationContext: string | null;
  grants: { roleCode: string; permissions: string[]; scope: ScopeElement[]; validUntil: string | null }[];
}

export interface Identity {
  id: string;
  personId: string;
  displayName: string;
  type: string;
  username: string;
  state: string;
  validUntil: string | null;
  platformUser: boolean;
}

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  builtIn: boolean;
  permissions: string[];
}

export interface AuditEvent {
  id: string;
  seq: number;
  occurredAt: string;
  actorType: string;
  action: string;
  objectType: string | null;
  objectId: string | null;
  result: string;
  correlationId: string | null;
}

export interface Target {
  id: string;
  name: string;
  hostname: string | null;
  type: string;
  environment: string;
  criticality: string;
  status: string;
}

export interface ProviderInstance {
  id: string;
  type: string;
  name: string;
  endpoint: string;
  credentialConfigured: boolean;
  enabled: boolean;
  health: string;
}

export interface ComponentHealth {
  component: string;
  category: string;
  classification: string;
  status: 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE' | 'UNKNOWN';
  reason: string | null;
  affectedFunctionality: string[];
}

export interface SystemHealth {
  status: ComponentHealth['status'];
  components: ComponentHealth[];
}

export function hasPermission(access: EffectiveAccess | undefined, permission: string): boolean {
  return !!access?.grants.some((g) => g.permissions.includes(permission));
}
