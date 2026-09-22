import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, MenuItem, Stack, TextField, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import type { Identity, OrgUnit, Page, Role } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { DataTable, ErrorAlert } from './common';

const IDENTITY_TYPES = ['EMPLOYEE', 'CONTRACTOR', 'CONSULTANT', 'SERVICE', 'TEMPORARY', 'EXTERNAL', 'EMERGENCY'];

interface RoleAssignment {
  id: string;
  identityId: string;
  roleId: string;
  roleCode: string;
  scope: { type: string; value: string }[];
  status: string;
  grantedAt: string;
  reason: string | null;
}

export const identityStateColor = (s: string) => (s === 'ACTIVE' ? 'success' : s === 'PENDING' ? 'info' : s === 'SUSPENDED' ? 'warning' : 'error');

/** User administration: create people and their identities, lifecycle (activate/suspend/disable), login link and roles. */
export function UsersPage() {
  const { t } = useLocale();
  const [users, setUsers] = useState<Identity[]>();
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<unknown>();
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<Identity>();
  const [search, setSearch] = useState('');

  const load = useCallback(async (next?: string | null) => {
    try {
      const p = await apiFetch<Page<Identity>>(`/api/v1/identities?limit=100${next ? `&cursor=${encodeURIComponent(next)}` : ''}`);
      setUsers((prev) => (next ? [...(prev ?? []), ...p.items] : p.items));
      setCursor(p.nextCursor);
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (selected) {
    return <UserDetail identity={selected} onBack={() => { setSelected(undefined); void load(); }} />;
  }
  if (error) return <ErrorAlert error={error} />;
  if (!users) return <CircularProgress aria-label={t.loading} />;
  const q = search.trim().toLowerCase();
  const rows = q ? users.filter((u) => u.username.toLowerCase().includes(q) || u.displayName.toLowerCase().includes(q)) : users;
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
        <Typography variant="h5" component="h2">{t.nav.users}</Typography>
        <Stack direction="row" spacing={1}>
          <TextField size="small" placeholder={t.search} value={search} onChange={(e) => setSearch(e.target.value)} />
          <Button variant="contained" onClick={() => setAdding(true)}>{t.addUser}</Button>
        </Stack>
      </Stack>
      {rows.length === 0 ? <Alert severity="info">{t.noUsers}</Alert> : (
        <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
          <DataTable<Identity> title="" rows={rows} rowKey={(u) => u.id} columns={[
            { header: t.name, cell: (u) => <Button size="small" onClick={() => setSelected(u)}>{u.displayName}</Button> },
            { header: t.username, cell: (u) => u.username },
            { header: t.type, cell: (u) => u.type },
            { header: t.state, cell: (u) => <Chip size="small" label={u.state} color={identityStateColor(u.state)} /> },
            { header: t.loginAccount, cell: (u) => (u.platformUser ? <Chip size="small" variant="outlined" color="success" label={t.linked} /> : <Typography variant="caption" color="text.secondary">{t.notLinked}</Typography>) },
          ]} footer={cursor ? <Button onClick={() => void load(cursor)}>{t.loadMore}</Button> : null} />
        </CardContent></Card>
      )}
      {adding && <AddUserDialog onClose={() => setAdding(false)} onCreated={(u) => { setAdding(false); void load(); setSelected(u); }} />}
    </Stack>
  );
}

function AddUserDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (u: Identity) => void }) {
  const { t } = useLocale();
  const [units, setUnits] = useState<OrgUnit[]>([]);
  const [form, setForm] = useState({ givenName: '', familyName: '', email: '', orgUnitId: '', type: 'EMPLOYEE', username: '', activate: true });
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<Page<OrgUnit>>('/api/v1/org-units?limit=100').then((p) => {
      setUnits(p.items);
      setForm((f) => ({ ...f, orgUnitId: f.orgUnitId || (p.items[0]?.id ?? '') }));
    }, setError);
  }, []);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
    setForm((f) => {
      const next = { ...f, [k]: value };
      // Suggest a username (given.family) until the admin edits it.
      if ((k === 'givenName' || k === 'familyName') && (!f.username || f.username === suggest(f))) {
        next.username = suggest(next);
      }
      return next;
    });
  };

  const save = async () => {
    setSaving(true);
    try {
      const person = await apiFetch<{ id: string }>('/api/v1/persons', {
        method: 'POST',
        body: JSON.stringify({ givenName: form.givenName, familyName: form.familyName, email: form.email || null, orgUnitId: form.orgUnitId, employmentStatus: 'ACTIVE' }),
      });
      let identity = await apiFetch<Identity>('/api/v1/identities', {
        method: 'POST', body: JSON.stringify({ personId: person.id, type: form.type, username: form.username }),
      });
      if (form.activate) {
        identity = await apiFetch<Identity>(`/api/v1/identities/${identity.id}:activate`, { method: 'POST', body: JSON.stringify({ reason: 'created by administrator' }) });
      }
      onCreated(identity);
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  const valid = form.givenName && form.familyName && form.orgUnitId && /^[a-z0-9][a-z0-9._-]{1,63}$/.test(form.username);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addUser}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Stack direction="row" spacing={2}>
            <TextField required fullWidth label={t.givenName} value={form.givenName} onChange={set('givenName')} />
            <TextField required fullWidth label={t.familyName} value={form.familyName} onChange={set('familyName')} />
          </Stack>
          <TextField label={t.email} type="email" value={form.email} onChange={set('email')} helperText={t.emailForLogin} />
          <TextField select required label={t.orgUnit} value={form.orgUnitId} onChange={set('orgUnitId')}>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.name}</MenuItem>)}
          </TextField>
          <TextField select label={t.identityType} value={form.type} onChange={set('type')}>
            {IDENTITY_TYPES.map((x) => <MenuItem key={x} value={x}>{x}</MenuItem>)}
          </TextField>
          <TextField required label={t.username} value={form.username} onChange={set('username')} helperText="a-z 0-9 . _ -" />
          <FormControlLabel control={<Checkbox checked={form.activate} onChange={set('activate')} />} label={t.activateNow} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !valid} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}

function suggest(f: { givenName: string; familyName: string }): string {
  return [f.givenName, f.familyName].map((s) => s.trim().toLowerCase().replace(/[^a-z0-9]/g, '')).filter(Boolean).join('.');
}

function UserDetail({ identity: initial, onBack }: { identity: Identity; onBack: () => void }) {
  const { t } = useLocale();
  const [identity, setIdentity] = useState(initial);
  const [grants, setGrants] = useState<RoleAssignment[]>();
  const [roles, setRoles] = useState<Role[]>([]);
  const [units, setUnits] = useState<OrgUnit[]>([]);
  const [error, setError] = useState<unknown>();
  const [prompt, setPrompt] = useState<{ title: string; run: (reason: string) => Promise<void> }>();
  const [granting, setGranting] = useState(false);
  const [linking, setLinking] = useState(false);
  const [notice, setNotice] = useState<{ severity: 'success' | 'warning'; text: string }>();
  const [loginBusy, setLoginBusy] = useState(false);

  const createLogin = async () => {
    setLoginBusy(true);
    setNotice(undefined);
    try {
      const r = await apiFetch<{ subject: string; invitationSent: boolean }>(`/api/v1/identities/${identity.id}:create-login`, { method: 'POST' });
      setNotice(r.invitationSent ? { severity: 'success', text: t.loginCreated } : { severity: 'warning', text: t.loginCreatedNoMail });
      setError(undefined);
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setLoginBusy(false);
    }
  };

  const resend = async () => {
    setLoginBusy(true);
    try {
      await apiFetch(`/api/v1/identities/${identity.id}:resend-invitation`, { method: 'POST' });
      setNotice({ severity: 'success', text: t.invitationSent });
      setError(undefined);
    } catch (e) {
      setError(e);
    } finally {
      setLoginBusy(false);
    }
  };

  const load = useCallback(async () => {
    try {
      const [i, g] = await Promise.all([
        apiFetch<Identity>(`/api/v1/identities/${initial.id}`),
        apiFetch<Page<RoleAssignment>>(`/api/v1/role-assignments?identityId=${initial.id}&activeOnly=true&limit=100`),
      ]);
      setIdentity(i);
      setGrants(g.items);
    } catch (e) {
      setError(e);
    }
  }, [initial.id]);

  useEffect(() => {
    void load();
    apiFetch<Role[]>('/api/v1/roles').then(setRoles, () => undefined);
    apiFetch<Page<OrgUnit>>('/api/v1/org-units?limit=100').then((p) => setUnits(p.items), () => undefined);
  }, [load]);

  const transition = (action: 'activate' | 'suspend' | 'reinstate' | 'disable', label: string) => setPrompt({
    title: label,
    run: async (reason) => {
      setIdentity(await apiFetch<Identity>(`/api/v1/identities/${identity.id}:${action}`, { method: 'POST', body: JSON.stringify({ reason: reason || label }) }));
    },
  });

  const s = identity.state;
  return (
    <Stack spacing={3}>
      <Box>
        <Button onClick={onBack}>← {t.backToUsers}</Button>
        <Stack direction="row" spacing={2} alignItems="center">
          <Typography variant="h5" component="h2">{identity.displayName}</Typography>
          <Chip label={s} color={identityStateColor(s)} />
        </Stack>
        <Typography color="text.secondary">{identity.username} · {identity.type}</Typography>
      </Box>
      {error !== undefined && <ErrorAlert error={error} />}
      {notice && <Alert severity={notice.severity}>{notice.text}</Alert>}

      <Card><CardContent>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {s === 'PENDING' && <Button variant="contained" onClick={() => transition('activate', t.activate)}>{t.activate}</Button>}
          {s === 'ACTIVE' && <Button variant="outlined" color="warning" onClick={() => transition('suspend', t.suspend)}>{t.suspend}</Button>}
          {s === 'SUSPENDED' && <Button variant="contained" onClick={() => transition('reinstate', t.reinstate)}>{t.reinstate}</Button>}
          {(s === 'ACTIVE' || s === 'SUSPENDED' || s === 'PENDING') && <Button variant="outlined" color="error" onClick={() => transition('disable', t.disableIdentity)}>{t.disableIdentity}</Button>}
          <Box sx={{ flexGrow: 1 }} />
          <Chip variant="outlined" color={identity.platformUser ? 'success' : 'default'} label={`${t.loginAccount}: ${identity.platformUser ? t.linked : t.notLinked}`} />
          {!identity.platformUser && s === 'ACTIVE' && (
            <Button variant="contained" color="secondary" disabled={loginBusy} onClick={() => void createLogin()}>{t.createLogin}</Button>
          )}
          {!identity.platformUser && <Button size="small" onClick={() => setLinking(true)}>{t.linkLogin}</Button>}
          {identity.platformUser && <Button size="small" disabled={loginBusy} onClick={() => void resend()}>{t.resendInvitation}</Button>}
        </Stack>
      </CardContent></Card>

      <Card><CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6" component="h3">{t.roleAssignments}</Typography>
          <Button variant="contained" onClick={() => setGranting(true)} disabled={s !== 'ACTIVE'}>{t.grantRole}</Button>
        </Stack>
        {!grants ? <CircularProgress aria-label={t.loading} /> : grants.length === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
          <DataTable<RoleAssignment> title="" rows={grants} rowKey={(g) => g.id} columns={[
            { header: t.role, cell: (g) => g.roleCode },
            { header: t.scope, cell: (g) => g.scope.map((x) => (x.type === 'GLOBAL' ? t.scopeGlobal : `${x.type}: ${units.find((u) => u.id === x.value)?.name ?? x.value}`)).join(', ') },
            { header: t.time, cell: (g) => new Date(g.grantedAt).toLocaleString() },
            { header: t.action, cell: (g) => <Button size="small" color="error" onClick={() => setPrompt({ title: `${t.revoke} ${g.roleCode}`, run: async (reason) => { await apiFetch(`/api/v1/role-assignments/${g.id}:revoke`, { method: 'POST', body: JSON.stringify({ reason: reason || 'revoked by administrator' }) }); await load(); } })}>{t.revoke}</Button> },
          ]} />
        )}
      </CardContent></Card>

      {prompt && <ReasonDialog title={prompt.title} onClose={() => setPrompt(undefined)} onConfirm={async (reason) => {
        try {
          await prompt.run(reason);
          setError(undefined);
        } catch (e) {
          setError(e);
        }
        setPrompt(undefined);
      }} />}
      {granting && <GrantDialog identity={identity} roles={roles} units={units} onClose={() => setGranting(false)} onGranted={() => { setGranting(false); void load(); }} />}
      {linking && <LinkDialog identity={identity} onClose={() => setLinking(false)} onLinked={() => { setLinking(false); void load(); }} />}
    </Stack>
  );
}

function ReasonDialog({ title, onClose, onConfirm }: { title: string; onClose: () => void; onConfirm: (reason: string) => Promise<void> }) {
  const { t } = useLocale();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent><TextField fullWidth sx={{ mt: 1 }} label={t.reason} value={reason} onChange={(e) => setReason(e.target.value)} /></DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={busy} onClick={() => { setBusy(true); void onConfirm(reason); }}>{title}</Button>
      </DialogActions>
    </Dialog>
  );
}

function GrantDialog({ identity, roles, units, onClose, onGranted }: { identity: Identity; roles: Role[]; units: OrgUnit[]; onClose: () => void; onGranted: () => void }) {
  const { t } = useLocale();
  const [roleId, setRoleId] = useState(roles[0]?.id ?? '');
  const [scopeType, setScopeType] = useState<'GLOBAL' | 'ORG_UNIT'>('ORG_UNIT');
  const [unit, setUnit] = useState(units[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>();
  const save = async () => {
    try {
      await apiFetch('/api/v1/role-assignments', {
        method: 'POST',
        body: JSON.stringify({ identityId: identity.id, roleId, scope: [scopeType === 'GLOBAL' ? { type: 'GLOBAL', value: '*' } : { type: 'ORG_UNIT', value: unit }], reason: reason || null }),
      });
      onGranted();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.grantRole}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField select label={t.role} value={roleId} onChange={(e) => setRoleId(e.target.value)}>
            {roles.map((r) => <MenuItem key={r.id} value={r.id}>{r.name} ({r.code})</MenuItem>)}
          </TextField>
          <TextField select label={t.scope} value={scopeType} onChange={(e) => setScopeType(e.target.value as 'GLOBAL' | 'ORG_UNIT')}>
            <MenuItem value="ORG_UNIT">{t.scopeOrgUnit}</MenuItem>
            <MenuItem value="GLOBAL">{t.scopeGlobal}</MenuItem>
          </TextField>
          {scopeType === 'ORG_UNIT' && (
            <TextField select label={t.orgUnit} value={unit} onChange={(e) => setUnit(e.target.value)}>
              {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.name}</MenuItem>)}
            </TextField>
          )}
          <TextField label={t.reason} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={!roleId || (scopeType === 'ORG_UNIT' && !unit)} onClick={() => void save()}>{t.grantRole}</Button>
      </DialogActions>
    </Dialog>
  );
}

function LinkDialog({ identity, onClose, onLinked }: { identity: Identity; onClose: () => void; onLinked: () => void }) {
  const { t } = useLocale();
  const [subject, setSubject] = useState('');
  const [error, setError] = useState<unknown>();
  const save = async () => {
    try {
      await apiFetch(`/api/v1/identities/${identity.id}/platform-user`, { method: 'PUT', body: JSON.stringify({ subject: subject.trim() }) });
      onLinked();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.linkLogin}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField label="Keycloak user ID (sub)" value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="8f2c…" />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={!subject.trim()} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}
