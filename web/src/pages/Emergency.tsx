import { useCallback, useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import { hasPermission } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { useMe } from '../MeContext';
import { DataTable, ErrorAlert } from './common';
import { rotationColor, type Checkout, type VaultedCredential } from './Vault';

interface EmergencyUse {
  checkout: Checkout;
  reviewStatus: string;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
}

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

/** Emergency accounts with break-glass access (no approval, 4 hours, always reviewed afterwards). */
export function EmergencyAccountsPage() {
  const { t } = useLocale();
  const allowed = hasPermission(useMe(), 'emergency:access');
  const [rows, setRows] = useState<VaultedCredential[]>();
  const [error, setError] = useState<unknown>();
  const [target, setTarget] = useState<VaultedCredential>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<unknown>();
  const [info, setInfo] = useState<string>();
  const load = useCallback(() => {
    apiFetch<VaultedCredential[]>('/api/v1/vaulted-credentials').then((v) => setRows(v.filter((x) => x.emergency)), setError);
  }, []);
  useEffect(load, [load]);
  if (error) return <ErrorAlert error={error} />;
  if (!rows) return <CircularProgress aria-label={t.loading} />;
  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5" component="h2">{t.nav.emergencyAccountsList}</Typography>
        <Typography variant="body2" color="text.secondary">{t.breakGlassBody}</Typography>
      </Box>
      {info && <Alert severity="warning" onClose={() => setInfo(undefined)}>{info}</Alert>}
      {rows.length === 0 ? <Alert severity="info">{t.noEmergencyAccounts}</Alert> : (
        <DataTable<VaultedCredential> hideTitle title={t.nav.emergencyAccountsList} rows={rows} rowKey={(v) => v.accountId} columns={[
          { header: t.name, cell: (v) => <Stack direction="row" spacing={1} alignItems="center"><span>{v.accountName}</span><Chip size="small" color="error" label={t.emergencyTag} /></Stack> },
          { header: t.server, cell: (v) => v.targetName },
          { header: t.rotationStatus, cell: (v) => <Chip size="small" label={v.rotationStatus} color={rotationColor(v.rotationStatus)} /> },
          { header: t.checkedOutBy, cell: (v) => (v.activeCheckout ? `${v.activeCheckout.identityName ?? '—'} (${t.until} ${when(v.activeCheckout.notAfter)})` : '—') },
          {
            header: t.action, cell: (v) => (allowed ? (
              <Button size="small" variant="contained" color="error" disabled={!!v.activeCheckout || (v.rotationStatus !== 'VERIFIED' && v.rotationStatus !== 'UNKNOWN')}
                onClick={() => { setTarget(v); setReason(''); setDialogError(undefined); }}>{t.breakGlass}</Button>
            ) : null),
          },
        ]} />
      )}
      <Dialog open={!!target} onClose={() => setTarget(undefined)} fullWidth maxWidth="sm">
        <DialogTitle>{target ? format(t.breakGlassTitle, { account: target.accountName, server: target.targetName }) : ''}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {dialogError !== undefined && <ErrorAlert error={dialogError} />}
            <Alert severity="error">{t.breakGlassBody}</Alert>
            <TextField required multiline minRows={3} label={t.reasonCol} value={reason} onChange={(e) => setReason(e.target.value)}
              slotProps={{ htmlInput: { maxLength: 500 } }} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTarget(undefined)}>{t.cancel}</Button>
          <Button variant="contained" color="error" disabled={busy || reason.trim().length < 10} onClick={() => {
            setBusy(true);
            apiFetch(`/api/v1/vaulted-credentials/${target?.accountId}:break-glass`, { method: 'POST', body: JSON.stringify({ reason: reason.trim() }) })
              .then(() => { setTarget(undefined); setInfo(t.breakGlassDone); load(); }, setDialogError).finally(() => setBusy(false));
          }}>{t.breakGlass}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/** Emergency uses: active ones, or all with their review (second-person review of every break-glass). */
export function EmergencyUsesPage({ activeOnly = false }: { activeOnly?: boolean }) {
  const { t } = useLocale();
  const canReview = hasPermission(useMe(), 'emergency:review');
  const [rows, setRows] = useState<EmergencyUse[]>();
  const [error, setError] = useState<unknown>();
  const [reviewing, setReviewing] = useState<EmergencyUse>();
  const [note, setNote] = useState('');
  const [dialogError, setDialogError] = useState<unknown>();
  const load = useCallback(() => {
    apiFetch<EmergencyUse[]>('/api/v1/emergency-uses').then((u) => setRows(activeOnly ? u.filter((x) => x.checkout.status === 'ACTIVE') : u), setError);
  }, [activeOnly]);
  useEffect(load, [load]);
  if (error) return <ErrorAlert error={error} />;
  if (!rows) return <CircularProgress aria-label={t.loading} />;
  const title = activeOnly ? t.nav.activeEmergencies : t.nav.emergencyAudit;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h2">{title}</Typography>
      {rows.length === 0 ? <Alert severity="info">{t.noEmergencyUses}</Alert> : (
        <DataTable<EmergencyUse> hideTitle title={title} rows={rows} rowKey={(u) => u.checkout.id} columns={[
          { header: t.name, cell: (u) => `${u.checkout.accountName ?? ''} @ ${u.checkout.targetName ?? ''}` },
          { header: t.usedBy, cell: (u) => u.checkout.identityName ?? '—' },
          { header: t.started, cell: (u) => when(u.checkout.startedAt) },
          { header: t.status, cell: (u) => <Chip size="small" label={u.checkout.status} color={u.checkout.status === 'ACTIVE' ? 'error' : 'default'} /> },
          { header: t.reveals, cell: (u) => u.checkout.revealCount },
          { header: t.reasonCol, cell: (u) => u.checkout.reason?.replace(/^BREAK-GLASS: /, '') ?? '' },
          {
            header: t.review, cell: (u) => (u.reviewStatus === 'REVIEWED'
              ? <Chip size="small" color="success" label={`${t.reviewed}: ${u.reviewedByName ?? ''}`} title={u.reviewNote ?? ''} />
              : canReview ? <Button size="small" variant="outlined" onClick={() => { setReviewing(u); setNote(''); setDialogError(undefined); }}>{t.review}</Button>
                : <Chip size="small" color="warning" label={t.pendingReview} />),
          },
        ]} />
      )}
      <Dialog open={!!reviewing} onClose={() => setReviewing(undefined)} fullWidth maxWidth="sm">
        <DialogTitle>{t.reviewTitle}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {dialogError !== undefined && <ErrorAlert error={dialogError} />}
            {reviewing && <Typography color="text.secondary">{reviewing.checkout.accountName} @ {reviewing.checkout.targetName} — {reviewing.checkout.identityName}: {reviewing.checkout.reason}</Typography>}
            <TextField required multiline minRows={2} label={t.reviewNote} value={note} onChange={(e) => setNote(e.target.value)} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setReviewing(undefined)}>{t.cancel}</Button>
          <Button variant="contained" disabled={!note.trim()} onClick={() => {
            apiFetch(`/api/v1/emergency-uses/${reviewing?.checkout.id}:review`, { method: 'POST', body: JSON.stringify({ note: note.trim() }) })
              .then(() => { setReviewing(undefined); load(); }, setDialogError);
          }}>{t.review}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

export function ActiveEmergenciesPage() {
  return <EmergencyUsesPage activeOnly />;
}
