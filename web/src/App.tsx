import { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, MemoryRouter, Route, Routes } from 'react-router';
import { Box, Button, CircularProgress, Stack, Typography } from '@mui/material';
import { ColorModeContext, type ColorMode, DirectionalTheme } from './theme';
import { directionOf, messages, type Locale } from './i18n/messages';
import { LocaleContext } from './i18n/LocaleContext';
import { ApiError, apiFetch, startLogin } from './api/client';
import type { EffectiveAccess } from './api/types';
import { Shell } from './layout/Shell';
import { flatten, isAvailable, type NavItem } from './navigation';
import { AccessLogsPage, AuditPage, ConfigurationChangesPage, HealthPage, IntegrationsPage, PlannedPage, PrivilegedActivityPage, ProvidersPage, RolesPage, SettingsPage, TargetsPage } from './pages/Pages';
import { UsersPage } from './pages/Users';
import { OrgUnitsPage } from './pages/OrgUnits';
import { AccessRequestsPage, ApprovalsPage, PoliciesPage } from './pages/Requests';
import { ReportsPage } from './pages/Reports';
import { DashboardPage } from './pages/Dashboard';
import { MeContext } from './MeContext';
import { ErrorAlert } from './pages/common';
import { DatabasesPage, ServersPage } from './pages/Servers';
import { CredentialRequestsPage, MyCheckoutsPage, VaultPage } from './pages/Vault';
import { ActiveEmergenciesPage, EmergencyAccountsPage, EmergencyUsesPage } from './pages/Emergency';
import { AllAccountsPage, LinuxAccountsPage, PrivilegedAccountsPage, ServiceAccountsPage, WindowsAccountsPage } from './pages/Accounts';

const PAGES: Record<string, () => React.JSX.Element> = {
  dashboard: DashboardPage,
  systemHealth: HealthPage,
  users: UsersPage,
  roles: RolesPage,
  rolesPermissions: RolesPage,
  auditLogs: AuditPage,
  allTargets: TargetsPage,
  providerManagement: ProvidersPage,
  servers: () => <ServersPage />,
  databases: DatabasesPage,
  directory: OrgUnitsPage,
  accessRequests: AccessRequestsPage,
  reports: ReportsPage,
  approvals: ApprovalsPage,
  accessPolicies: PoliciesPage,
  policies: PoliciesPage,
  serviceAccountsList: ServiceAccountsPage,
  serviceAccounts: ServiceAccountsPage,
  allAccounts: AllAccountsPage,
  privilegedAccounts: PrivilegedAccountsPage,
  linuxAccounts: LinuxAccountsPage,
  windowsAccounts: WindowsAccountsPage,
  passwordVault: VaultPage,
  pamRequests: CredentialRequestsPage,
  myCheckouts: MyCheckoutsPage,
  pamApprovals: ApprovalsPage,
  emergencyAccountsList: EmergencyAccountsPage,
  emergencyAccounts: EmergencyAccountsPage,
  emergencyAccess: EmergencyAccountsPage,
  emergencyRequests: EmergencyAccountsPage,
  activeEmergencies: ActiveEmergenciesPage,
  emergencyAudit: () => <EmergencyUsesPage />,
  emergencyApprovals: () => <EmergencyUsesPage />,
  accessLogs: AccessLogsPage,
  privilegedActivity: PrivilegedActivityPage,
  configurationChanges: ConfigurationChangesPage,
  systemSettings: () => <SettingsPage />,
  integrations: IntegrationsPage,
};

function routeElement(item: NavItem) {
  const Page = PAGES[item.id];
  return isAvailable(item) && Page ? <Page /> : <PlannedPage item={item} />;
}

export function App({ initialLocale = 'en', inMemoryRouter = false }: { initialLocale?: Locale; inMemoryRouter?: boolean }) {
  const [locale, setLocale] = useState<Locale>(initialLocale);
  const [mode, setMode] = useState<ColorMode>(() => {
    try {
      const saved = window.localStorage.getItem('iam.colorMode');
      if (saved === 'light' || saved === 'dark') return saved;
      return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      return 'light';
    }
  });
  const colorMode = useMemo(() => ({
    mode,
    toggle: () => setMode((m) => {
      const next = m === 'light' ? 'dark' : 'light';
      try {
        window.localStorage.setItem('iam.colorMode', next);
      } catch {
        // preference is per browser only
      }
      return next;
    }),
  }), [mode]);
  const [me, setMe] = useState<EffectiveAccess>();
  const [error, setError] = useState<unknown>();
  const t = messages[locale];
  const direction = directionOf(locale);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = direction;
  }, [locale, direction]);

  useEffect(() => {
    apiFetch<EffectiveAccess>('/api/v1/me').then(setMe, setError);
  }, []);

  const ctx = useMemo(() => ({ locale, t, toggle: () => setLocale(locale === 'en' ? 'ar' : 'en') }), [locale, t]);
  const Router = inMemoryRouter ? MemoryRouter : BrowserRouter;

  let content: React.JSX.Element;
  if (error && !(error instanceof ApiError && error.status === 401)) {
    content = <Box sx={{ p: 4 }}><ErrorAlert error={error} /></Box>;
  } else if (!me) {
    content = (
      <Stack sx={{ p: 6 }} spacing={2} alignItems="flex-start">
        <Typography variant="h4" component="h1">{t.appTitle}</Typography>
        <Typography color="text.secondary">{t.appSubtitle}</Typography>
        {error ? <Button variant="contained" onClick={() => startLogin()}>{t.signIn}</Button> : <CircularProgress aria-label={t.loading} />}
      </Stack>
    );
  } else {
    content = (
      <MeContext.Provider value={me}>
        <Shell me={me}>
          <Routes>
            {flatten().filter((i) => !i.children).map((i) => <Route key={i.id} path={i.path} element={routeElement(i)} />)}
            <Route path="*" element={<DashboardPage />} />
          </Routes>
        </Shell>
      </MeContext.Provider>
    );
  }

  return (
    <LocaleContext.Provider value={ctx}>
      <ColorModeContext.Provider value={colorMode}>
        <DirectionalTheme direction={direction} mode={mode}>
          <Router>{content}</Router>
        </DirectionalTheme>
      </ColorModeContext.Provider>
    </LocaleContext.Provider>
  );
}
