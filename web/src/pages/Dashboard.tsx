import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link as RouterLink } from 'react-router';
import {
  Box, Button, Card, CardContent, Chip, LinearProgress, Skeleton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { apiFetch } from '../api/client';
import type { AccountFinding, Account, Identity, Operation, Page, SystemHealth, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { operationColor } from './Accounts';
import { useMe } from '../MeContext';
import { TONES, type Tone, useColorMode, useTokens } from '../theme';
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
    return undefined; // no permission or dependency down: the card shows "—"
  }
}

function Kpi({ label, value, icon, tone, hint, to }: { label: string; value?: string; icon: string; tone: Tone; hint?: string; to?: string }) {
  const c = TONES[tone];
  const body = (
    <CardContent sx={{ p: 2.5, '&:last-child': { pb: 2.5 } }}>
      <Stack direction="row" spacing={2} alignItems="center">
        <Box sx={{ width: 46, height: 46, borderRadius: 3, bgcolor: c.bg, color: c.fg, display: 'grid', placeItems: 'center', flexShrink: 0 }}>
          <Icon name={icon} size={22} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 13, color: 'text.secondary', fontWeight: 500 }}>{label}</Typography>
          {value === undefined ? <Skeleton width={56} height={36} /> : (
            <Typography sx={{ fontSize: 26, fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1.25, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
          )}
        </Box>
      </Stack>
      {hint && <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 1.25 }}>{hint}</Typography>}
    </CardContent>
  );
  return (
    <Card sx={{ transition: 'transform .15s, box-shadow .15s', '&:hover': to ? { transform: 'translateY(-2px)', boxShadow: '0 8px 24px rgba(16,24,40,0.08)' } : {} }}>
      {to ? <Box component={RouterLink} to={to} sx={{ color: 'inherit', textDecoration: 'none', display: 'block' }}>{body}</Box> : body}
    </Card>
  );
}

function Panel({ title, subtitle, action, children }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" sx={{ mb: 2.5 }}>
          <Box>
            <Typography sx={{ fontWeight: 700, fontSize: 16, letterSpacing: '-0.01em' }}>{title}</Typography>
            {subtitle && <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.25 }}>{subtitle}</Typography>}
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

/** Operations per day for 14 days: succeeded vs. needing attention (smooth area chart, SVG). */
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
  const W = 640;
  const H = 200;
  const max = Math.max(4, ...buckets.map((b) => Math.max(b.ok, b.bad)));
  const x = (i: number) => (i / (buckets.length - 1)) * W;
  const y = (v: number) => H - 8 - (v / max) * (H - 24);
  /** Catmull-Rom → cubic Bézier for a smooth line through all points. */
  const smooth = (vals: number[]) => vals.map((v, i) => {
    if (i === 0) return `M${x(0)},${y(v)}`;
    const p0 = vals[i - 2] ?? vals[i - 1] ?? v;
    const p1 = vals[i - 1] ?? v;
    const p2 = v;
    const p3 = vals[i + 1] ?? v;
    const c1x = x(i - 1) + (x(i) - x(Math.max(0, i - 2))) / 6;
    const c1y = y(p1) + (y(p2) - y(p0)) / 6;
    const c2x = x(i) - (x(Math.min(vals.length - 1, i + 1)) - x(i - 1)) / 6;
    const c2y = y(p2) - (y(p3) - y(p1)) / 6;
    return `C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${x(i).toFixed(1)},${y(p2).toFixed(1)}`;
  }).join(' ');
  const area = (vals: number[]) => `${smooth(vals)} L${W},${H} L0,${H} Z`;
  const ok = buckets.map((b) => b.ok);
  const bad = buckets.map((b) => b.bad);
  const [okColor, badColor] = [k.chart[1], k.chart[0]];
  const total = ok.reduce((a, b) => a + b, 0);
  const totalBad = bad.reduce((a, b) => a + b, 0);
  return (
    <Box>
      <Stack direction="row" spacing={4} sx={{ mb: 2 }}>
        {[[okColor, 'SUCCESS', total], [badColor, 'FAILED / UNKNOWN', totalBad]].map(([c, l, n]) => (
          <Box key={String(l)}>
            <Stack direction="row" spacing={0.75} alignItems="center">
              <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: String(c) }} />
              <Typography sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 500 }}>{l}</Typography>
            </Stack>
            <Typography sx={{ fontSize: 22, fontWeight: 800, letterSpacing: '-0.02em' }}>{n}</Typography>
          </Box>
        ))}
      </Stack>
      <Box component="svg" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" sx={{ width: '100%', height: 220, display: 'block', overflow: 'visible' }}
        role="img" aria-label="operations per day">
        <defs>
          <linearGradient id="gOk" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor={okColor} stopOpacity={0.35} /><stop offset="100%" stopColor={okColor} stopOpacity={0} /></linearGradient>
          <linearGradient id="gBad" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor={badColor} stopOpacity={0.3} /><stop offset="100%" stopColor={badColor} stopOpacity={0} /></linearGradient>
        </defs>
        {[0, 0.25, 0.5, 0.75].map((f) => (
          <line key={f} x1={0} x2={W} y1={8 + (H - 16) * f} y2={8 + (H - 16) * f} stroke={k.border} strokeDasharray="4 6" vectorEffect="non-scaling-stroke" />
        ))}
        <path d={area(ok)} fill="url(#gOk)" />
        <path d={smooth(ok)} fill="none" stroke={okColor} strokeWidth={2.5} vectorEffect="non-scaling-stroke" strokeLinecap="round" />
        <path d={area(bad)} fill="url(#gBad)" />
        <path d={smooth(bad)} fill="none" stroke={badColor} strokeWidth={2.5} vectorEffect="non-scaling-stroke" strokeLinecap="round" />
      </Box>
      <Stack direction="row" justifyContent="space-between" sx={{ mt: 1 }}>
        {buckets.filter((_, i) => i % 2 === 0 || i === buckets.length - 1).map((b) => (
          <Typography key={b.day.toISOString()} sx={{ fontSize: 11, color: 'text.secondary' }}>
            {b.day.toLocaleDateString(locale === 'ar' ? 'ar' : 'en', { month: 'short', day: 'numeric' })}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}

/** Donut of account composition (SVG). */
function Donut({ parts, center }: { parts: { label: string; value: number; color: string }[]; center: string }) {
  const k = useTokens();
  const total = Math.max(1, parts.reduce((a, p) => a + p.value, 0));
  const r = 60;
  const c = 2 * Math.PI * r;
  let offset = 0;
  return (
    <Stack direction={{ xs: 'column', sm: 'row', lg: 'column', xl: 'row' }} spacing={3} alignItems="center">
      <Box sx={{ position: 'relative', width: 160, height: 160, flexShrink: 0 }}>
        <svg viewBox="0 0 160 160" width={160} height={160} role="img" aria-label="accounts composition">
          <circle cx={80} cy={80} r={r} fill="none" stroke={k.muted} strokeWidth={18} />
          {parts.map((p) => {
            const len = (p.value / total) * c;
            const el = (
              <circle key={p.label} cx={80} cy={80} r={r} fill="none" stroke={p.color} strokeWidth={18} strokeDasharray={`${len} ${c - len}`}
                strokeDashoffset={-offset} transform="rotate(-90 80 80)" strokeLinecap="butt" />
            );
            offset += len;
            return el;
          })}
        </svg>
        <Box sx={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', textAlign: 'center' }}>
          <Box>
            <Typography sx={{ fontSize: 24, fontWeight: 800, lineHeight: 1 }}>{center}</Typography>
          </Box>
        </Box>
      </Box>
      <Stack spacing={1.25} sx={{ width: '100%' }}>
        {parts.map((p) => (
          <Stack key={p.label} direction="row" alignItems="center" spacing={1}>
            <Box sx={{ width: 10, height: 10, borderRadius: 1, bgcolor: p.color }} />
            <Typography sx={{ fontSize: 13, flexGrow: 1 }}>{p.label}</Typography>
            <Typography sx={{ fontSize: 13, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{p.value}</Typography>
          </Stack>
        ))}
      </Stack>
    </Stack>
  );
}

export function DashboardPage() {
  const { t } = useLocale();
  const { mode } = useColorMode();
  const k = useTokens();
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
  const vaulted = data.vault?.length ?? 0;
  const checkedOut = data.vault?.filter((v) => v.activeCheckout).length;
  const privilegedCount = data.privileged?.items.length ?? 0;
  const accountCount = data.accounts?.items.length ?? 0;
  const healthy = data.health?.status === 'HEALTHY';

  return (
    <Stack spacing={3}>
      {/* Hero */}
      <Box sx={{
        position: 'relative', overflow: 'hidden', borderRadius: 4, p: { xs: 3, md: 4 }, color: '#fff',
        background: mode === 'light'
          ? 'linear-gradient(120deg, #312e81 0%, #4f46e5 45%, #0ea5e9 100%)'
          : 'linear-gradient(120deg, #1e1b4b 0%, #3730a3 50%, #0369a1 100%)',
        boxShadow: '0 10px 30px rgba(79,70,229,0.25)',
      }}>
        <Box sx={{ position: 'absolute', insetInlineEnd: -60, top: -60, width: 260, height: 260, borderRadius: '50%', bgcolor: 'rgba(255,255,255,0.08)' }} />
        <Box sx={{ position: 'absolute', insetInlineEnd: 120, bottom: -90, width: 200, height: 200, borderRadius: '50%', bgcolor: 'rgba(255,255,255,0.06)' }} />
        <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'center' }} spacing={3} sx={{ position: 'relative' }}>
          <Box>
            <Typography sx={{ fontSize: 13, opacity: 0.8, fontWeight: 500 }}>{new Date().toLocaleDateString(undefined, { dateStyle: 'full' })}</Typography>
            <Typography variant="h4" component="h2" sx={{ color: '#fff', mt: 0.5 }}>{format(t.welcome, { name: displayName ?? '' })}</Typography>
            <Typography sx={{ opacity: 0.85, mt: 0.5 }}>{t.dashboardSubtitle}</Typography>
            {data.health && (
              <Stack direction="row" spacing={1} alignItems="center" sx={{
                mt: 2, display: 'inline-flex', px: 1.5, py: 0.6, borderRadius: 99, bgcolor: 'rgba(255,255,255,0.14)', border: '1px solid rgba(255,255,255,0.2)',
              }}>
                <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: healthy ? '#34d399' : '#fbbf24', boxShadow: `0 0 0 4px ${healthy ? 'rgba(52,211,153,0.25)' : 'rgba(251,191,36,0.25)'}` }} />
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{healthy ? t.allSystemsOk : t.systemsDegraded}</Typography>
              </Stack>
            )}
          </Box>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
            {[
              { to: '/assets/servers', label: t.addServerAction, icon: 'plus' },
              { to: '/pam/vault', label: t.vaultAction, icon: 'privilegedAccess' },
              { to: '/identity/requests', label: t.newRequest, icon: 'arrowRight' },
            ].map((a) => (
              <Button key={a.to} component={RouterLink} to={a.to} startIcon={<Icon name={a.icon} size={16} />}
                sx={{ color: '#fff', bgcolor: 'rgba(255,255,255,0.14)', border: '1px solid rgba(255,255,255,0.22)', height: 40, px: 2, backdropFilter: 'blur(4px)',
                  '&:hover': { bgcolor: 'rgba(255,255,255,0.24)' } }}>
                {a.label}
              </Button>
            ))}
          </Stack>
        </Stack>
      </Box>

      {/* KPIs */}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(4, 1fr)' } }}>
        <Kpi label={t.kpiServers} value={show(servers)} icon="assets" tone="indigo" to="/assets/servers"
          hint={data.databases ? `+ ${countLabel(data.databases)} ${t.nav.databases}` : undefined} />
        <Kpi label={t.kpiAccounts} value={show(countLabel(data.accounts))} icon="accounts" tone="sky" to="/accounts/all" />
        <Kpi label={t.kpiPrivileged} value={show(countLabel(data.privileged))} icon="privilegedAccess" tone="amber" to="/accounts/privileged"
          hint={data.vault ? `${vaulted} ${t.vaultedAccounts}` : undefined} />
        <Kpi label={t.kpiFindings} value={show(countLabel(data.findings))} icon="auditCompliance" tone="rose" to="/accounts/privileged" />
        <Kpi label={t.kpiUsers} value={show(countLabel(data.identities))} icon="identityAccess" tone="emerald" to="/identity/users" />
        <Kpi label={t.kpiPendingApprovals} value={show(data.approvals ? String(data.approvals.length) : undefined)} icon="bell" tone="violet" to="/identity/approvals" />
        <Kpi label={t.kpiOperations} value={show(attention === undefined ? undefined : String(attention))} icon="activity" tone={attention ? 'rose' : 'slate'} />
        <Kpi label={t.kpiCheckedOut} value={show(checkedOut === undefined ? undefined : String(checkedOut))} icon="shieldKey" tone="indigo" to="/pam/vault" />
      </Box>

      {/* Chart + composition */}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Panel title={t.recentOperations} subtitle={t.last14Days}>
          {!data.loaded ? <LinearProgress /> : <OperationsChart operations={ops} />}
        </Panel>
        <Panel title={t.accountsComposition} subtitle={t.nav.privilegedAccounts}>
          {!data.loaded ? <LinearProgress /> : (
            <Donut center={countLabel(data.accounts) ?? '—'} parts={[
              { label: t.standardAccounts, value: Math.max(0, accountCount - privilegedCount), color: k.chart[2] },
              { label: t.kpiPrivileged, value: Math.max(0, privilegedCount - vaulted), color: k.chart[3] },
              { label: t.vaultedAccounts, value: vaulted, color: k.chart[1] },
            ]} />
          )}
        </Panel>
      </Box>

      {/* Table + health + findings */}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Panel title={t.recentOperations} action={<Button component={RouterLink} to="/audit/logs" size="small" endIcon={<Icon name="arrowRight" size={14} />}>{t.viewAll}</Button>}>
          {!data.loaded ? <LinearProgress /> : ops.length === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
            <TableContainer sx={{ border: 0 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t.type}</TableCell>
                    <TableCell>{t.status}</TableCell>
                    <TableCell>{t.result}</TableCell>
                    <TableCell align="right">{t.time}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {ops.slice(0, 8).map((o) => (
                    <TableRow key={o.id}>
                      <TableCell sx={{ fontWeight: 600, fontSize: 13 }}>{o.type}</TableCell>
                      <TableCell><Chip size="small" label={o.status} color={operationColor(o.status)} /></TableCell>
                      <TableCell sx={{ color: 'text.secondary', maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {o.errorCode ?? o.verificationSummary ?? ''}
                      </TableCell>
                      <TableCell align="right" sx={{ color: 'text.secondary', whiteSpace: 'nowrap', fontSize: 13 }}>
                        {o.createdAt ? new Date(o.createdAt).toLocaleString() : ''}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Panel>
        <Stack spacing={2}>
          <Panel title={t.healthSummary} action={data.health ? <Chip size="small" label={data.health.status} color={healthColor(data.health.status)} /> : undefined}>
            {!data.loaded ? <LinearProgress /> : !data.health ? <Typography color="text.secondary">—</Typography> : (
              <Stack spacing={1}>
                {data.health.components.map((c) => (
                  <Stack key={c.component} direction="row" alignItems="center" spacing={1.25} title={c.reason ?? c.status}>
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: c.status === 'HEALTHY' ? 'success.main' : c.status === 'DEGRADED' ? 'warning.main' : 'error.main' }} />
                    <Typography sx={{ fontSize: 13, flexGrow: 1 }}>{c.component}</Typography>
                    {c.status !== 'HEALTHY' && <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{c.reason ?? ''}</Typography>}
                  </Stack>
                ))}
              </Stack>
            )}
          </Panel>
          <Panel title={t.findingsByType}>
            {!data.loaded ? <LinearProgress /> : byType.size === 0 ? <Typography color="text.secondary">{t.noData}</Typography> : (
              <Stack spacing={1.5}>
                {[...byType.entries()].sort((a, b) => b[1] - a[1]).map(([type, n]) => (
                  <Box key={type}>
                    <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                      <Typography sx={{ fontSize: 13 }}>{type}</Typography>
                      <Typography sx={{ fontSize: 13, fontWeight: 700 }}>{n}</Typography>
                    </Stack>
                    <LinearProgress variant="determinate" value={(n / maxFinding) * 100} color="error" sx={{ height: 6 }} />
                  </Box>
                ))}
              </Stack>
            )}
          </Panel>
        </Stack>
      </Box>
    </Stack>
  );
}
