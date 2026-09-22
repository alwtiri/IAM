import { useState } from 'react';
import {
  Alert, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Tooltip, Typography,
} from '@mui/material';
import { submitAndWait } from '../api/operations';
import type { Account, Operation } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { DataTable, ErrorAlert, usePaged } from './common';

export const accountStatusColor = (s: string) => (s === 'ENABLED' ? 'success' : s === 'LOCKED' || s === 'EXPIRED' ? 'warning'
  : s === 'DISABLED' || s === 'ABSENT' ? 'error' : 'default');

export const operationColor = (s: string) => (s === 'SUCCESS' ? 'success' : s === 'QUEUED' || s === 'RUNNING' ? 'info'
  : s === 'UNKNOWN' || s === 'PARTIAL' ? 'warning' : 'error');

/** Shows the outcome of an operation honestly: UNKNOWN is never presented as success (G5). */
export function OperationOutcome({ op }: { op: Operation }) {
  const { t } = useLocale();
  if (op.status === 'SUCCESS') {
    return <Alert severity="success">{t.operationOk}{op.verificationSummary ? ` — ${op.verificationSummary}` : ''}</Alert>;
  }
  const severity = op.status === 'UNKNOWN' || op.status === 'PARTIAL' || op.status === 'RUNNING' || op.status === 'QUEUED' ? 'warning' : 'error';
  return (
    <Alert severity={severity}>
      <strong>{op.status}</strong>{op.errorCode ? ` — ${op.errorCode}` : ''}{op.errorMessage ? `: ${op.errorMessage}` : ''}
      {op.statusReason && !op.errorMessage ? ` — ${op.statusReason}` : ''}
    </Alert>
  );
}

type Action = 'disable' | 'enable' | 'unlock';

/** Accounts table with lifecycle actions (each change is confirmed, audited with a reason and verified by read-back). */
export function AccountsTable({ query, title, showServer = true, filter }: {
  query: string;
  title: string;
  showServer?: boolean;
  filter?: (a: Account) => boolean;
}) {
  const { t } = useLocale();
  const { items, cursor, loading, error, loadMore, reload } = usePaged<Account>(`/api/v1/accounts${query}`);
  const [pending, setPending] = useState<{ account: Account; action: Action }>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<string>();
  const [result, setResult] = useState<Operation>();
  const [actionError, setActionError] = useState<unknown>();

  const run = async (account: Account, action: Action | 'refresh', why?: string) => {
    setBusy(account.id);
    setResult(undefined);
    setActionError(undefined);
    try {
      const op = await submitAndWait(`/api/v1/accounts/${account.id}:${action}`, action === 'refresh' ? undefined : { reason: why || null });
      setResult(op);
      reload();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(undefined);
    }
  };

  if (error) {
    return <ErrorAlert error={error} />;
  }
  const rows = filter ? items.filter(filter) : items;
  const label = (a: Action) => (a === 'disable' ? t.disable : a === 'enable' ? t.enable : t.unlock);
  return (
    <Stack spacing={2}>
      {result && <OperationOutcome op={result} />}
      {actionError !== undefined && <ErrorAlert error={actionError} />}
      <DataTable<Account> title={title} rows={rows} rowKey={(a) => a.id} columns={[
        { header: t.name, cell: (a) => <Tooltip title={a.displayName ?? ''}><span>{a.name}</span></Tooltip> },
        ...(showServer ? [{ header: t.server, cell: (a: Account) => a.targetName }] : []),
        { header: t.status, cell: (a) => <Chip size="small" label={a.nativeStatus} color={accountStatusColor(a.nativeStatus)} /> },
        { header: t.privileged, cell: (a) => (a.privileged ? <Tooltip title={a.privilegeReason ?? ''}><Chip size="small" color="warning" label={t.yes} /></Tooltip> : '') },
        { header: t.lastLogin, cell: (a) => (a.lastLoginAt ? new Date(a.lastLoginAt).toLocaleString() : '—') },
        { header: t.findings, cell: (a) => a.openFindings.map((f) => <Chip key={f} size="small" variant="outlined" label={f} sx={{ me: 0.5 }} />) },
        {
          header: t.action, cell: (a) => (busy === a.id ? <CircularProgress size={20} aria-label={t.working} /> : (
            <Stack direction="row" spacing={0.5}>
              {a.nativeStatus !== 'DISABLED' && <Button size="small" color="error" onClick={() => setPending({ account: a, action: 'disable' })}>{t.disable}</Button>}
              {a.nativeStatus === 'DISABLED' && <Button size="small" onClick={() => setPending({ account: a, action: 'enable' })}>{t.enable}</Button>}
              {a.nativeStatus === 'LOCKED' && <Button size="small" onClick={() => setPending({ account: a, action: 'unlock' })}>{t.unlock}</Button>}
              <Button size="small" onClick={() => void run(a, 'refresh')}>{t.refresh}</Button>
            </Stack>
          )),
        },
      ]} footer={loading ? <CircularProgress size={24} aria-label={t.loading} /> : cursor ? <Button onClick={() => void loadMore()}>{t.loadMore}</Button> : null} />
      <Dialog open={!!pending} onClose={() => setPending(undefined)}>
        <DialogTitle>{t.confirmTitle}</DialogTitle>
        <DialogContent>
          {pending && (
            <Typography sx={{ mb: 2 }}>
              {format(t.confirmBody, { action: label(pending.action), account: pending.account.name, server: pending.account.targetName })}
            </Typography>
          )}
          <TextField fullWidth label={t.reason} value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 500 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPending(undefined)}>{t.cancel}</Button>
          <Button variant="contained" color={pending?.action === 'disable' ? 'error' : 'primary'} onClick={() => {
            const p = pending;
            setPending(undefined);
            setReason('');
            if (p) {
              void run(p.account, p.action, reason);
            }
          }}>{pending ? label(pending.action) : ''}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

export function AllAccountsPage() {
  const { t } = useLocale();
  return <AccountsTable query="?limit=100" title={t.nav.allAccounts} />;
}

export function PrivilegedAccountsPage() {
  const { t } = useLocale();
  return <AccountsTable query="?privileged=true&limit=100" title={t.nav.privilegedAccounts} />;
}

export function LinuxAccountsPage() {
  const { t } = useLocale();
  return <AccountsTable query="?limit=100" title={t.nav.linuxAccounts} filter={(a) => a.providerType === 'linux-ssh'} />;
}

export function WindowsAccountsPage() {
  const { t } = useLocale();
  return <AccountsTable query="?limit=100" title={t.nav.windowsAccounts}
    filter={(a) => a.providerType === 'windows-winrm' || a.providerType === 'active-directory'} />;
}

export function ServiceAccountsPage() {
  const { t } = useLocale();
  return <AccountsTable query="?limit=200" title={t.nav.serviceAccountsList}
    filter={(a) => a.type === 'SERVICE' || a.attributes.servicePrincipalNames !== undefined || a.attributes.interactiveLogin === 'false'} />;
}
