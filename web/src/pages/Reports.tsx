import { useState } from 'react';
import { Alert, Box, Button, Card, CardContent, CircularProgress, Stack, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import type { Page } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { ErrorAlert } from './common';

type Row = Record<string, unknown>;

/** RFC 4180 CSV with formula-injection protection (cells starting with = + - @ are prefixed with '). */
export function toCsv(rows: Row[], columns: { key: string; header: string; value?: (r: Row) => unknown }[]): string {
  const cell = (v: unknown) => {
    let s = v === null || v === undefined ? '' : Array.isArray(v) ? v.join('; ') : typeof v === 'object' ? JSON.stringify(v) : String(v);
    if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [columns.map((c) => cell(c.header)).join(',')];
  for (const r of rows) lines.push(columns.map((c) => cell(c.value ? c.value(r) : r[c.key])).join(','));
  return '﻿' + lines.join('\r\n');
}

async function fetchAll(path: string, max = 5000): Promise<Row[]> {
  const out: Row[] = [];
  let cursor: string | null = null;
  do {
    const sep = path.includes('?') ? '&' : '?';
    const page: Page<Row> | Row[] = await apiFetch<Page<Row> | Row[]>(cursor ? `${path}${sep}cursor=${encodeURIComponent(cursor)}` : path);
    if (Array.isArray(page)) return page;
    out.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor && out.length < max);
  return out;
}

function download(name: string, csv: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

interface ReportDef {
  id: string;
  title: string;
  description: string;
  path: string;
  columns: { key: string; header: string; value?: (r: Row) => unknown }[];
}

/** Compliance exports generated from live data with the viewer's own permissions. */
export function ReportsPage() {
  const { t } = useLocale();
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState<string>();
  const stamp = () => new Date().toISOString().slice(0, 10);

  const reports: ReportDef[] = [
    { id: 'accounts', title: t.rptAccounts, description: t.rptAccountsDesc, path: '/api/v1/accounts?limit=200', columns: [
      { key: 'targetName', header: 'Server' }, { key: 'name', header: 'Account' }, { key: 'providerType', header: 'Provider' },
      { key: 'nativeStatus', header: 'Status' }, { key: 'privileged', header: 'Privileged' }, { key: 'privilegeReason', header: 'Privilege reason' },
      { key: 'governanceState', header: 'Governance' }, { key: 'lastLoginAt', header: 'Last login' }, { key: 'openFindings', header: 'Open findings' }] },
    { id: 'privileged', title: t.rptPrivileged, description: t.rptPrivilegedDesc, path: '/api/v1/accounts?privileged=true&limit=200', columns: [
      { key: 'targetName', header: 'Server' }, { key: 'name', header: 'Account' }, { key: 'privilegeReason', header: 'Why privileged' },
      { key: 'nativeStatus', header: 'Status' }, { key: 'ownerIdentityId', header: 'Owner' }, { key: 'lastLoginAt', header: 'Last login' }] },
    { id: 'findings', title: t.rptFindings, description: t.rptFindingsDesc, path: '/api/v1/account-findings?limit=200', columns: [
      { key: 'targetName', header: 'Server' }, { key: 'accountName', header: 'Account' }, { key: 'type', header: 'Finding' },
      { key: 'severity', header: 'Severity' }, { key: 'detectedAt', header: 'Detected' }] },
    { id: 'requests', title: t.rptRequests, description: t.rptRequestsDesc, path: '/api/v1/access-requests?mine=false', columns: [
      { key: 'createdAt', header: 'Submitted' }, { key: 'requesterName', header: 'Requester' }, { key: 'roleCode', header: 'Role' },
      { key: 'durationDays', header: 'Days' }, { key: 'status', header: 'Status' }, { key: 'statusReason', header: 'Reason' },
      { key: 'steps', header: 'Approvals', value: (r) => (r.steps as { stepNo: number; status: string; decidedByName: string | null }[])
        .map((s) => `${s.stepNo}:${s.status}${s.decidedByName ? ` by ${s.decidedByName}` : ''}`) }, { key: 'validUntil', header: 'Valid until' }] },
    { id: 'users', title: t.rptUsers, description: t.rptUsersDesc, path: '/api/v1/identities?limit=200', columns: [
      { key: 'displayName', header: 'Name' }, { key: 'username', header: 'Username' }, { key: 'type', header: 'Type' },
      { key: 'state', header: 'State' }, { key: 'platformUser', header: 'Has login' }, { key: 'validUntil', header: 'Valid until' }] },
    { id: 'audit', title: t.rptAudit, description: t.rptAuditDesc, path: '/api/v1/audit-events?limit=200', columns: [
      { key: 'occurredAt', header: 'Time' }, { key: 'action', header: 'Action' }, { key: 'actorType', header: 'Actor type' },
      { key: 'objectType', header: 'Object type' }, { key: 'objectId', header: 'Object' }, { key: 'result', header: 'Result' },
      { key: 'correlationId', header: 'Correlation' }] },
  ];

  const run = async (r: ReportDef) => {
    setBusy(r.id);
    setError(undefined);
    setDone(undefined);
    try {
      const rows = await fetchAll(r.path);
      download(`${r.id}-${stamp()}.csv`, toCsv(rows, r.columns));
      setDone(`${r.title}: ${rows.length}`);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(undefined);
    }
  };

  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5" component="h2">{t.nav.reports}</Typography>
        <Typography color="text.secondary">{t.reportsIntro}</Typography>
      </Box>
      {error !== undefined && <ErrorAlert error={error} />}
      {done && <Alert severity="success">{done}</Alert>}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))' }}>
        {reports.map((r) => (
          <Card key={r.id}>
            <CardContent>
              <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{r.title}</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ minHeight: 44, mb: 1.5 }}>{r.description}</Typography>
              <Button variant="outlined" disabled={!!busy} onClick={() => void run(r)}
                startIcon={busy === r.id ? <CircularProgress size={16} /> : undefined}>{t.downloadCsv}</Button>
            </CardContent>
          </Card>
        ))}
      </Box>
    </Stack>
  );
}
