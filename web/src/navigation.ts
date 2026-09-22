/**
 * Baseline navigation (spec §61) plus the additional modules. `phase` is the phase that delivers the page;
 * entries of later phases render an explicit "planned" page — no placeholder data is ever shown as real.
 */
export interface NavItem {
  id: string;
  path: string;
  phase: number;
  permission?: string;
  children?: NavItem[];
}

const leaf = (id: string, path: string, phase: number, permission?: string): NavItem => ({ id, path, phase, permission });

export const NAVIGATION: NavItem[] = [
  leaf('dashboard', '/', 2, 'system:health:read'),
  {
    id: 'identityAccess', path: '/identity', phase: 2, children: [
      leaf('users', '/identity/users', 2, 'identity:read'),
      leaf('groups', '/identity/groups', 3),
      leaf('roles', '/identity/roles', 2, 'role:read'),
      leaf('accessPolicies', '/identity/policies', 4),
      leaf('accessRequests', '/identity/requests', 4),
      leaf('approvals', '/identity/approvals', 4),
      leaf('entitlements', '/identity/entitlements', 3),
      leaf('directory', '/identity/directory', 3),
      leaf('serviceAccounts', '/identity/service-accounts', 3),
    ],
  },
  {
    id: 'assets', path: '/assets', phase: 2, children: [
      leaf('allTargets', '/assets/targets', 2, 'target:read'),
      leaf('servers', '/assets/servers', 3),
      leaf('virtualization', '/assets/virtualization', 5),
      leaf('storage', '/assets/storage', 5),
      leaf('networkDevices', '/assets/network', 5),
      leaf('applications', '/assets/applications', 3),
      leaf('databases', '/assets/databases', 5),
    ],
  },
  {
    id: 'accounts', path: '/accounts', phase: 3, children: [
      leaf('allAccounts', '/accounts/all', 3),
      leaf('linuxAccounts', '/accounts/linux', 3),
      leaf('windowsAccounts', '/accounts/windows', 3),
      leaf('serviceAccountsList', '/accounts/service', 3),
      leaf('privilegedAccounts', '/accounts/privileged', 3),
      leaf('emergencyAccounts', '/accounts/emergency', 4),
    ],
  },
  {
    id: 'privilegedAccess', path: '/pam', phase: 6, children: [
      leaf('pamRequests', '/pam/requests', 4),
      leaf('pamApprovals', '/pam/approvals', 4),
      leaf('privilegedSessions', '/pam/sessions', 6),
      leaf('sshAccess', '/pam/ssh', 6),
      leaf('rdpAccess', '/pam/rdp', 6),
      leaf('emergencyAccess', '/pam/emergency', 6),
    ],
  },
  {
    id: 'sessions', path: '/sessions', phase: 6, children: [
      leaf('activeSessions', '/sessions/active', 6),
      leaf('sessionHistory', '/sessions/history', 6),
      leaf('sessionRecording', '/sessions/recordings', 6),
    ],
  },
  {
    id: 'emergency', path: '/emergency', phase: 4, children: [
      leaf('emergencyAccountsList', '/emergency/accounts', 4),
      leaf('emergencyRequests', '/emergency/requests', 4),
      leaf('emergencyApprovals', '/emergency/approvals', 4),
      leaf('activeEmergencies', '/emergency/active', 6),
      leaf('emergencyAudit', '/emergency/audit', 4),
    ],
  },
  {
    id: 'auditCompliance', path: '/audit', phase: 2, children: [
      leaf('auditLogs', '/audit/logs', 2, 'audit:read'),
      leaf('accessLogs', '/audit/access', 7),
      leaf('privilegedActivity', '/audit/privileged', 6),
      leaf('configurationChanges', '/audit/configuration', 7),
    ],
  },
  leaf('reports', '/reports', 7),
  {
    id: 'administration', path: '/admin', phase: 2, children: [
      leaf('rolesPermissions', '/admin/roles', 2, 'role:read'),
      leaf('policies', '/admin/policies', 4),
      leaf('securitySettings', '/admin/security', 9),
      leaf('authentication', '/admin/authentication', 9),
      leaf('notifications', '/admin/notifications', 8),
      leaf('integrations', '/admin/integrations', 8),
      leaf('providerManagement', '/admin/providers', 2, 'provider:read'),
      leaf('systemHealth', '/admin/health', 2, 'system:health:read'),
      leaf('systemSettings', '/admin/settings', 7),
    ],
  },
];

export const CURRENT_PHASE = 3;

export function flatten(items: NavItem[] = NAVIGATION): NavItem[] {
  return items.flatMap((i) => [i, ...(i.children ? flatten(i.children) : [])]);
}

export function isAvailable(item: NavItem): boolean {
  return item.phase <= CURRENT_PHASE;
}
