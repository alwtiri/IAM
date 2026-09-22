import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, MenuItem, Stack, TextField, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import type { AuditEvent, Identity, OrgUnit, Page, Role } from '../api/types';
import { hasPermission } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { useMe } from '../MeContext';
import { DataTable, ErrorAlert } from './common';

const IDENTITY_TYPES = ['EMPLOYEE', 'CONTRACTOR', 'CONSULTANT', 'SERVICE', 'TEMPORARY', 'EXTERNAL', 'EMERGENCY'];
const IDENTITY_STATES = ['PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED'];
const EMPLOYMENT = ['PRE_HIRE', 'ACTIVE', 'ON_LEAVE', 'TERMINATED'];

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

interface Person {
  id: string;
  orgUnitId: string | null;
  employeeId: string | null;
  givenName: string;
  familyName: string;
  displayName: string;
  positionId: string | null;
  locationId: string | null;
  managerPersonId: string | null;
  employmentStatus: string;
  startDate: string | null;
  endDate: string | null;
  email: string | null;
  phone: string | null;
  version: number;
}

type UserRow = Identity & { orgUnitPath?: string | null; stateReason?: string | null; version?: number };

export const identityStateColor = (s: string) => (s === 'ACTIVE' ? 'success' : s === 'PENDING' ? 'info' : s === 'SUSPENDED' ? 'warning' : 'error');

/** Users: search, filter and sort; create (one step); open a user for profile, lifecycle, roles, permissions and activity. */
export function UsersPage() {
  const { t } = useLocale();
  const canCreate = hasPermission(useMe(), 'identity:write');
  const [users, setUsers] = useState<UserRow[]>();
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<unknown>();
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<UserRow>();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState('');
  const [type, setType] = useState('');
  const [unit, setUnit] = useState('');
  const [sort, setSort] = useState<'name' | 'username' | 'state'>('name');
  const [units, setUnits] = useState<OrgUnit[]>([]);

  useEffect(() => {
    const h = setTimeout(() => setQuery(search.trim()), 300);
    return () => clearTimeout(h);
  }, [search]);
  useEffect(() => {
    apiFetch<Page<OrgUnit>>('/api/v1/org-units?limit=200').then((p) => setUnits(p.items), () => undefined);
  }, []);

  const path = useMemo(() => {
    const p = new URLSearchParams({ limit: '100' });
    if (query) p.set('q', query);
    if (state) p.set('state', state);
    if (type) p.set('type', type);
    if (unit) p.set('orgUnitId', unit);
    return `/api/v1/identities?${p.toString()}`;
  }, [query, state, type, unit]);

  const load = useCallback(async (next?: string | null) => {
    try {
      const p = await apiFetch<Page<UserRow>>(`${path}${next ? `&cursor=${encodeURIComponent(next)}` : ''}`);
      setUsers((prev) => (next ? [...(prev ?? []), ...p.items] : p.items));
      setCursor(p.nextCursor);
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, [path]);

  useEffect(() => {
    void load();
  }, [load]);

  if (selected) {
    return <UserDetail identity={selected} units={units} onBack={() => { setSelected(undefined); void load(); }} />;
  }
  if (error) return <ErrorAlert error={error} />;
  const rows = [...(users ?? [])].sort((a, b) => (sort === 'username' ? a.username.localeCompare(b.username)
    : sort === 'state' ? a.state.localeCompare(b.state) || a.displayName.localeCompare(b.displayName) : a.displayName.localeCompare(b.displayName)));
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
        <Box>
          <Typography variant="h5" component="h2">{t.nav.users}</Typography>
          {users && <Typography variant="body2" color="text.secondary">{format(t.usersCount, { n: `${rows.length}${cursor ? '+' : ''}` })}</Typography>}
        </Box>
        {canCreate && <Button variant="contained" onClick={() => setAdding(true)}>{t.addUser}</Button>}
      </Stack>
      <Card><CardContent sx={{ py: 2, '&:last-child': { pb: 2 } }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
          <TextField size="small" label={t.search} placeholder={`${t.username} / ${t.name} / ${t.email}`} value={search}
            onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260, flexGrow: 1 }} />
          <TextField size="small" select label={t.state} value={state} onChange={(e) => setState(e.target.value)} sx={{ minWidth: 150 }}>
            <MenuItem value="">{t.allStates}</MenuItem>
            {IDENTITY_STATES.map((x) => <MenuItem key={x} value={x}>{x}</MenuItem>)}
          </TextField>
          <TextField size="small" select label={t.identityType} value={type} onChange={(e) => setType(e.target.value)} sx={{ minWidth: 150 }}>
            <MenuItem value="">{t.allTypes}</MenuItem>
            {IDENTITY_TYPES.map((x) => <MenuItem key={x} value={x}>{x}</MenuItem>)}
          </TextField>
          <TextField size="small" select label={t.orgUnit} value={unit} onChange={(e) => setUnit(e.target.value)} sx={{ minWidth: 170 }}>
            <MenuItem value="">{t.allUnits}</MenuItem>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.path}</MenuItem>)}
          </TextField>
          <TextField size="small" select label={t.sortBy} value={sort} onChange={(e) => setSort(e.target.value as 'name' | 'username' | 'state')} sx={{ minWidth: 140 }}>
            <MenuItem value="name">{t.sortName}</MenuItem>
            <MenuItem value="username">{t.sortUsername}</MenuItem>
            <MenuItem value="state">{t.sortState}</MenuItem>
          </TextField>
        </Stack>
      </CardContent></Card>
      {!users ? <CircularProgress aria-label={t.loading} /> : rows.length === 0 ? <Alert severity="info">{t.noUsers}</Alert> : (
        <DataTable<UserRow> hideTitle title={t.nav.users} rows={rows} rowKey={(u) => u.id} columns={[
          { header: t.name, cell: (u) => <Button size="small" onClick={() => setSelected(u)}>{u.displayName}</Button> },
          { header: t.username, cell: (u) => u.username },
          { header: t.orgUnit, cell: (u) => u.orgUnitPath ?? '—' },
          { header: t.type, cell: (u) => u.type },
          { header: t.state, cell: (u) => <Chip size="small" label={u.state} color={identityStateColor(u.state)} title={u.stateReason ?? ''} /> },
          { header: t.loginAccount, cell: (u) => (u.platformUser ? <Chip size="small" variant="outlined" color="success" label={t.linked} /> : <Typography variant="caption" color="text.secondary">{t.notLinked}</Typography>) },
        ]} footer={cursor ? <Button onClick={() => void load(cursor)}>{t.loadMore}</Button> : null} />
      )}
      {adding && <AddUserDialog units={units} onClose={() => setAdding(false)} onCreated={(u) => { setAdding(false); void load(); setSelected(u); }} />}
    </Stack>
  );
}

function AddUserDialog({ units, onClose, onCreated }: { units: OrgUnit[]; onClose: () => void; onCreated: (u: Identity) => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({ givenName: '', familyName: '', email: '', orgUnitId: units[0]?.id ?? '', type: 'EMPLOYEE', username: '', activate: true, managerPersonId: '' });
  const [people, setPeople] = useState<Identity[]>([]);
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<Page<Identity>>('/api/v1/identities?state=ACTIVE&limit=200').then((p) => setPeople(p.items), () => undefined);
  }, []);
  useEffect(() => {
    if (!form.orgUnitId && units[0]) setForm((f) => ({ ...f, orgUnitId: units[0]?.id ?? '' }));
  }, [units, form.orgUnitId]);

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
      const identity = await apiFetch<Identity>('/api/v1/users', {
        method: 'POST',
        body: JSON.stringify({ givenName: form.givenName.trim(), familyName: form.familyName.trim(), email: form.email.trim() || null,
          orgUnitId: form.orgUnitId, managerPersonId: form.managerPersonId || null, type: form.type, username: form.username, activate: form.activate }),
      });
      onCreated(identity);
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  const emailOk = !form.email || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email);
  const valid = form.givenName.trim() && form.familyName.trim() && form.orgUnitId && emailOk && /^[a-z0-9][a-z0-9._-]{1,63}$/.test(form.username);
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
          <TextField label={t.email} type="email" value={form.email} onChange={set('email')} error={!emailOk} helperText={t.emailForLogin} />
          <TextField select required label={t.orgUnit} value={form.orgUnitId} onChange={set('orgUnitId')}>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.path}</MenuItem>)}
          </TextField>
          <TextField select label={t.manager} value={form.managerPersonId} onChange={set('managerPersonId')} helperText={t.managerHelp}>
            <MenuItem value="">—</MenuItem>
            {people.map((p) => <MenuItem key={p.id} value={p.personId}>{p.displayName} ({p.username})</MenuItem>)}
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

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Box sx={{ minWidth: 180 }}>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 500 }}>{label}</Typography>
      <Typography sx={{ fontSize: 14 }}>{value || '—'}</Typography>
    </Box>
  );
}

function UserDetail({ identity: initial, units, onBack }: { identity: UserRow; units: OrgUnit[]; onBack: () => void }) {
  const { t } = useLocale();
  const me = useMe();
  const canLifecycle = hasPermission(me, 'identity:lifecycle');
  const canEditPerson = hasPermission(me, 'person:write');
  const canGrant = hasPermission(me, 'role-assignment:write');
  const canAudit = hasPermission(me, 'audit:read');
  const canLogin = hasPermission(me, 'identity:platform-user');
  const isSelf = me?.identityId === initial.id;
  const [identity, setIdentity] = useState<UserRow>(initial);
  const [person, setPerson] = useState<Person>();
  const [manager, setManager] = useState<string>();
  const [grants, setGrants] = useState<RoleAssignment[]>();
  const [roles, setRoles] = useState<Role[]>([]);
  const [activity, setActivity] = useState<AuditEvent[]>();
  const [error, setError] = useState<unknown>();
  const [prompt, setPrompt] = useState<{ title: string; body?: string; required?: boolean; run: (reason: string) => Promise<void> }>();
  const [granting, setGranting] = useState(false);
  const [linking, setLinking] = useState(false);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<{ severity: 'success' | 'warning'; text: string }>();
  const [loginBusy, setLoginBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [i, g] = await Promise.all([
        apiFetch<UserRow>(`/api/v1/identities/${initial.id}`),
        apiFetch<Page<RoleAssignment>>(`/api/v1/role-assignments?identityId=${initial.id}&activeOnly=true&limit=100`),
      ]);
      setIdentity((prev) => ({ ...prev, ...i }));
      setGrants(g.items);
      const p = await apiFetch<Person>(`/api/v1/persons/${i.personId}`).catch(() => undefined);
      setPerson(p);
      if (p?.managerPersonId) {
        const m = await apiFetch<Page<Identity>>(`/api/v1/identities?personId=${p.managerPersonId}&limit=5`).catch(() => undefined);
        setManager(m?.items[0] ? `${m.items[0].displayName} (${m.items[0].username})` : undefined);
      } else {
        setManager(undefined);
      }
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, [initial.id]);

  const loadActivity = useCallback(async () => {
    if (!canAudit) return;
    try {
      const [about, by] = await Promise.all([
        apiFetch<Page<AuditEvent>>(`/api/v1/audit-events?objectId=${initial.id}&limit=50`),
        apiFetch<Page<AuditEvent>>(`/api/v1/audit-events?actorIdentityId=${initial.id}&limit=50`),
      ]);
      const all = new Map<string, AuditEvent>();
      [...about.items, ...by.items].forEach((e) => all.set(e.id, e));
      setActivity([...all.values()].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)).slice(0, 30));
    } catch {
      setActivity([]);
    }
  }, [initial.id, canAudit]);

  useEffect(() => {
    void load();
    void loadActivity();
    apiFetch<Role[]>('/api/v1/roles').then(setRoles, () => undefined);
  }, [load, loadActivity]);

  const run = async (fn: () => Promise<void>) => {
    try {
      await fn();
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  };

  const transition = (action: 'activate' | 'suspend' | 'reinstate' | 'disable', label: string, body?: string, required = false) => setPrompt({
    title: label, body, required,
    run: async (reason) => {
      await apiFetch<Identity>(`/api/v1/identities/${identity.id}:${action}`, { method: 'POST', body: JSON.stringify({ reason: reason || label }) });
      await load();
      await loadActivity();
    },
  });

  const createLogin = () => run(async () => {
    setLoginBusy(true);
    try {
      const r = await apiFetch<{ subject: string; invitationSent: boolean }>(`/api/v1/identities/${identity.id}:create-login`, { method: 'POST' });
      setNotice(r.invitationSent ? { severity: 'success', text: t.loginCreated } : { severity: 'warning', text: t.loginCreatedNoMail });
      await load();
    } finally {
      setLoginBusy(false);
    }
  });

  const sendReset = () => run(async () => {
    setLoginBusy(true);
    try {
      await apiFetch(`/api/v1/identities/${identity.id}:resend-invitation`, { method: 'POST' });
      setNotice({ severity: 'success', text: t.resetSent });
    } finally {
      setLoginBusy(false);
    }
  });

  const permissions = useMemo(() => {
    const set = new Set<string>();
    (grants ?? []).forEach((g) => roles.find((r) => r.id === g.roleId || r.code === g.roleCode)?.permissions.forEach((p) => set.add(p)));
    return [...set].sort();
  }, [grants, roles]);

  const s = identity.state;
  const unitName = (id: string | null | undefined) => (id ? units.find((u) => u.id === id)?.path ?? id : null);
  return (
    <Stack spacing={3}>
      <Box>
        <Button onClick={onBack}>← {t.backToUsers}</Button>
        <Stack direction="row" spacing={2} alignItems="center">
          <Typography variant="h5" component="h2">{identity.displayName}</Typography>
          <Chip label={s} color={identityStateColor(s)} title={identity.stateReason ?? ''} />
        </Stack>
        <Typography color="text.secondary">{identity.username} · {identity.type}{identity.orgUnitPath ? ` · ${identity.orgUnitPath}` : ''}</Typography>
      </Box>
      {error !== undefined && <ErrorAlert error={error} />}
      {notice && <Alert severity={notice.severity} onClose={() => setNotice(undefined)}>{notice.text}</Alert>}

      <Card><CardContent>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
          {canLifecycle && !isSelf && s === 'PENDING' && <Button variant="contained" onClick={() => transition('activate', t.activate)}>{t.activate}</Button>}
          {canLifecycle && !isSelf && s === 'ACTIVE' && <Button variant="outlined" color="warning" onClick={() => transition('suspend', t.lockUser, undefined, true)}>{t.lockUser}</Button>}
          {canLifecycle && !isSelf && s === 'SUSPENDED' && <Button variant="contained" onClick={() => transition('reinstate', t.unlockUser)}>{t.unlockUser}</Button>}
          {canLifecycle && !isSelf && (s === 'ACTIVE' || s === 'SUSPENDED' || s === 'PENDING') && (
            <Button variant="outlined" color="error" onClick={() => transition('disable', t.deactivateUser, t.deactivateBody, true)}>{t.deactivateUser}</Button>
          )}
          <Box sx={{ flexGrow: 1 }} />
          <Chip variant="outlined" color={identity.platformUser ? 'success' : 'default'} label={`${t.loginAccount}: ${identity.platformUser ? t.linked : t.notLinked}`} />
          {canLogin && !identity.platformUser && s === 'ACTIVE' && (
            <Button variant="contained" color="secondary" disabled={loginBusy} onClick={() => void createLogin()}>{t.createLogin}</Button>
          )}
          {canLogin && !identity.platformUser && <Button size="small" onClick={() => setLinking(true)}>{t.linkLogin}</Button>}
          {canLogin && identity.platformUser && s === 'ACTIVE' && <Button size="small" disabled={loginBusy} onClick={() => void sendReset()}>{t.sendReset}</Button>}
        </Stack>
      </CardContent></Card>

      <Card><CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
          <Typography variant="h6" component="h3">{t.profile}</Typography>
          {canEditPerson && person && <Button variant="outlined" onClick={() => setEditing(true)}>{t.editProfile}</Button>}
        </Stack>
        {!person ? <Typography color="text.secondary">—</Typography> : (
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}>
            <Field label={t.givenName} value={person.givenName} />
            <Field label={t.familyName} value={person.familyName} />
            <Field label={t.displayNameLabel} value={person.displayName} />
            <Field label={t.email} value={person.email} />
            <Field label={t.phone} value={person.phone} />
            <Field label={t.employeeId} value={person.employeeId} />
            <Field label={t.orgUnit} value={unitName(person.orgUnitId)} />
            <Field label={t.manager} value={manager} />
            <Field label={t.employmentStatus} value={person.employmentStatus} />
            <Field label={t.validUntilLabel} value={identity.validUntil ? new Date(identity.validUntil).toLocaleString() : null} />
          </Box>
        )}
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>{t.sessionsNotTracked}</Typography>
      </CardContent></Card>

      <Card><CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6" component="h3">{t.roleAssignments}</Typography>
          {canGrant && <Button variant="contained" onClick={() => setGranting(true)} disabled={s !== 'ACTIVE'}>{t.grantRole}</Button>}
        </Stack>
        {!grants ? <CircularProgress aria-label={t.loading} /> : grants.length === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
          <DataTable<RoleAssignment> hideTitle title={t.roleAssignments} rows={grants} rowKey={(g) => g.id} columns={[
            { header: t.role, cell: (g) => g.roleCode },
            { header: t.scope, cell: (g) => g.scope.map((x) => (x.type === 'GLOBAL' ? t.scopeGlobal : `${x.type}: ${unitName(x.value) ?? x.value}`)).join(', ') },
            { header: t.time, cell: (g) => new Date(g.grantedAt).toLocaleString() },
            {
              header: t.action, cell: (g) => (canGrant ? <Button size="small" color="error" onClick={() => setPrompt({ title: `${t.revoke} ${g.roleCode}`, run: async (reason) => {
                await apiFetch(`/api/v1/role-assignments/${g.id}:revoke`, { method: 'POST', body: JSON.stringify({ reason: reason || 'revoked by administrator' }) });
                await load();
              } })}>{t.revoke}</Button> : null),
            },
          ]} />
        )}
        <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>{t.effectivePermissions}</Typography>
        {permissions.length === 0 ? <Typography color="text.secondary">—</Typography> : (
          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>{permissions.map((p) => <Chip key={p} size="small" variant="outlined" label={p} />)}</Stack>
        )}
      </CardContent></Card>

      {canAudit && (
        <Card><CardContent>
          <Typography variant="h6" component="h3" sx={{ mb: 1 }}>{t.activity}</Typography>
          {!activity ? <CircularProgress aria-label={t.loading} /> : activity.length === 0 ? <Typography color="text.secondary">{t.noActivity}</Typography> : (
            <DataTable<AuditEvent> hideTitle title={t.activity} rows={activity} rowKey={(e) => e.id} columns={[
              { header: t.time, cell: (e) => new Date(e.occurredAt).toLocaleString() },
              { header: t.action, cell: (e) => <Typography sx={{ fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{e.action}</Typography> },
              { header: t.object, cell: (e) => [e.objectType, e.objectId === identity.id ? identity.username : e.objectId].filter(Boolean).join(' / ') },
              { header: t.result, cell: (e) => e.result },
            ]} />
          )}
        </CardContent></Card>
      )}

      {prompt && <ReasonDialog title={prompt.title} body={prompt.body} required={prompt.required} onClose={() => setPrompt(undefined)} onConfirm={async (reason) => {
        await run(() => prompt.run(reason));
        setPrompt(undefined);
      }} />}
      {editing && person && <EditProfileDialog person={person} units={units} onClose={() => setEditing(false)}
        onSaved={() => { setEditing(false); setNotice({ severity: 'success', text: t.saved }); void load(); void loadActivity(); }} />}
      {granting && <GrantDialog identity={identity} roles={roles} units={units} onClose={() => setGranting(false)} onGranted={() => { setGranting(false); void load(); void loadActivity(); }} />}
      {linking && <LinkDialog identity={identity} onClose={() => setLinking(false)} onLinked={() => { setLinking(false); void load(); }} />}
    </Stack>
  );
}

function EditProfileDialog({ person, units, onClose, onSaved }: { person: Person; units: OrgUnit[]; onClose: () => void; onSaved: () => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({
    givenName: person.givenName, familyName: person.familyName, displayName: person.displayName ?? '', email: person.email ?? '',
    phone: person.phone ?? '', employeeId: person.employeeId ?? '', orgUnitId: person.orgUnitId ?? '', managerPersonId: person.managerPersonId ?? '',
    employmentStatus: person.employmentStatus,
  });
  const [people, setPeople] = useState<Identity[]>([]);
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    apiFetch<Page<Identity>>('/api/v1/identities?state=ACTIVE&limit=200').then((p) => setPeople(p.items.filter((x) => x.personId !== person.id)), () => undefined);
  }, [person.id]);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const emailOk = !form.email || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email);
  const save = async () => {
    setSaving(true);
    try {
      await apiFetch(`/api/v1/persons/${person.id}`, {
        method: 'PATCH',
        headers: { 'If-Match': String(person.version) },
        body: JSON.stringify({
          givenName: form.givenName.trim(), familyName: form.familyName.trim(), displayName: form.displayName.trim() || null,
          email: form.email.trim() || null, phone: form.phone.trim() || null, employeeId: form.employeeId.trim() || null,
          orgUnitId: form.orgUnitId || null, managerPersonId: form.managerPersonId || null, employmentStatus: form.employmentStatus,
          positionId: person.positionId, locationId: person.locationId, startDate: person.startDate, endDate: person.endDate,
        }),
      });
      onSaved();
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.editProfile}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Stack direction="row" spacing={2}>
            <TextField required fullWidth label={t.givenName} value={form.givenName} onChange={set('givenName')} />
            <TextField required fullWidth label={t.familyName} value={form.familyName} onChange={set('familyName')} />
          </Stack>
          <TextField label={t.displayNameLabel} value={form.displayName} onChange={set('displayName')} />
          <Stack direction="row" spacing={2}>
            <TextField fullWidth label={t.email} value={form.email} onChange={set('email')} error={!emailOk} />
            <TextField fullWidth label={t.phone} value={form.phone} onChange={set('phone')} />
          </Stack>
          <TextField label={t.employeeId} value={form.employeeId} onChange={set('employeeId')} />
          <TextField select label={t.orgUnit} value={form.orgUnitId} onChange={set('orgUnitId')}>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.path}</MenuItem>)}
          </TextField>
          <TextField select label={t.manager} value={form.managerPersonId} onChange={set('managerPersonId')}>
            <MenuItem value="">—</MenuItem>
            {people.map((p) => <MenuItem key={p.id} value={p.personId}>{p.displayName} ({p.username})</MenuItem>)}
          </TextField>
          <TextField select label={t.employmentStatus} value={form.employmentStatus} onChange={set('employmentStatus')}>
            {EMPLOYMENT.map((x) => <MenuItem key={x} value={x}>{x}</MenuItem>)}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !form.givenName.trim() || !form.familyName.trim() || !emailOk} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}

function ReasonDialog({ title, body, required = false, onClose, onConfirm }: {
  title: string; body?: string; required?: boolean; onClose: () => void; onConfirm: (reason: string) => Promise<void>;
}) {
  const { t } = useLocale();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        {body && <Alert severity="warning" sx={{ mt: 1 }}>{body}</Alert>}
        <TextField fullWidth sx={{ mt: 2 }} label={required ? t.reasonRequired : t.reason} value={reason} onChange={(e) => setReason(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 500 } }} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" color={body ? 'error' : 'primary'} disabled={busy || (required && !reason.trim())}
          onClick={() => { setBusy(true); void onConfirm(reason.trim()).finally(() => setBusy(false)); }}>{title}</Button>
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
