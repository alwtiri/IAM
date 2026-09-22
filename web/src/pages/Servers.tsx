import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem,
  Stack, TextField, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import { submitAndWait } from '../api/operations';
import type { DiscoveryRun, Operation, OrgUnit, Page, ProviderBinding, ProviderInstance, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { AccountsTable, OperationOutcome } from './Accounts';
import { DataTable, ErrorAlert } from './common';

const SERVER_TYPES: readonly string[] = ['LINUX_SERVER', 'WINDOWS_SERVER'];
const DATABASE_TYPES: readonly string[] = ['DATABASE'];
const ENVIRONMENTS = ['PRODUCTION', 'STAGING', 'TEST', 'DEVELOPMENT', 'DR'];
const CRITICALITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/** Server management (Phase 3): register servers, connect them through a provider, test, discover and manage accounts. */
export function ServersPage({ kind = 'servers' }: { kind?: 'servers' | 'databases' }) {
  const { t } = useLocale();
  const types: readonly string[] = kind === 'databases' ? DATABASE_TYPES : SERVER_TYPES;
  const title = kind === 'databases' ? t.nav.databases : t.nav.servers;
  const [servers, setServers] = useState<Target[]>();
  const [error, setError] = useState<unknown>();
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<Target>();
  const [editing, setEditing] = useState<Target>();
  const [deleting, setDeleting] = useState<Target>();
  const [info, setInfo] = useState<string>();

  const load = useCallback(async () => {
    try {
      const pages = await Promise.all(types.map((type) => apiFetch<Page<Target>>(`/api/v1/targets?type=${type}&limit=100`)));
      setServers(pages.flatMap((p) => p.items).sort((a, b) => a.name.localeCompare(b.name)));
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, [types]);

  useEffect(() => {
    void load();
  }, [load]);

  if (selected) {
    return <ServerDetail server={selected} onBack={() => { setSelected(undefined); void load(); }}
      onChanged={(s) => setSelected(s)} onDeleted={() => { setSelected(undefined); setInfo(t.deleted); void load(); }} />;
  }
  if (error) return <ErrorAlert error={error} />;
  if (!servers) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h5" component="h2">{title}</Typography>
        <Button variant="contained" onClick={() => setAdding(true)}>{kind === 'databases' ? t.addDatabase : t.addServer}</Button>
      </Stack>
      {info && <Alert severity="success" onClose={() => setInfo(undefined)}>{info}</Alert>}
      {servers.length === 0 ? <Alert severity="info">{t.noServers}</Alert> : (
        <DataTable<Target> title="" rows={servers} rowKey={(s) => s.id} columns={[
          { header: t.name, cell: (s) => <Button size="small" onClick={() => setSelected(s)}>{s.name}</Button> },
          { header: t.hostname, cell: (s) => s.hostname ?? '' },
          { header: t.type, cell: (s) => typeLabel(t, s.type) },
          { header: t.environment, cell: (s) => s.environment },
          { header: t.criticality, cell: (s) => s.criticality },
          { header: t.state, cell: (s) => s.status },
          {
            header: t.action, cell: (s) => (
              <Stack direction="row" spacing={0.5}>
                <Button size="small" onClick={() => setSelected(s)}>{t.connections}</Button>
                <Button size="small" onClick={() => setEditing(s)}>{t.edit}</Button>
                <Button size="small" color="error" onClick={() => setDeleting(s)}>{t.delete}</Button>
              </Stack>
            ),
          },
        ]} />
      )}
      {adding && <AddServerDialog types={types} onClose={() => setAdding(false)} onCreated={(s) => { setAdding(false); void load(); setSelected(s); }} />}
      {editing && <AddServerDialog types={types} existing={editing} onClose={() => setEditing(undefined)}
        onCreated={() => { setEditing(undefined); setInfo(t.saved); void load(); }} />}
      {deleting && <DeleteServerDialog server={deleting} onClose={() => setDeleting(undefined)}
        onDeleted={() => { setDeleting(undefined); setInfo(t.deleted); void load(); }} />}
    </Stack>
  );
}

function typeLabel(t: ReturnType<typeof useLocale>['t'], type: string) {
  return type === 'LINUX_SERVER' ? t.linuxServer : type === 'WINDOWS_SERVER' ? t.windowsServer : type === 'DATABASE' ? t.postgresDatabase : type;
}

function AddServerDialog({ types, existing, onClose, onCreated }: {
  types: readonly string[]; existing?: Target; onClose: () => void; onCreated: (t: Target) => void;
}) {
  const { t } = useLocale();
  const [units, setUnits] = useState<OrgUnit[]>([]);
  const [form, setForm] = useState({
    name: existing?.name ?? '', hostname: existing?.hostname ?? '', type: existing?.type ?? types[0] ?? 'LINUX_SERVER',
    environment: existing?.environment ?? 'PRODUCTION', criticality: existing?.criticality ?? 'MEDIUM', ownerOrgUnitId: existing?.ownerOrgUnitId ?? '',
  });
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<Page<OrgUnit>>('/api/v1/org-units?limit=100').then((p) => {
      setUnits(p.items);
      if (p.items.length > 0) setForm((f) => ({ ...f, ownerOrgUnitId: f.ownerOrgUnitId || (p.items[0]?.id ?? '') }));
    }, setError);
  }, []);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const save = async () => {
    setSaving(true);
    try {
      const saved = existing
        ? await apiFetch<Target>(`/api/v1/targets/${existing.id}`, {
          method: 'PATCH',
          headers: { 'If-Match': String(existing.version ?? 0) },
          body: JSON.stringify({ ...existing, ...form, hostname: form.hostname || null }),
        })
        : await apiFetch<Target>('/api/v1/targets', {
          method: 'POST',
          body: JSON.stringify({ ...form, hostname: form.hostname || null,
            operatingSystem: form.type === 'LINUX_SERVER' ? 'Linux' : form.type === 'WINDOWS_SERVER' ? 'Windows' : null,
            platform: form.type === 'DATABASE' ? 'PostgreSQL' : null }),
        });
      onCreated(saved);
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{existing ? t.editServer : t.addServer}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField required label={t.name} value={form.name} onChange={set('name')} />
          <TextField required label={t.hostname} value={form.hostname} onChange={set('hostname')} placeholder="srv01.example.org" />
          <TextField select label={t.serverType} value={form.type} onChange={set('type')} disabled={!!existing}>
            {types.map((x) => <MenuItem key={x} value={x}>{typeLabel(t, x)}</MenuItem>)}
          </TextField>
          <TextField select label={t.environment} value={form.environment} onChange={set('environment')}>
            {ENVIRONMENTS.map((e) => <MenuItem key={e} value={e}>{e}</MenuItem>)}
          </TextField>
          <TextField select label={t.criticality} value={form.criticality} onChange={set('criticality')}>
            {CRITICALITIES.map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
          </TextField>
          <TextField select required label={t.orgUnit} value={form.ownerOrgUnitId} onChange={set('ownerOrgUnitId')}>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.name}</MenuItem>)}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !form.name || !form.hostname || !form.ownerOrgUnitId} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}

function ServerDetail({ server, onBack, onChanged, onDeleted }: {
  server: Target; onBack: () => void; onChanged: (s: Target) => void; onDeleted: () => void;
}) {
  const { t } = useLocale();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [editConn, setEditConn] = useState<ProviderBinding>();
  const [deleteConn, setDeleteConn] = useState<ProviderBinding>();
  const [instances, setInstances] = useState<Record<string, ProviderInstance>>({});
  const [bindings, setBindings] = useState<ProviderBinding[]>();
  const [runs, setRuns] = useState<DiscoveryRun[]>([]);
  const [error, setError] = useState<unknown>();
  const [connecting, setConnecting] = useState(false);
  const [busy, setBusy] = useState<string>();
  const [outcome, setOutcome] = useState<Operation>();
  const [accountsKey, setAccountsKey] = useState(0);

  const load = useCallback(async () => {
    try {
      const [b, r] = await Promise.all([
        apiFetch<ProviderBinding[]>(`/api/v1/targets/${server.id}/provider-bindings`),
        apiFetch<DiscoveryRun[]>(`/api/v1/targets/${server.id}/discovery-runs`),
      ]);
      setBindings(b);
      setRuns(r);
      const found = await Promise.all(b.map((x) => apiFetch<ProviderInstance>(`/api/v1/provider-instances/${x.providerInstanceId}`).catch(() => undefined)));
      setInstances(Object.fromEntries(found.filter((x): x is ProviderInstance => !!x).map((x) => [x.id, x])));
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, [server.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = async (binding: ProviderBinding, kind: 'test' | 'discover') => {
    setBusy(binding.providerInstanceId + kind);
    setOutcome(undefined);
    try {
      const path = kind === 'test' ? `/api/v1/targets/${server.id}:test-connection` : `/api/v1/targets/${server.id}/discovery-runs`;
      const op = await submitAndWait(path, { providerInstanceId: binding.providerInstanceId }, { timeoutMs: kind === 'test' ? 90_000 : 600_000 });
      setOutcome(op);
      if (kind === 'discover') setAccountsKey((k) => k + 1);
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(undefined);
    }
  };

  const toggle = async (b: ProviderBinding) => {
    const inst = instances[b.providerInstanceId];
    try {
      await apiFetch(`/api/v1/provider-instances/${b.providerInstanceId}:${inst?.enabled === false ? 'enable' : 'disable'}`, { method: 'POST' });
      await load();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Button onClick={onBack}>← {t.back}</Button>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
          <Box>
            <Typography variant="h5" component="h2">{server.name}</Typography>
            <Typography color="text.secondary">
              {server.hostname} · {typeLabel(t, server.type)} · {server.environment} · {server.criticality}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1}>
            <Button variant="outlined" onClick={() => setEditing(true)}>{t.edit}</Button>
            <Button variant="outlined" color="error" onClick={() => setDeleting(true)}>{t.delete}</Button>
          </Stack>
        </Stack>
      </Box>
      {error !== undefined && <ErrorAlert error={error} />}
      {outcome && <OperationOutcome op={outcome} />}

      <Stack spacing={1}>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Typography variant="h6" component="h3">{t.connections}</Typography>
          <Button variant="outlined" onClick={() => setConnecting(true)}>{t.addConnection}</Button>
        </Stack>
        {!bindings ? <CircularProgress aria-label={t.loading} /> : bindings.length === 0 ? <Alert severity="info">{t.noConnections}</Alert> : (
          <DataTable<ProviderBinding> title="" rows={bindings} rowKey={(b) => b.providerInstanceId} columns={[
            { header: t.name, cell: (b) => b.providerName },
            { header: t.type, cell: (b) => b.providerType },
            { header: t.endpoint, cell: (b) => instances[b.providerInstanceId]?.endpoint ?? '' },
            {
              header: t.status, cell: (b) => (instances[b.providerInstanceId]?.enabled === false
                ? <Chip size="small" label={t.disabledLabel} /> : <Chip size="small" color="success" label={t.enabledLabel} />),
            },
            {
              header: t.action, cell: (b) => (busy?.startsWith(b.providerInstanceId) ? <CircularProgress size={20} aria-label={t.working} /> : (
                <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                  <Button size="small" variant="outlined" onClick={() => void act(b, 'test')}>{t.testConnection}</Button>
                  <Button size="small" variant="contained" onClick={() => void act(b, 'discover')}>{t.discoverAccounts}</Button>
                  <Button size="small" onClick={() => setEditConn(b)}>{t.edit}</Button>
                  <Button size="small" onClick={() => void toggle(b)}>{instances[b.providerInstanceId]?.enabled === false ? t.enable : t.disable}</Button>
                  <Button size="small" color="error" onClick={() => setDeleteConn(b)}>{t.delete}</Button>
                </Stack>
              )),
            },
          ]} />
        )}
      </Stack>

      {runs.length > 0 && (
        <DataTable<DiscoveryRun> title={t.discoveryRuns} rows={runs.slice(0, 5)} rowKey={(r) => r.id} columns={[
          { header: t.started, cell: (r) => new Date(r.startedAt).toLocaleString() },
          { header: t.status, cell: (r) => <Chip size="small" label={r.status} color={r.status === 'COMPLETED' ? 'success' : r.status === 'RUNNING' ? 'info' : 'error'} /> },
          { header: t.seen, cell: (r) => r.accountsSeen },
          { header: t.newCount, cell: (r) => r.accountsNew },
          { header: t.removedCount, cell: (r) => r.accountsRemoved },
          { header: t.result, cell: (r) => r.errorMessage ?? '' },
        ]} />
      )}

      <AccountsTable key={accountsKey} query={`?targetId=${server.id}&limit=200`} title={t.accountsOnServer} showServer={false} />

      {connecting && <ConnectDialog server={server} onClose={() => setConnecting(false)} onConnected={() => { setConnecting(false); void load(); }} />}
      {editing && <AddServerDialog types={[server.type]} existing={server} onClose={() => setEditing(false)}
        onCreated={(s) => { setEditing(false); onChanged(s); }} />}
      {deleting && <DeleteServerDialog server={server} onClose={() => setDeleting(false)} onDeleted={onDeleted} />}
      {editConn && instances[editConn.providerInstanceId] && (
        <EditConnectionDialog instance={instances[editConn.providerInstanceId] as ProviderInstance} onClose={() => setEditConn(undefined)}
          onSaved={() => { setEditConn(undefined); void load(); }} />
      )}
      {deleteConn && (
        <ConfirmDialog title={format(t.deleteConnectionTitle, { name: deleteConn.providerName })} body={t.deleteConnectionBody} confirmLabel={t.delete}
          onClose={() => setDeleteConn(undefined)}
          onConfirm={async () => {
            await apiFetch(`/api/v1/provider-instances/${deleteConn.providerInstanceId}`, { method: 'DELETE' });
            setDeleteConn(undefined);
            await load();
          }} />
      )}
    </Stack>
  );
}

/** Creates a provider instance for the server (credential goes straight to Vault) and binds it to the server. */
function ConnectDialog({ server, onClose, onConnected }: { server: Target; onClose: () => void; onConnected: () => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({
    host: server.hostname ?? '', port: '22', username: 'svc-iam', authType: 'key', secret: '', hostKeyFingerprint: '',
    allowUnknownHostKey: false, sudo: true,
  });
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);

  if (server.type === 'DATABASE') {
    return <PostgresConnectDialog server={server} onClose={onClose} onConnected={onConnected} />;
  }
  if (server.type !== 'LINUX_SERVER') {
    return <WinRmConnectDialog server={server} onClose={onClose} onConnected={onConnected} />;
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const save = async () => {
    setSaving(true);
    try {
      const settings: Record<string, string> = { username: form.username, authType: form.authType, sudo: String(form.sudo) };
      if (form.hostKeyFingerprint) settings.hostKeyFingerprint = form.hostKeyFingerprint.trim();
      if (form.allowUnknownHostKey) settings.allowUnknownHostKey = 'true';
      const instance = await apiFetch<ProviderInstance>('/api/v1/provider-instances', {
        method: 'POST',
        body: JSON.stringify({ type: 'linux-ssh', name: `${server.name}-ssh`, endpoint: `ssh://${form.host}:${form.port}`, settings, credential: form.secret }),
      });
      await apiFetch(`/api/v1/targets/${server.id}/provider-bindings`, { method: 'POST', body: JSON.stringify({ providerInstanceId: instance.id }) });
      onConnected();
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  const valid = form.host && form.username && form.secret && (form.hostKeyFingerprint || form.allowUnknownHostKey);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addConnection} — SSH</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Stack direction="row" spacing={2}>
            <TextField required fullWidth label={t.hostname} value={form.host} onChange={set('host')} />
            <TextField label={t.port} value={form.port} onChange={set('port')} sx={{ width: 120 }} />
          </Stack>
          <TextField required label={t.serviceAccount} value={form.username} onChange={set('username')} />
          <TextField select label={t.authType} value={form.authType} onChange={set('authType')}>
            <MenuItem value="key">{t.sshKey}</MenuItem>
            <MenuItem value="password">{t.password}</MenuItem>
          </TextField>
          <TextField required label={t.secretValue} value={form.secret} onChange={set('secret')} type={form.authType === 'password' ? 'password' : 'text'}
            multiline={form.authType === 'key'} minRows={form.authType === 'key' ? 4 : undefined} autoComplete="off" />
          <TextField label={t.hostKeyFingerprint} value={form.hostKeyFingerprint} onChange={set('hostKeyFingerprint')} />
          <FormControlLabel control={<Checkbox checked={form.allowUnknownHostKey} onChange={set('allowUnknownHostKey')} />} label={t.allowUnknownHostKey} />
          <FormControlLabel control={<Checkbox checked={form.sudo} onChange={set('sudo')} />} label={t.useSudo} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !valid} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}


/** WinRM (HTTPS + Basic) connection for a Windows server; the password goes straight to Vault. */
function WinRmConnectDialog({ server, onClose, onConnected }: { server: Target; onClose: () => void; onConnected: () => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({ host: server.hostname ?? '', port: '5986', username: 'svc-iam', secret: '', pinnedCertificateSha256: '' });
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const save = async () => {
    setSaving(true);
    try {
      const settings: Record<string, string> = { username: form.username };
      if (form.pinnedCertificateSha256.trim()) settings.pinnedCertificateSha256 = form.pinnedCertificateSha256.trim();
      const instance = await apiFetch<ProviderInstance>('/api/v1/provider-instances', {
        method: 'POST',
        body: JSON.stringify({ type: 'windows-winrm', name: `${server.name}-winrm`, endpoint: `https://${form.host}:${form.port}/wsman`, settings, credential: form.secret }),
      });
      await apiFetch(`/api/v1/targets/${server.id}/provider-bindings`, { method: 'POST', body: JSON.stringify({ providerInstanceId: instance.id }) });
      onConnected();
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addConnection} — WinRM (HTTPS)</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Alert severity="info">{t.winrmHint}</Alert>
          <Stack direction="row" spacing={2}>
            <TextField required fullWidth label={t.hostname} value={form.host} onChange={set('host')} />
            <TextField label={t.port} value={form.port} onChange={set('port')} sx={{ width: 120 }} />
          </Stack>
          <TextField required label={t.serviceAccount} value={form.username} onChange={set('username')} helperText="HOST\\svc-iam" />
          <TextField required label={t.password} type="password" value={form.secret} onChange={set('secret')} autoComplete="off" />
          <TextField label={t.certificatePin} value={form.pinnedCertificateSha256} onChange={set('pinnedCertificateSha256')} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !form.host || !form.username || !form.secret} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}

/** PostgreSQL connection (TLS verified by default); the password goes straight to Vault. */
function PostgresConnectDialog({ server, onClose, onConnected }: { server: Target; onClose: () => void; onConnected: () => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({ host: server.hostname ?? '', port: '5432', database: 'postgres', username: 'iam_service', secret: '',
    sslMode: 'verify-full', caCertificatePem: '' });
  const [error, setError] = useState<unknown>();
  const [saving, setSaving] = useState(false);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const save = async () => {
    setSaving(true);
    try {
      const settings: Record<string, string> = { username: form.username, sslMode: form.sslMode };
      if (form.sslMode === 'disable') settings.allowInsecure = 'true';
      if (form.caCertificatePem.trim()) settings.caCertificatePem = form.caCertificatePem.trim();
      const instance = await apiFetch<ProviderInstance>('/api/v1/provider-instances', {
        method: 'POST',
        body: JSON.stringify({ type: 'postgresql', name: `${server.name}-pg`, endpoint: `postgresql://${form.host}:${form.port}/${form.database}`, settings, credential: form.secret }),
      });
      await apiFetch(`/api/v1/targets/${server.id}/provider-bindings`, { method: 'POST', body: JSON.stringify({ providerInstanceId: instance.id }) });
      onConnected();
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addConnection} — PostgreSQL</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Alert severity="info">{t.pgHint}</Alert>
          <Stack direction="row" spacing={2}>
            <TextField required fullWidth label={t.hostname} value={form.host} onChange={set('host')} />
            <TextField label={t.port} value={form.port} onChange={set('port')} sx={{ width: 120 }} />
          </Stack>
          <TextField label={t.databaseName} value={form.database} onChange={set('database')} />
          <TextField required label={t.serviceAccount} value={form.username} onChange={set('username')} />
          <TextField required label={t.password} type="password" value={form.secret} onChange={set('secret')} autoComplete="off" />
          <TextField select label="TLS" value={form.sslMode} onChange={set('sslMode')}>
            <MenuItem value="verify-full">verify-full</MenuItem>
            <MenuItem value="verify-ca">verify-ca</MenuItem>
            <MenuItem value="require">require</MenuItem>
            <MenuItem value="disable">disable ({t.labOnly})</MenuItem>
          </TextField>
          <TextField label={t.caCertificate} value={form.caCertificatePem} onChange={set('caCertificatePem')} multiline minRows={3} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={saving || !form.host || !form.username || !form.secret} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}

export function DatabasesPage() {
  return <ServersPage kind="databases" />;
}


/** Confirmation dialog; when {@code confirmText} is set the user must type it (for destructive actions). */
function ConfirmDialog({ title, body, confirmLabel, confirmText, onClose, onConfirm }: {
  title: string; body: string; confirmLabel: string; confirmText?: string; onClose: () => void; onConfirm: () => Promise<void>;
}) {
  const { t } = useLocale();
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <Typography color="text.secondary">{body}</Typography>
          {confirmText && <TextField label={t.typeNameToConfirm} placeholder={confirmText} value={typed} onChange={(e) => setTyped(e.target.value)} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" color="error" disabled={busy || (!!confirmText && typed.trim() !== confirmText)} onClick={() => {
          setBusy(true);
          setError(undefined);
          onConfirm().catch(setError).finally(() => setBusy(false));
        }}>{confirmLabel}</Button>
      </DialogActions>
    </Dialog>
  );
}

function DeleteServerDialog({ server, onClose, onDeleted }: { server: Target; onClose: () => void; onDeleted: () => void }) {
  const { t } = useLocale();
  return (
    <ConfirmDialog title={format(t.deleteServerTitle, { name: server.name })} body={t.deleteServerBody} confirmLabel={t.delete}
      confirmText={server.name} onClose={onClose}
      onConfirm={async () => {
        await apiFetch(`/api/v1/targets/${server.id}`, { method: 'DELETE', body: JSON.stringify({ reason: 'deleted in the UI' }) });
        onDeleted();
      }} />
  );
}

/** Edits any connection type: endpoint, settings and (optionally) a new credential that becomes a new Vault version. */
function EditConnectionDialog({ instance, onClose, onSaved }: { instance: ProviderInstance; onClose: () => void; onSaved: () => void }) {
  const { t } = useLocale();
  const [endpoint, setEndpoint] = useState(instance.endpoint);
  const [settings, setSettings] = useState<[string, string][]>(Object.entries(instance.settings ?? {}));
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const multiline = (k: string) => /pem|certificate/i.test(k);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.editConnection} — {instance.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField required label={t.endpoint} value={endpoint} onChange={(e) => setEndpoint(e.target.value)} />
          <Typography variant="subtitle2">{t.settingsLabel}</Typography>
          {settings.map(([k, v], i) => (
            <TextField key={k} label={k} value={v} multiline={multiline(k)} minRows={multiline(k) ? 3 : undefined}
              onChange={(e) => setSettings(settings.map((x, j) => (j === i ? [k, e.target.value] : x)))} />
          ))}
          <TextField label={t.newSecretOptional} value={secret} onChange={(e) => setSecret(e.target.value)} multiline minRows={3} autoComplete="off" />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={busy || !endpoint.trim()} onClick={() => {
          setBusy(true);
          setError(undefined);
          apiFetch(`/api/v1/provider-instances/${instance.id}`, {
            method: 'PATCH',
            body: JSON.stringify({ endpoint: endpoint.trim(), settings: Object.fromEntries(settings.filter(([, v]) => v !== '')), credential: secret || null }),
          }).then(onSaved, setError).finally(() => setBusy(false));
        }}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}
