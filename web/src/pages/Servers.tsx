import { useCallback, useEffect, useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem,
  Stack, TextField, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import { submitAndWait } from '../api/operations';
import type { DiscoveryRun, Operation, OrgUnit, Page, ProviderBinding, ProviderInstance, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { AccountsTable, OperationOutcome } from './Accounts';
import { DataTable, ErrorAlert } from './common';

const SERVER_TYPES = ['LINUX_SERVER', 'WINDOWS_SERVER'] as const;
const ENVIRONMENTS = ['PRODUCTION', 'STAGING', 'TEST', 'DEVELOPMENT', 'DR'];
const CRITICALITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/** Server management (Phase 3): register servers, connect them through a provider, test, discover and manage accounts. */
export function ServersPage() {
  const { t } = useLocale();
  const [servers, setServers] = useState<Target[]>();
  const [error, setError] = useState<unknown>();
  const [adding, setAdding] = useState(false);
  const [selected, setSelected] = useState<Target>();

  const load = useCallback(async () => {
    try {
      const pages = await Promise.all(SERVER_TYPES.map((type) => apiFetch<Page<Target>>(`/api/v1/targets?type=${type}&limit=100`)));
      setServers(pages.flatMap((p) => p.items).sort((a, b) => a.name.localeCompare(b.name)));
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (selected) {
    return <ServerDetail server={selected} onBack={() => setSelected(undefined)} />;
  }
  if (error) return <ErrorAlert error={error} />;
  if (!servers) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h5" component="h2">{t.nav.servers}</Typography>
        <Button variant="contained" onClick={() => setAdding(true)}>{t.addServer}</Button>
      </Stack>
      {servers.length === 0 ? <Alert severity="info">{t.noServers}</Alert> : (
        <DataTable<Target> title="" rows={servers} rowKey={(s) => s.id} columns={[
          { header: t.name, cell: (s) => <Button size="small" onClick={() => setSelected(s)}>{s.name}</Button> },
          { header: t.hostname, cell: (s) => s.hostname ?? '' },
          { header: t.type, cell: (s) => (s.type === 'LINUX_SERVER' ? t.linuxServer : t.windowsServer) },
          { header: t.environment, cell: (s) => s.environment },
          { header: t.criticality, cell: (s) => s.criticality },
          { header: t.state, cell: (s) => s.status },
        ]} />
      )}
      {adding && <AddServerDialog onClose={() => setAdding(false)} onCreated={(s) => { setAdding(false); void load(); setSelected(s); }} />}
    </Stack>
  );
}

function AddServerDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (t: Target) => void }) {
  const { t } = useLocale();
  const [units, setUnits] = useState<OrgUnit[]>([]);
  const [form, setForm] = useState({ name: '', hostname: '', type: 'LINUX_SERVER', environment: 'PRODUCTION', criticality: 'MEDIUM', ownerOrgUnitId: '' });
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
      const created = await apiFetch<Target>('/api/v1/targets', {
        method: 'POST',
        body: JSON.stringify({ ...form, hostname: form.hostname || null, operatingSystem: form.type === 'LINUX_SERVER' ? 'Linux' : 'Windows' }),
      });
      onCreated(created);
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addServer}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField required label={t.name} value={form.name} onChange={set('name')} />
          <TextField required label={t.hostname} value={form.hostname} onChange={set('hostname')} placeholder="srv01.example.org" />
          <TextField select label={t.serverType} value={form.type} onChange={set('type')}>
            <MenuItem value="LINUX_SERVER">{t.linuxServer}</MenuItem>
            <MenuItem value="WINDOWS_SERVER">{t.windowsServer}</MenuItem>
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

function ServerDetail({ server, onBack }: { server: Target; onBack: () => void }) {
  const { t } = useLocale();
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

  return (
    <Stack spacing={3}>
      <Box>
        <Button onClick={onBack}>← {t.back}</Button>
        <Typography variant="h5" component="h2">{server.name}</Typography>
        <Typography color="text.secondary">
          {server.hostname} · {server.type === 'LINUX_SERVER' ? t.linuxServer : t.windowsServer} · {server.environment} · {server.criticality}
        </Typography>
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
            {
              header: t.action, cell: (b) => (busy?.startsWith(b.providerInstanceId) ? <CircularProgress size={20} aria-label={t.working} /> : (
                <Stack direction="row" spacing={1}>
                  <Button size="small" variant="outlined" onClick={() => void act(b, 'test')}>{t.testConnection}</Button>
                  <Button size="small" variant="contained" onClick={() => void act(b, 'discover')}>{t.discoverAccounts}</Button>
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

  if (server.type !== 'LINUX_SERVER') {
    return (
      <Dialog open onClose={onClose}>
        <DialogTitle>{t.addConnection}</DialogTitle>
        <DialogContent><Alert severity="info">{t.winrmPending}</Alert></DialogContent>
        <DialogActions><Button onClick={onClose}>{t.cancel}</Button></DialogActions>
      </Dialog>
    );
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

