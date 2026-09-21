import { useEffect, useState } from 'react';
import { Alert, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import type { AuditEvent, Identity, ProviderInstance, Role, SystemHealth, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import type { NavItem } from '../navigation';
import { DataTable, ErrorAlert, PagedView } from './common';

const statusColor = (s: string) => (s === 'HEALTHY' || s === 'ACTIVE' || s === 'SUCCESS' ? 'success'
  : s === 'DEGRADED' || s === 'SUSPENDED' || s === 'PENDING' ? 'warning'
  : s === 'UNKNOWN' ? 'default' : 'error');

export function HealthPage() {
  const { t } = useLocale();
  const [health, setHealth] = useState<SystemHealth>();
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    apiFetch<SystemHealth>('/api/v1/system/health').then(setHealth, setError);
  }, []);
  if (error) return <ErrorAlert error={error} />;
  if (!health) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h2">{t.nav.systemHealth}</Typography>
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography>{t.overallStatus}:</Typography>
        <Chip label={health.status} color={statusColor(health.status)} />
      </Stack>
      <DataTable title={t.component} rows={health.components} rowKey={(c) => c.component} columns={[
        { header: t.component, cell: (c) => c.component },
        { header: t.status, cell: (c) => <Chip size="small" label={c.status} color={statusColor(c.status)} /> },
        { header: t.affected, cell: (c) => [c.reason, ...c.affectedFunctionality].filter(Boolean).join(' — ') },
      ]} />
    </Stack>
  );
}

export function UsersPage() {
  const { t } = useLocale();
  return (
    <PagedView<Identity> path="/api/v1/identities" title={t.nav.users} rowKey={(i) => i.id} columns={[
      { header: t.name, cell: (i) => i.displayName },
      { header: t.username, cell: (i) => i.username },
      { header: t.type, cell: (i) => i.type },
      { header: t.state, cell: (i) => <Chip size="small" label={i.state} color={statusColor(i.state)} /> },
    ]} />
  );
}

export function RolesPage() {
  const { t } = useLocale();
  const [roles, setRoles] = useState<Role[]>();
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    apiFetch<Role[]>('/api/v1/roles').then(setRoles, setError);
  }, []);
  if (error) return <ErrorAlert error={error} />;
  if (!roles) return <CircularProgress aria-label={t.loading} />;
  return (
    <DataTable title={t.nav.roles} rows={roles} rowKey={(r) => r.id} columns={[
      { header: t.code, cell: (r) => r.code },
      { header: t.name, cell: (r) => r.name },
      { header: t.permissions, cell: (r) => r.permissions.join(', ') },
    ]} />
  );
}

export function AuditPage() {
  const { t } = useLocale();
  return (
    <PagedView<AuditEvent> path="/api/v1/audit-events?limit=100" title={t.nav.auditLogs} rowKey={(e) => e.id} columns={[
      { header: t.time, cell: (e) => new Date(e.occurredAt).toLocaleString() },
      { header: t.action, cell: (e) => e.action },
      { header: t.object, cell: (e) => [e.objectType, e.objectId].filter(Boolean).join(' / ') },
      { header: t.result, cell: (e) => <Chip size="small" label={e.result} color={statusColor(e.result)} /> },
    ]} />
  );
}

export function TargetsPage() {
  const { t } = useLocale();
  return (
    <PagedView<Target> path="/api/v1/targets" title={t.nav.allTargets} rowKey={(x) => x.id} columns={[
      { header: t.name, cell: (x) => x.name },
      { header: t.hostname, cell: (x) => x.hostname ?? '' },
      { header: t.type, cell: (x) => x.type },
      { header: t.environment, cell: (x) => x.environment },
      { header: t.state, cell: (x) => x.status },
    ]} />
  );
}

export function ProvidersPage() {
  const { t } = useLocale();
  return (
    <PagedView<ProviderInstance> path="/api/v1/provider-instances" title={t.nav.providerManagement} rowKey={(p) => p.id} columns={[
      { header: t.name, cell: (p) => p.name },
      { header: t.type, cell: (p) => p.type },
      { header: t.endpoint, cell: (p) => p.endpoint },
      { header: t.credential, cell: (p) => (p.credentialConfigured ? t.inVault : t.none) },
      { header: t.enabled, cell: (p) => String(p.enabled) },
    ]} />
  );
}

/** Honest placeholder for modules of later phases. */
export function PlannedPage({ item }: { item: NavItem }) {
  const { t } = useLocale();
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h2">{t.nav[item.id as keyof typeof t.nav] ?? item.id}</Typography>
      <Alert severity="info" title={t.plannedTitle}>{format(t.plannedBody, { phase: item.phase })}</Alert>
    </Stack>
  );
}
