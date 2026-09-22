import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Box, Card, CardContent, Chip, LinearProgress, Skeleton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import type { AccountFinding, Account, Identity, Operation, Page, SystemHealth, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { operationColor } from './Accounts';
import { useMe } from '../MeContext';
import { useTokens } from '../theme';
import { Icon } from '../layout/Icons';
import type { VaultedCredential } from './Vault';

/** Count from a cursor page: exact below the page size, otherwise "N+" (the API does not count on purpose). */
function countLabel(p?: Page<unknown>): string | undefined {
  if (!p) return undefined;
  return p.nextCursor ? `${p.items.length}+` : String(p.items.length);
}

async function tryFetch<T>(path: string): Promise<T | undefined> {
  try {
    return await apiFetch<T>(path);
  } catch {
    return undefined; // no permission or dependency down: the card is simply hidden
  }
}

/** KPI card in the shadcn "section cards" style: muted label, large value, icon badge, footnote. */
function Kpi({ label, value, icon, hint, tone }: { label: string; value?: string; icon: string; hint?: string; tone?: 'warn' | 'bad' }) {
  const k = useTokens();
  return (
    <Card>
      <CardContent sx={{ p: 2.5, '&:last-child': { pb: 2.5 } }}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
          <Typography sx={{ fontSize: 13, color: 'text.secondary', fontWeight: 500 }}>{label}</Typography>
          <Box sx={{ color: tone === 'bad' ? 'error.main' : tone === 'warn' ? 'warning.main' : k.mutedForeground }}><Icon name={icon} /></Box>
        </Stack>
        {value === undefined ? <Skeleton width={70} height={40} /> : (
          <Typography sx={{ fontSize: 30, fontWeight: 700, letterSpacing: '-0.03em', mt: 0.75, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
        )}
        {hint && <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>{hint}</Typography>}
      </CardContent>
    </Card>
  );
}

function Panel({ title, description, children, action }: { title: string; description?: string; children: ReactNode; action?: ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" sx={{ mb: 2 }}>
          <Box>
            <Typography sx={{ fontWeight: 600, fontSize: 16, letterSpacing: '-0.01em' }}>{title}</Typography>
            {description && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{description}</Typography>}
          </Box>
          {action}
        </Stack>
        {children}
      </CardContent>
    </Card>
  );
}

const healthColor = (s: string) => (s === 'HEALTHY' ? 'success' : s === 'DEGRADED' ? 'warning' : s === 'UNKNOWN' ? 'default' : 'error');
const PROBLEM = ['FAILED', 'UNKNOWN', 'PARTIAL', 'TIMEOUT'];

/** Stacked area chart of operations per day (succeeded vs. needing attention), drawn as SVG like the shadcn area chart. */
function OperationsChart({ operations, days = 14 }: { operations: Operation[]; days?: number }) {
  const k = useTokens();
  const { locale } = useLocale();
  const buckets = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const list = Array.from({ length: days }, (_, i) => {
      const d = new Date(today);
      d.setDate(today.getDate() - (days - 1 - i));
      return { day: d, ok: 0, bad: 0 };
    });
    for (const o of operations) {
      if (!o.createdAt) continue;
      const d = new Date(o.createdAt);
      d.setHours(0, 0, 0, 0);
      const b = list.find((x) => x.day.getTime() === d.getTime());
      if (!b) continue;
      if (PROBLEM.includes(o.status)) b.bad += 1; else b.ok += 1;
    }
    return list;
  }, [operations, days]);
  const W = 600;
  const H = 180;
  const max = Math.max(4, ...buckets.map((b) => b.ok + b.bad));
  const x = (i: number) => (i / (buckets.length - 1)) * W;
  const y = (v: number) => H - (v / max) * (H - 12);
  const line = (vals: number[]) => vals.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const totals = buckets.map((b) => b.ok + b.bad);
  const bads = buckets.map((b) => b.bad);
  const area = (top: number[], bottom: number[]) => `${line(top)} ${bottom.map((_, j) => {
    const i = bottom.length - 1 - j;
    return `L${x(i).toFixed(1)},${y(bottom[i] ?? 0).toFixed(1)}`;
  }).join(' ')} Z`;
  const zeros = buckets.map(() => 0);
  const [ok, bad] = [k.chart[1], k.chart[0]];
  return (
    <Box>
      <Box component="svg" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" sx={{ width: '100%', height: 200, display: 'block' }} role="img"
        aria-label="operations per day">
        <defs>
          <linearGradient id="gOk" x1="0" x2="0" y1="0" y2="1"><stop offset="5%" stopColor={ok} stopOpacity={0.45} /><stop offset="95%" stopColor={ok} stopOpacity={0.05} /></linearGradient>
          <linearGradient id="gBad" x1="0" x2="0" y1="0" y2="1"><stop offset="5%" stopColor={bad} stopOpacity={0.6} /><stop offset="95%" stopColor={bad} stopOpacity={0.08} /></linearGradient>
        </defs>
        {[0.25, 0.5, 0.75].map((f) => <line key={f} x1={0} x2={W} y1={H * f} y2={H * f} stroke={k.border} strokeDasharray="3 4" vectorEffect="non-scaling-stroke" />)}
        <path d={area(totals, bads)} fill="url(#gOk)" />
        <path d={line(totals)} fill="none" stroke={ok} strokeWidth={2} vectorEffect="non-scaling-stroke" />
        <path d={area(bads, zeros)} fill="url(#gBad)" />
        <path d={line(bads)} fill="none" stroke={bad} strokeWidth={2} vectorEffect="non-scaling-stroke" />
      </Box>
      <Stack direction="row" justifyContent="space-between" sx={{ mt: 1 }}>
        {buckets.filter((_, i) => i % 2 === 0 || i === buckets.length - 1).map((b) => (
          <Typography key={b.day.toISOString()} sx={{ fontSize: 11, color: 'text.secondary' }}>
            {b.day.toLocaleDateString(locale === 'ar' ? 'ar' : 'en', { month: 'short', day: 'numeric' })}
          </Typography>
        ))}
      </Stack>
      <Stack direction="row" spacing={2} sx={{ mt: 1.5 }}>
        {[[ok, 'SUCCESS'], [bad, 'FAILED / UNKNOWN']].map(([c, l]) => (
          <Stack key={l} direction="row" spacing={0.75} alignItems="center">
            <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: c }} />
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{l}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  );
}

export function DashboardPage() {
  const { t } = useLocale();
  const displayName = useMe()?.displayName;
  const [data, setData] = useState<{
    linux?: Page<Target>; windows?: Page<Target>; databases?: Page<Target>; accounts?: Page<Account>; privileged?: Page<Account>;
    findings?: Page<AccountFinding>; identities?: Page<Identity>; operations?: Page<Operation>; health?: SystemHealth; approvals?: unknown[];
    vault?: VaultedCredential[]; loaded: boolean;
  }>({ loaded: false });

  useEffect(() => {
    void Promise.all([
      tryFetch<Page<Target>>('/api/v1/targets?type=LINUX_SERVER&limit=200'),
      tryFetch<Page<Target>>('/api/v1/targets?type=WINDOWS_SERVER&limit=200'),
      tryFetch<Page<Target>>('/api/v1/targets?type=DATABASE&limit=200'),
      tryFetch<Page<Account>>('/api/v1/accounts?limit=200'),
      tryFetch<Page<Account>>('/api/v1/accounts?privileged=true&limit=200'),
      tryFetch<Page<AccountFinding>>('/api/v1/account-findings?limit=200'),
      tryFetch<Page<Identity>>('/api/v1/identities?limit=200'),
      tryFetch<Page<Operation>>('/api/v1/operations?limit=200'),
      tryFetch<SystemHealth>('/api/v1/system/health'),
      tryFetch<unknown[]>('/api/v1/approvals'),
      tryFetch<VaultedCredential[]>('/api/v1/vaulted-credentials'),
    ]).then(([linux, windows, databases, accounts, privileged, findings, identities, operations, health, approvals, vault]) =>
      setData({ linux, windows, databases, accounts, privileged, findings, identities, operations, health, approvals, vault, loaded: true }));
  }, []);

  const servers = data.linux || data.windows
    ? String((data.linux?.items.length ?? 0) + (data.windows?.items.length ?? 0)) + (data.linux?.nextCursor || data.windows?.nextCursor ? '+' : '')
    : undefined;
  const ops = data.operations?.items ?? [];
  const attention = data.operations ? ops.filter((o) => PROBLEM.includes(o.status)).length : undefined;
  const byType = new Map<string, number>();
  data.findings?.items.forEach((f) => byType.set(f.type, (byType.get(f.type) ?? 0) + 1));
  const maxFinding = Math.max(1, ...byType.values());
  const show = (v: string | undefined) => (data.loaded ? v ?? '—' : undefined);
  const vaulted = data.vault?.length;
  const checkedOut = data.vault?.filter((v) => v.activeCheckout).length;
  const unverified = data.vault?.filter((v) => v.rotationStatus !== 'VERIFIED').length;

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-end' }} spacing={1}>
        <Box>
          <Typography variant="h4" component="h2">{format(t.welcome, { name: displayName ?? '' })}</Typography>
          <Typography color="text.secondary">{t.dashboardSubtitle}</Typography>
        </Box>
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{new Date().toLocaleDateString(undefined, { dateStyle: 'full' })}</Typography>
      </Stack>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' } }}>
        <Kpi label={t.kpiServers} value={show(servers)} icon="assets" hint={data.databases ? `+ ${countLabel(data.databases)} DB` : undefined} />
        <Kpi label={t.kpiAccounts} value={show(countLabel(data.accounts))} icon="accounts" />
        <Kpi label={t.kpiPrivileged} value={show(countLabel(data.privileged))} icon="privilegedAccess" tone="warn"
          hint={vaulted !== undefined ? `${vaulted} ${t.nav.passwordVault}` : undefined} />
        <Kpi label={t.kpiFindings} value={show(countLabel(data.findings))} icon="auditCompliance" tone="bad" />
        <Kpi label={t.kpiUsers} value={show(countLabel(data.identities))} icon="identityAccess" />
        <Kpi label={t.kpiPendingApprovals} value={show(data.approvals ? String(data.approvals.length) : undefined)} icon="reports" />
        <Kpi label={t.kpiOperations} value={show(attention === undefined ? undefined : String(attention))} icon="emergency" tone={attention ? 'bad' : undefined} />
        <Kpi label={t.kpiCheckedOut} value={show(checkedOut === undefined ? undefined : String(checkedOut))} icon="shieldKey"
          hint={unverified ? `${unverified} ≠ VERIFIED` : undefined} tone={unverified ? 'warn' : undefined} />
      </Box>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Panel title={t.recentOperations} description={t.last14Days}>
          {!data.loaded ? <LinearProgress /> : <OperationsChart operations={ops} />}
        </Panel>
        <Stack spacing={2}>
          <Panel title={t.healthSummary}>
            {!data.loaded ? <LinearProgress /> : !data.health ? <Typography color="text.secondary">—</Typography> : (
              <Stack spacing={1.5}>
                <Chip label={data.health.status} color={healthColor(data.health.status)} sx={{ alignSelf: 'flex-start' }} />
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75 }}>
                  {data.health.components.map((c) => (
                    <Chip key={c.component} size="small" variant="outlined" label={c.component} color={healthColor(c.status)} title={c.reason ?? c.status} />
                  ))}
                </Box>
              </Stack>
            )}
          </Panel>
          <Panel title={t.findingsByType}>
            {!data.loaded ? <LinearProgress /> : byType.size === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
              <Stack spacing={1.2}>
                {[...byType.entries()].sort((a, b) => b[1] - a[1]).map(([type, n]) => (
                  <Box key={type}>
                    <Stack direction="row" justifyContent="space-between">
                      <Typography sx={{ fontSize: 13 }}>{type}</Typography>
                      <Typography sx={{ fontSize: 13, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{n}</Typography>
                    </Stack>
                    <LinearProgress variant="determinate" value={(n / maxFinding) * 100} color="error" sx={{ height: 6 }} />
                  </Box>
                ))}
              </Stack>
            )}
          </Panel>
        </Stack>
      </Box>

      <Panel title={t.recentOperations}>
        {!data.loaded ? <LinearProgress /> : ops.length === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t.status}</TableCell>
                  <TableCell>{t.type}</TableCell>
                  <TableCell>{t.result}</TableCell>
                  <TableCell>{t.time}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {ops.slice(0, 10).map((o) => (
                  <TableRow key={o.id}>
                    <TableCell><Chip size="small" label={o.status} color={operationColor(o.status)} variant="outlined" /></TableCell>
                    <TableCell sx={{ fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{o.type}</TableCell>
                    <TableCell sx={{ color: 'text.secondary', maxWidth: 380, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {o.errorCode ?? o.verificationSummary ?? ''}
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>{o.createdAt ? new Date(o.createdAt).toLocaleString() : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Panel>
    </Stack>
  );
}
