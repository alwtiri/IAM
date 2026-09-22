import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, InputAdornment, Stack, Tab,
  Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import { hasPermission } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { useMe } from '../MeContext';
import { DataTable, ErrorAlert } from './common';

export interface Checkout {
  id: string;
  accountId: string;
  accountName: string | null;
  targetName: string | null;
  identityName: string | null;
  requestId: string | null;
  reason: string | null;
  startedAt: string;
  notAfter: string;
  status: string;
  endedAt: string | null;
  revealCount: number;
}

export interface VaultedCredential {
  accountId: string;
  accountName: string;
  targetName: string;
  providerType: string;
  rotationStatus: string;
  rotationTrigger: string | null;
  lastRotatedAt: string | null;
  lastRotationError: string | null;
  nextRotationAt: string | null;
  activeCheckout: Checkout | null;
}

interface RequestableCredential {
  accountId: string;
  accountName: string;
  targetName: string;
  providerType: string;
  available: boolean;
  unavailableReason: string | null;
  allowed: boolean;
  approvals: string[];
  requireJustification: boolean;
  explanation: string;
}

interface Revealed {
  checkoutId: string;
  accountName: string;
  targetName: string;
  password: string;
  alternatePassword: string | null;
  notAfter: string;
}

export const rotationColor = (s: string) => (s === 'VERIFIED' ? 'success' : s === 'ROTATING' ? 'info' : s === 'UNKNOWN' ? 'warning'
  : s === 'FAILED' ? 'error' : 'default');

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

/** Reason + duration dialog used for direct checkouts and for checkout requests. */
function CheckoutDialog({ open, title, submitLabel, onClose, onSubmit }: {
  open: boolean;
  title: string;
  submitLabel: string;
  onClose: () => void;
  onSubmit: (reason: string, hours: number) => Promise<void>;
}) {
  const { t } = useLocale();
  const [reason, setReason] = useState('');
  const [hours, setHours] = useState(2);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField required label={t.justification} value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2}
            slotProps={{ htmlInput: { maxLength: 500 } }} />
          <TextField type="number" label={t.durationHours} value={hours} onChange={(e) => setHours(Number(e.target.value))}
            slotProps={{ htmlInput: { min: 1, max: 72 } }} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={busy || !reason.trim() || hours < 1 || hours > 72} onClick={() => {
          setBusy(true);
          setError(undefined);
          onSubmit(reason.trim(), hours).then(() => { setReason(''); onClose(); }, setError).finally(() => setBusy(false));
        }}>{submitLabel}</Button>
      </DialogActions>
    </Dialog>
  );
}

/** Shows a revealed password with copy; hides it after 60 seconds and never stores it. */
function RevealDialog({ revealed, onClose }: { revealed?: Revealed; onClose: () => void }) {
  const { t } = useLocale();
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => {
    if (revealed) {
      timer.current = setTimeout(onClose, 60_000);
    }
    return () => clearTimeout(timer.current);
  }, [revealed, onClose]);
  const copy = (value: string) => {
    void navigator.clipboard?.writeText(value).then(() => setCopied(true));
  };
  const field = (value: string) => (
    <TextField fullWidth value={value} slotProps={{
      input: {
        readOnly: true, sx: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', letterSpacing: 1 },
        endAdornment: <InputAdornment position="end"><Button size="small" onClick={() => copy(value)}>{copied ? t.copied : t.copy}</Button></InputAdornment>,
      },
    }} />
  );
  return (
    <Dialog open={!!revealed} onClose={onClose} fullWidth maxWidth="sm">
      {revealed && (
        <>
          <DialogTitle>{format(t.revealTitle, { account: revealed.accountName, server: revealed.targetName })}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              {field(revealed.password)}
              {revealed.alternatePassword && (
                <>
                  <Alert severity="warning">{t.alternatePassword}</Alert>
                  {field(revealed.alternatePassword)}
                </>
              )}
              <Typography variant="body2" color="text.secondary">{format(t.revealHint, { until: when(revealed.notAfter) })}</Typography>
            </Stack>
          </DialogContent>
          <DialogActions><Button onClick={onClose}>{t.close}</Button></DialogActions>
        </>
      )}
    </Dialog>
  );
}

/** Own (or, for credential managers, all) checkouts with reveal and check-in. */
export function CheckoutsTable({ mine = true, title }: { mine?: boolean; title: string }) {
  const { t } = useLocale();
  const [rows, setRows] = useState<Checkout[]>();
  const [error, setError] = useState<unknown>();
  const [actionError, setActionError] = useState<unknown>();
  const [revealed, setRevealed] = useState<Revealed>();
  const load = useCallback(() => {
    apiFetch<Checkout[]>(`/api/v1/credential-checkouts?mine=${mine}`).then(setRows, setError);
  }, [mine]);
  useEffect(load, [load]);
  const closeReveal = useCallback(() => setRevealed(undefined), []);
  if (error) return <ErrorAlert error={error} />;
  if (!rows) return <CircularProgress aria-label={t.loading} />;
  const act = (c: Checkout, action: 'reveal' | 'check-in') => {
    setActionError(undefined);
    apiFetch<Revealed | Checkout>(`/api/v1/credential-checkouts/${c.id}:${action}`, { method: 'POST' }).then((r) => {
      if (action === 'reveal') setRevealed(r as Revealed);
      load();
    }, setActionError);
  };
  return (
    <Stack spacing={2}>
      {actionError !== undefined && <ErrorAlert error={actionError} />}
      <DataTable<Checkout> title={title} rows={rows} rowKey={(c) => c.id} columns={[
        { header: t.name, cell: (c) => `${c.accountName ?? ''} @ ${c.targetName ?? ''}` },
        ...(mine ? [] : [{ header: t.checkedOutBy, cell: (c: Checkout) => c.identityName ?? '—' }]),
        { header: t.status, cell: (c) => <Chip size="small" label={c.status} color={c.status === 'ACTIVE' ? 'success' : 'default'} /> },
        { header: t.until, cell: (c) => when(c.notAfter) },
        { header: t.reveals, cell: (c) => c.revealCount },
        { header: t.justification, cell: (c) => c.reason ?? '' },
        {
          header: t.action, cell: (c) => (c.status === 'ACTIVE' ? (
            <Stack direction="row" spacing={0.5}>
              {mine && <Button size="small" variant="contained" onClick={() => act(c, 'reveal')}>{t.reveal}</Button>}
              <Button size="small" color="warning" onClick={() => act(c, 'check-in')}>{t.checkIn}</Button>
            </Stack>
          ) : null),
        },
      ]} />
      <RevealDialog revealed={revealed} onClose={closeReveal} />
    </Stack>
  );
}

export function MyCheckoutsPage() {
  const { t } = useLocale();
  return <CheckoutsTable title={t.nav.myCheckouts} />;
}

/** Vaulted privileged passwords: state, rotation, direct checkout (credential managers). */
export function VaultPage() {
  const { t } = useLocale();
  const me = useMe();
  const manager = hasPermission(me, 'credential:manage');
  const [tab, setTab] = useState(0);
  const [rows, setRows] = useState<VaultedCredential[]>();
  const [error, setError] = useState<unknown>();
  const [actionError, setActionError] = useState<unknown>();
  const [info, setInfo] = useState<string>();
  const [checkout, setCheckout] = useState<VaultedCredential>();
  const load = useCallback(() => {
    apiFetch<VaultedCredential[]>('/api/v1/vaulted-credentials').then(setRows, setError);
  }, []);
  useEffect(load, [load]);
  useEffect(() => { // follow rotations in flight
    if (!rows?.some((r) => r.rotationStatus === 'ROTATING')) return undefined;
    const id = setInterval(load, 3000);
    return () => clearInterval(id);
  }, [rows, load]);
  if (error) return <ErrorAlert error={error} />;
  if (!rows) return <CircularProgress aria-label={t.loading} />;
  const rotate = (v: VaultedCredential) => {
    setActionError(undefined);
    apiFetch(`/api/v1/vaulted-credentials/${v.accountId}:rotate`, { method: 'POST', body: JSON.stringify({ reason: 'manual rotation' }) })
      .then(() => { setInfo(t.vaultStarted); load(); }, setActionError);
  };
  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5" component="h2">{t.vaultTitle}</Typography>
        <Typography variant="body2" color="text.secondary">{t.vaultIntro}</Typography>
      </Box>
      {manager && (
        <Tabs value={tab} onChange={(_, v: number) => setTab(v)}>
          <Tab label={t.vaultTitle} />
          <Tab label={t.allCheckouts} />
        </Tabs>
      )}
      {tab === 1 ? <CheckoutsTable mine={false} title={t.allCheckouts} /> : (
        <>
          {info && <Alert severity="info" onClose={() => setInfo(undefined)}>{info}</Alert>}
          {actionError !== undefined && <ErrorAlert error={actionError} />}
          {rows.length === 0 ? <Alert severity="info">{t.noVaulted}</Alert> : (
            <DataTable<VaultedCredential> hideTitle title={t.nav.passwordVault} rows={rows} rowKey={(v) => v.accountId} columns={[
              { header: t.name, cell: (v) => v.accountName },
              { header: t.server, cell: (v) => v.targetName },
              {
                header: t.rotationStatus, cell: (v) => (
                  <Tooltip title={v.lastRotationError ?? v.rotationTrigger ?? ''}>
                    <Chip size="small" label={v.rotationStatus} color={rotationColor(v.rotationStatus)} />
                  </Tooltip>
                ),
              },
              { header: t.lastRotated, cell: (v) => when(v.lastRotatedAt) },
              { header: t.nextRotation, cell: (v) => when(v.nextRotationAt) },
              {
                header: t.checkedOutBy, cell: (v) => (v.activeCheckout
                  ? `${v.activeCheckout.identityName ?? '—'} (${t.until} ${when(v.activeCheckout.notAfter)})` : '—'),
              },
              {
                header: t.action, cell: (v) => (manager ? (
                  <Stack direction="row" spacing={0.5}>
                    <Button size="small" disabled={!!v.activeCheckout || v.rotationStatus === 'ROTATING'} onClick={() => rotate(v)}>{t.rotateNow}</Button>
                    <Button size="small" variant="outlined" disabled={!!v.activeCheckout || v.rotationStatus !== 'VERIFIED' && v.rotationStatus !== 'UNKNOWN'}
                      onClick={() => setCheckout(v)}>{t.checkOut}</Button>
                  </Stack>
                ) : null),
              },
            ]} />
          )}
        </>
      )}
      <CheckoutDialog open={!!checkout} submitLabel={t.checkOut}
        title={checkout ? format(t.checkOutTitle, { account: checkout.accountName, server: checkout.targetName }) : ''}
        onClose={() => setCheckout(undefined)}
        onSubmit={async (reason, hours) => {
          await apiFetch(`/api/v1/vaulted-credentials/${checkout?.accountId}:checkout`, { method: 'POST', body: JSON.stringify({ reason, durationHours: hours }) });
          setTab(0);
          setInfo(t.myCheckouts + ' ✓');
          load();
        }} />
    </Stack>
  );
}

/** Self-service: request a time-bound checkout of a vaulted password (approved per policy P-300). */
export function CredentialRequestsPage() {
  const { t } = useLocale();
  const [rows, setRows] = useState<RequestableCredential[]>();
  const [error, setError] = useState<unknown>();
  const [target, setTarget] = useState<RequestableCredential>();
  const [info, setInfo] = useState<string>();
  const load = useCallback(() => {
    apiFetch<RequestableCredential[]>('/api/v1/access-requests/requestable-credentials').then(setRows, setError);
  }, []);
  useEffect(load, [load]);
  if (error) return <ErrorAlert error={error} />;
  if (!rows) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={3}>
      {info && <Alert severity="success" onClose={() => setInfo(undefined)}>{info}</Alert>}
      {rows.length === 0 ? <Alert severity="info">{t.noVaulted}</Alert> : (
        <DataTable<RequestableCredential> title={t.nav.pamRequests} rows={rows} rowKey={(r) => r.accountId} columns={[
          { header: t.name, cell: (r) => r.accountName },
          { header: t.server, cell: (r) => r.targetName },
          { header: t.type, cell: (r) => r.providerType },
          { header: t.approvalsNeeded, cell: (r) => (r.allowed ? r.approvals.join(' → ') || '—' : <Chip size="small" color="error" label={r.explanation} />) },
          {
            header: t.action, cell: (r) => (r.available && r.allowed
              ? <Button size="small" variant="contained" onClick={() => setTarget(r)}>{t.requestCheckout}</Button>
              : <Chip size="small" label={r.unavailableReason ?? t.unavailable} />),
          },
        ]} />
      )}
      <CheckoutsTable title={t.myCheckouts} />
      <CheckoutDialog open={!!target} submitLabel={t.requestCheckout}
        title={target ? format(t.requestCheckoutTitle, { account: target.accountName, server: target.targetName }) : ''}
        onClose={() => setTarget(undefined)}
        onSubmit={async (reason, hours) => {
          await apiFetch('/api/v1/access-requests/credential', { method: 'POST',
            body: JSON.stringify({ accountId: target?.accountId, justification: reason, durationHours: hours }) });
          setInfo(t.checkoutRequested);
          load();
        }} />
    </Stack>
  );
}

