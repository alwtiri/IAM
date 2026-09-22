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
  ipAddress?: string | null;
  dnsName?: string | null;
  platform?: string | null;
  operatingSystem?: string | null;
  classification?: string | null;
  ownerOrgUnitId?: string | null;
  ownerIdentityId?: string | null;
  technicalOwnerIdentityId?: string | null;
  businessOwnerIdentityId?: string | null;
  locationId?: string | null;
  tags?: string[];
  version?: number;
}

export interface ProviderInstance {
  id: string;
  type: string;
  name: string;
  endpoint: string;
  credentialConfigured: boolean;
  enabled: boolean;
  health: string;
  settings?: Record<string, string>;
  version?: number;
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

export interface OrgUnit {
  id: string;
  parentId: string | null;
  kind: string;
  code: string;
  name: string;
  path: string;
}

export interface ProviderBinding {
  targetId: string;
  providerInstanceId: string;
  providerType: string;
  providerName: string;
  channel: string | null;
}

export interface Submitted {
  operationId: string;
  discoveryRunId: string | null;
}

export type OperationStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'CANCELLED' | 'PARTIAL' | 'UNKNOWN';

export interface Operation {
  id: string;
  type: string;
  status: OperationStatus;
  statusReason: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  verificationSummary: string | null;
  finishedAt: string | null;
  createdAt?: string;
  targetId?: string | null;
}

export interface DiscoveryRun {
  id: string;
  providerInstanceId: string;
  operationId: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  accountsSeen: number;
  accountsNew: number;
  accountsRemoved: number;
  errorMessage: string | null;
}

export interface Account {
  id: string;
  targetId: string;
  targetName: string;
  providerInstanceId: string;
  providerType: string;
  nativeId: string | null;
  name: string;
  displayName: string | null;
  type: string;
  privileged: boolean;
  privilegeReason: string | null;
  governanceState: string;
  nativeStatus: string;
  attributes: Record<string, string>;
  lastSeenAt: string | null;
  lastLoginAt: string | null;
  openFindings: string[];
  version: number;
}

export interface AccountFinding {
  id: string;
  accountId: string;
  accountName: string;
  targetId: string;
  targetName: string;
  type: string;
  severity: string;
  detectedAt: string;
}

export function hasPermission(access: EffectiveAccess | undefined, permission: string): boolean {
  return !!access?.grants.some((g) => g.permissions.includes(permission));
}
