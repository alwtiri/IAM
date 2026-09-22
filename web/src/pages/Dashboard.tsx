import { useEffect, useState, type ReactNode } from 'react';
import { Box, Card, CardContent, Chip, LinearProgress, Skeleton, Stack, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import type { AccountFinding, Account, Identity, Operation, Page, SystemHealth, Target } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { format } from '../i18n/messages';
import { operationColor } from './Accounts';
import { useMe } from '../MeContext';

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

function Kpi({ label, value, accent, hint }: { label: string; value?: string; accent: string; hint?: string }) {
  return (
    <Card sx={{ position: 'relative', overflow: 'hidden' }}>
      <Box sx={{ position: 'absolute', insetInlineStart: 0, top: 0, bottom: 0, width: 4, bgcolor: accent }} />
      <CardContent>
        <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 600 }}>{label}</Typography>
        {value === undefined ? <Skeleton width={60} height={44} /> : (
          <Typography sx={{ fontSize: 34, fontWeight: 800, letterSpacing: '-0.03em', mt: 0.5 }}>{value}</Typography>
        )}
        {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
      </CardContent>
    </Card>
  );
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 2 }}>{title}</Typography>
        {children}
      </CardContent>
    </Card>
  );
}

const healthColor = (s: string) => (s === 'HEALTHY' ? 'success' : s === 'DEGRADED' ? 'warning' : s === 'UNKNOWN' ? 'default' : 'error');

export function DashboardPage() {
  const { t } = useLocale();
  const displayName = useMe()?.displayName;
  const [data, setData] = useState<{
    linux?: Page<Target>; windows?: Page<Target>; accounts?: Page<Account>; privileged?: Page<Account>;
    findings?: Page<AccountFinding>; identities?: Page<Identity>; operations?: Page<Operation>; health?: SystemHealth; loaded: boolean;
  }>({ loaded: false });

  useEffect(() => {
    void Promise.all([
      tryFetch<Page<Target>>('/api/v1/targets?type=LINUX_SERVER&limit=200'),
      tryFetch<Page<Target>>('/api/v1/targets?type=WINDOWS_SERVER&limit=200'),
      tryFetch<Page<Account>>('/api/v1/accounts?limit=200'),
      tryFetch<Page<Account>>('/api/v1/accounts?privileged=true&limit=200'),
      tryFetch<Page<AccountFinding>>('/api/v1/account-findings?limit=200'),
      tryFetch<Page<Identity>>('/api/v1/identities?limit=200'),
      tryFetch<Page<Operation>>('/api/v1/operations?limit=12'),
      tryFetch<SystemHealth>('/api/v1/system/health'),
    ]).then(([linux, windows, accounts, privileged, findings, identities, operations, health]) =>
      setData({ linux, windows, accounts, privileged, findings, identities, operations, health, loaded: true }));
  }, []);

  const servers = data.linux || data.windows
    ? String((data.linux?.items.length ?? 0) + (data.windows?.items.length ?? 0)) + (data.linux?.nextCursor || data.windows?.nextCursor ? '+' : '')
    : undefined;
  const attention = data.operations?.items.filter((o) => ['FAILED', 'UNKNOWN', 'PARTIAL', 'TIMEOUT'].includes(o.status)).length;
  const byType = new Map<string, number>();
  data.findings?.items.forEach((f) => byType.set(f.type, (byType.get(f.type) ?? 0) + 1));
  const maxFinding = Math.max(1, ...byType.values());
  const show = (v: string | undefined) => (data.loaded ? v ?? '—' : undefined);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4" component="h2">{format(t.welcome, { name: displayName ?? '' })}</Typography>
        <Typography color="text.secondary">{t.dashboardSubtitle}</Typography>
      </Box>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(6, 1fr)' } }}>
        <Kpi label={t.kpiServers} value={show(servers)} accent="#4f46e5" />
        <Kpi label={t.kpiAccounts} value={show(countLabel(data.accounts))} accent="#0ea5e9" />
        <Kpi label={t.kpiPrivileged} value={show(countLabel(data.privileged))} accent="#d97706" />
        <Kpi label={t.kpiFindings} value={show(countLabel(data.findings))} accent="#dc2626" />
        <Kpi label={t.kpiUsers} value={show(countLabel(data.identities))} accent="#16a34a" />
        <Kpi label={t.kpiOperations} value={show(attention === undefined ? undefined : String(attention))} accent="#7c3aed" />
      </Box>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Panel title={t.recentOperations}>
          {!data.loaded ? <LinearProgress /> : !data.operations?.items.length ? <Typography color="text.secondary">{t.noData}</Typography> : (
            <Stack divider={<Box sx={{ borderTop: '1px solid', borderColor: 'divider' }} />}>
              {data.operations.items.map((o) => (
                <Stack key={o.id} direction="row" alignItems="center" spacing={2} sx={{ py: 1 }}>
                  <Chip size="small" label={o.status} color={operationColor(o.status)} sx={{ minWidth: 90 }} />
                  <Typography sx={{ fontFamily: 'monospace', fontSize: 13, flexGrow: 1 }}>{o.type}</Typography>
                  <Typography variant="body2" color="text.secondary" noWrap sx={{ maxWidth: 320 }}>{o.errorCode ?? o.verificationSummary ?? ''}</Typography>
                  <Typography variant="caption" color="text.secondary">{o.createdAt ? new Date(o.createdAt).toLocaleString() : ''}</Typography>
                </Stack>
              ))}
            </Stack>
          )}
        </Panel>
        <Stack spacing={2}>
          <Panel title={t.healthSummary}>
            {!data.loaded ? <LinearProgress /> : !data.health ? <Typography color="text.secondary">—</Typography> : (
              <Stack spacing={1}>
                <Chip label={data.health.status} color={healthColor(data.health.status)} sx={{ alignSelf: 'flex-start' }} />
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
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
                    <Stack direction="row" justifyContent="space-between"><Typography variant="body2">{type}</Typography><Typography variant="body2" sx={{ fontWeight: 700 }}>{n}</Typography></Stack>
                    <LinearProgress variant="determinate" value={(n / maxFinding) * 100} color="error" sx={{ height: 6, borderRadius: 3 }} />
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
