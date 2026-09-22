import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack,
  Switch, Tab, Tabs, TextField, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { DataTable, ErrorAlert } from './common';

export interface RequestStep {
  stepNo: number;
  approverType: string;
  approverRole: string | null;
  approverName: string | null;
  status: string;
  decidedByName: string | null;
  decidedAt: string | null;
  comment: string | null;
  note: string | null;
}

export interface AccessRequest {
  id: string;
  requesterName: string | null;
  beneficiaryName: string | null;
  roleCode: string;
  scopeType: string;
  justification: string | null;
  durationDays: number;
  status: string;
  statusReason: string | null;
  policyExplanation: string | null;
  matchedPolicies: string[];
  sodConflicts: { ruleCode: string; ruleName: string; heldRole: string; mode: string; severity: string }[];
  steps: RequestStep[];
  validUntil: string | null;
  createdAt: string;
  canDecide: boolean;
  canCancel: boolean;
}

interface RequestableRole {
  id: string;
  code: string;
  name: string;
  description: string | null;
  held: boolean;
  allowed: boolean;
  approvals: string[];
  requireJustification: boolean;
  maxDurationDays: number | null;
  explanation: string;
}

export const requestStatusColor = (s: string) => (s === 'ACTIVE' ? 'success' : s === 'PENDING_APPROVAL' || s === 'APPROVED' ? 'info'
  : s === 'REJECTED' || s === 'FAILED' ? 'error' : 'default');

function StepsSummary({ r }: { r: AccessRequest }) {
  const { t } = useLocale();
  if (r.steps.length === 0) return <Typography variant="caption" color="text.secondary">—</Typography>;
  return (
    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
      {r.steps.map((s) => (
        <Chip key={s.stepNo} size="small" variant={s.status === 'PENDING' ? 'filled' : 'outlined'}
          color={s.status === 'APPROVED' ? 'success' : s.status === 'REJECTED' ? 'error' : s.status === 'PENDING' ? 'warning' : 'default'}
          label={`${s.stepNo}. ${s.approverType === 'MANAGER' ? (s.approverName ?? t.manager) : s.approverRole}`}
          title={[s.decidedByName, s.comment, s.note].filter(Boolean).join(' — ')} />
      ))}
    </Stack>
  );
}

function RequestDetails({ r }: { r: AccessRequest }) {
  const { t } = useLocale();
  return (
    <Stack spacing={0.5}>
      {r.statusReason && <Typography variant="body2" color={r.status === 'REJECTED' || r.status === 'FAILED' ? 'error' : 'text.secondary'}>{r.statusReason}</Typography>}
      {r.policyExplanation && <Typography variant="caption" color="text.secondary">{t.policy}: {r.policyExplanation}</Typography>}
      {r.sodConflicts.map((c) => (
        <Typography key={c.ruleCode} variant="caption" color={c.mode === 'PREVENTIVE' ? 'error' : 'warning.main'}>
          SoD {c.ruleCode} ({c.mode}): {c.ruleName}
        </Typography>
      ))}
    </Stack>
  );
}

/** Self-service: request a time-bound role, follow its approvals, cancel while pending. */
export function AccessRequestsPage() {
  const { t } = useLocale();
  const [tab, setTab] = useState<'mine' | 'all'>('mine');
  const [rows, setRows] = useState<AccessRequest[]>();
  const [error, setError] = useState<unknown>();
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    try {
      setRows(await apiFetch<AccessRequest[]>(`/api/v1/access-requests?mine=${tab === 'mine'}`));
      setError(undefined);
    } catch (e) {
      setError(e);
      setRows([]);
    }
  }, [tab]);

  useEffect(() => {
    void load();
  }, [load]);

  const cancel = async (id: string) => {
    try {
      await apiFetch(`/api/v1/access-requests/${id}:cancel`, { method: 'POST' });
      await load();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h5" component="h2">{t.nav.accessRequests}</Typography>
        <Button variant="contained" onClick={() => setCreating(true)}>{t.newRequest}</Button>
      </Stack>
      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab value="mine" label={t.myRequests} />
        <Tab value="all" label={t.allRequests} />
      </Tabs>
      {error !== undefined && <ErrorAlert error={error} />}
      {!rows ? <CircularProgress aria-label={t.loading} /> : rows.length === 0 ? <Alert severity="info">{t.noRequests}</Alert> : (
        <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
          <DataTable<AccessRequest> title="" rows={rows} rowKey={(r) => r.id} columns={[
            { header: t.time, cell: (r) => new Date(r.createdAt).toLocaleString() },
            ...(tab === 'all' ? [{ header: t.requester, cell: (r: AccessRequest) => r.requesterName ?? '' }] : []),
            { header: t.role, cell: (r) => <strong>{r.roleCode}</strong> },
            { header: t.duration, cell: (r) => format(t.days, { n: r.durationDays }) },
            { header: t.status, cell: (r) => <Chip size="small" label={r.status} color={requestStatusColor(r.status)} /> },
            { header: t.approvalsCol, cell: (r) => <StepsSummary r={r} /> },
            { header: t.details, cell: (r) => <Box sx={{ maxWidth: 360 }}><RequestDetails r={r} />{r.validUntil && <Typography variant="caption">{t.validUntil}: {new Date(r.validUntil).toLocaleDateString()}</Typography>}</Box> },
            { header: t.action, cell: (r) => (r.canCancel ? <Button size="small" color="warning" onClick={() => void cancel(r.id)}>{t.cancelRequest}</Button> : null) },
          ]} />
        </CardContent></Card>
      )}
      {creating && <NewRequestDialog onClose={() => setCreating(false)} onCreated={() => { setCreating(false); setTab('mine'); void load(); }} />}
    </Stack>
  );
}

function NewRequestDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const { t } = useLocale();
  const [roles, setRoles] = useState<RequestableRole[]>([]);
  const [roleId, setRoleId] = useState('');
  const [scopeType, setScopeType] = useState('ORG_UNIT');
  const [days, setDays] = useState('30');
  const [justification, setJustification] = useState('');
  const [error, setError] = useState<unknown>();
  const [result, setResult] = useState<AccessRequest>();

  useEffect(() => {
    apiFetch<RequestableRole[]>('/api/v1/access-requests/requestable-roles').then((r) => {
      setRoles(r);
      const first = r.find((x) => x.allowed && !x.held);
      if (first) setRoleId(first.id);
    }, setError);
  }, []);

  const role = roles.find((r) => r.id === roleId);
  const submit = async () => {
    try {
      setResult(await apiFetch<AccessRequest>('/api/v1/access-requests', {
        method: 'POST', body: JSON.stringify({ roleId, scopeType, durationDays: Number(days), justification: justification || null }),
      }));
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Dialog open onClose={result ? onCreated : onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.newRequest}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          {result ? (
            <Alert severity={result.status === 'REJECTED' ? 'error' : 'success'}>
              <strong>{result.status}</strong> — {result.statusReason ?? (result.status === 'PENDING_APPROVAL' ? t.sentForApproval : t.granted)}
            </Alert>
          ) : (
            <>
              <TextField select label={t.role} value={roleId} onChange={(e) => setRoleId(e.target.value)}>
                {roles.map((r) => (
                  <MenuItem key={r.id} value={r.id} disabled={r.held || !r.allowed}>
                    {r.name} ({r.code}){r.held ? ` — ${t.alreadyHeld}` : !r.allowed ? ` — ${t.notAllowed}` : ''}
                  </MenuItem>
                ))}
              </TextField>
              {role && (
                <Alert severity="info">
                  {t.approvalsNeeded}: {role.approvals.length === 0 ? t.none : role.approvals.map((a) => (a === 'MANAGER' ? t.manager : a.replace('ROLE:', ''))).join(' → ')}
                  {role.maxDurationDays ? ` · ${t.maxDuration}: ${format(t.days, { n: role.maxDurationDays })}` : ''}
                </Alert>
              )}
              <TextField select label={t.scope} value={scopeType} onChange={(e) => setScopeType(e.target.value)}>
                <MenuItem value="ORG_UNIT">{t.myUnit}</MenuItem>
                <MenuItem value="GLOBAL">{t.scopeGlobal}</MenuItem>
              </TextField>
              <TextField label={t.duration} type="number" value={days} onChange={(e) => setDays(e.target.value)}
                slotProps={{ htmlInput: { min: 1, max: role?.maxDurationDays ?? 3650 } }} helperText={t.durationHelp} />
              <TextField label={t.justification} required={role?.requireJustification} multiline minRows={2} value={justification}
                onChange={(e) => setJustification(e.target.value)} />
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        {result ? <Button variant="contained" onClick={onCreated}>{t.close}</Button> : (
          <>
            <Button onClick={onClose}>{t.cancel}</Button>
            <Button variant="contained" disabled={!roleId || !(Number(days) > 0) || (!!role?.requireJustification && !justification.trim())}
              onClick={() => void submit()}>{t.submit}</Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}

/** Requests waiting for the signed-in approver. Approving needs recent MFA (step-up); rejecting needs a reason. */
export function ApprovalsPage() {
  const { t } = useLocale();
  const [rows, setRows] = useState<AccessRequest[]>();
  const [error, setError] = useState<unknown>();
  const [deciding, setDeciding] = useState<{ r: AccessRequest; approve: boolean }>();
  const [comment, setComment] = useState('');
  const [notice, setNotice] = useState<string>();

  const load = useCallback(async () => {
    try {
      setRows(await apiFetch<AccessRequest[]>('/api/v1/approvals'));
    } catch (e) {
      setError(e);
      setRows([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const decide = async () => {
    if (!deciding) return;
    try {
      const r = await apiFetch<AccessRequest>(`/api/v1/access-requests/${deciding.r.id}:${deciding.approve ? 'approve' : 'reject'}`, {
        method: 'POST', body: JSON.stringify({ comment: comment || null }),
      });
      setNotice(`${r.roleCode}: ${r.status}`);
      setError(undefined);
      setDeciding(undefined);
      setComment('');
      await load();
    } catch (e) {
      setError(e);
      setDeciding(undefined);
    }
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h2">{t.nav.approvals}</Typography>
      {error !== undefined && <ErrorAlert error={error} />}
      {notice && <Alert severity="success" onClose={() => setNotice(undefined)}>{notice}</Alert>}
      {!rows ? <CircularProgress aria-label={t.loading} /> : rows.length === 0 ? <Alert severity="info">{t.noApprovals}</Alert> : (
        <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
          <DataTable<AccessRequest> title="" rows={rows} rowKey={(r) => r.id} columns={[
            { header: t.time, cell: (r) => new Date(r.createdAt).toLocaleString() },
            { header: t.requester, cell: (r) => r.requesterName ?? '' },
            { header: t.role, cell: (r) => <strong>{r.roleCode}</strong> },
            { header: t.scope, cell: (r) => (r.scopeType === 'GLOBAL' ? t.scopeGlobal : t.scopeOrgUnit) },
            { header: t.duration, cell: (r) => format(t.days, { n: r.durationDays }) },
            { header: t.justification, cell: (r) => <Box sx={{ maxWidth: 280 }}>{r.justification}</Box> },
            { header: t.approvalsCol, cell: (r) => <StepsSummary r={r} /> },
            {
              header: t.action, cell: (r) => (
                <Stack direction="row" spacing={1}>
                  <Button size="small" variant="contained" color="success" onClick={() => setDeciding({ r, approve: true })}>{t.approve}</Button>
                  <Button size="small" variant="outlined" color="error" onClick={() => setDeciding({ r, approve: false })}>{t.reject}</Button>
                </Stack>
              ),
            },
          ]} />
        </CardContent></Card>
      )}
      <Dialog open={!!deciding} onClose={() => setDeciding(undefined)} fullWidth maxWidth="xs">
        <DialogTitle>{deciding?.approve ? t.approve : t.reject}: {deciding?.r.roleCode}</DialogTitle>
        <DialogContent>
          <TextField fullWidth sx={{ mt: 1 }} label={deciding?.approve ? t.comment : t.reason} required={!deciding?.approve} value={comment}
            onChange={(e) => setComment(e.target.value)} multiline minRows={2} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeciding(undefined)}>{t.cancel}</Button>
          <Button variant="contained" color={deciding?.approve ? 'success' : 'error'} disabled={!deciding?.approve && !comment.trim()}
            onClick={() => void decide()}>{deciding?.approve ? t.approve : t.reject}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

interface PolicyRow {
  id: string;
  code: string;
  name: string;
  description: string | null;
  enabled: boolean;
  effect: string;
  roleCodes: string[] | null;
  identityTypes: string[] | null;
  approvals: string[];
  requireJustification: boolean;
  maxDurationDays: number | null;
}

interface SodRow {
  id: string;
  code: string;
  name: string;
  leftRole: string;
  rightRole: string;
  mode: string;
  severity: string;
  enabled: boolean;
}

/** Access policies (deny-overrides) and separation-of-duties rules. */
export function PoliciesPage() {
  const { t } = useLocale();
  const [policies, setPolicies] = useState<PolicyRow[]>();
  const [rules, setRules] = useState<SodRow[]>([]);
  const [error, setError] = useState<unknown>();

  const load = useCallback(async () => {
    try {
      setPolicies(await apiFetch<PolicyRow[]>('/api/v1/policies'));
      setRules(await apiFetch<SodRow[]>('/api/v1/sod-rules'));
    } catch (e) {
      setError(e);
      setPolicies((p) => p ?? []);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const toggle = async (p: PolicyRow) => {
    try {
      await apiFetch(`/api/v1/policies/${p.id}:${p.enabled ? 'disable' : 'enable'}`, { method: 'POST' });
      await load();
    } catch (e) {
      setError(e);
    }
  };

  if (!policies) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h5" component="h2">{t.nav.accessPolicies}</Typography>
        <Typography color="text.secondary">{t.policiesIntro}</Typography>
      </Box>
      {error !== undefined && <ErrorAlert error={error} />}
      <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <DataTable<PolicyRow> title="" rows={policies} rowKey={(p) => p.id} columns={[
          { header: t.code, cell: (p) => <strong>{p.code}</strong> },
          { header: t.name, cell: (p) => <Box><Typography variant="body2">{p.name}</Typography><Typography variant="caption" color="text.secondary">{p.description}</Typography></Box> },
          { header: t.effect, cell: (p) => <Chip size="small" label={p.effect} color={p.effect === 'DENY' ? 'error' : 'success'} /> },
          { header: t.appliesTo, cell: (p) => [(p.roleCodes ?? [t.anyRole]).join(', '), p.identityTypes ? `(${p.identityTypes.join(', ')})` : ''].join(' ') },
          { header: t.approvalsCol, cell: (p) => p.approvals.map((a) => (a === 'MANAGER' ? t.manager : a.replace('ROLE:', ''))).join(' → ') || '—' },
          { header: t.maxDuration, cell: (p) => (p.maxDurationDays ? format(t.days, { n: p.maxDurationDays }) : '—') },
          { header: t.enabled, cell: (p) => <Switch checked={p.enabled} onChange={() => void toggle(p)} inputProps={{ 'aria-label': p.code }} /> },
        ]} />
      </CardContent></Card>
      <Typography variant="h6" component="h3">{t.sodRules}</Typography>
      <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <DataTable<SodRow> title="" rows={rules} rowKey={(r) => r.id} columns={[
          { header: t.code, cell: (r) => <strong>{r.code}</strong> },
          { header: t.name, cell: (r) => r.name },
          { header: t.conflictingRoles, cell: (r) => `${r.leftRole} ✕ ${r.rightRole}` },
          { header: t.mode, cell: (r) => <Chip size="small" variant="outlined" label={r.mode} color={r.mode === 'PREVENTIVE' ? 'error' : 'warning'} /> },
          { header: t.severity, cell: (r) => r.severity },
        ]} />
      </CardContent></Card>
    </Stack>
  );
}
