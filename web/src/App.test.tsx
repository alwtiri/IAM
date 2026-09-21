import { render, screen, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import { App } from './App';
import { messages } from './i18n/messages';
import { NAVIGATION, flatten } from './navigation';
import { apiFetch, ApiError } from './api/client';

const me = { identityId: 'i1', username: 'iam-admin', displayName: 'Platform Administrator', authenticationContext: 'mfa',
  grants: [{ roleCode: 'PLATFORM_ADMINISTRATOR', permissions: ['system:health:read'], scope: [{ type: 'GLOBAL', value: '*' }], validUntil: null }] };
const health = { status: 'DEGRADED', components: [
  { component: 'postgresql', category: 'DATABASE', classification: 'CORE_DEPENDENCY', status: 'HEALTHY', reason: null, affectedFunctionality: [] },
  { component: 'vault', category: 'SECRETS', classification: 'CORE_DEPENDENCY', status: 'DEGRADED', reason: 'standby node',
    affectedFunctionality: ['Storing and using credentials'] },
] };

function mockApi(routes: Record<string, { status: number; body?: unknown }>) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const r = routes[Object.keys(routes).find((k) => url.startsWith(k)) ?? ''] ?? { status: 404, body: { code: 'NOT_FOUND', message: 'nf', retryable: false } };
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status: r.status });
  });
}

describe('App shell', () => {
  beforeEach(() => vi.stubGlobal('fetch', mockApi({ '/api/v1/me': { status: 200, body: me }, '/api/v1/system/health': { status: 200, body: health } })));
  afterEach(() => vi.unstubAllGlobals());

  it('shows the signed-in identity, the §61 navigation, and the health dashboard', async () => {
    render(<App inMemoryRouter />);
    expect(await screen.findByText(/Platform Administrator/)).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'main navigation' })).toBeInTheDocument();
    expect(screen.getByText(messages.en.nav.identityAccess)).toBeInTheDocument();
    expect(await screen.findByText('vault')).toBeInTheDocument();
    expect(screen.getByText(/standby node — Storing and using credentials/)).toBeInTheDocument();
  });

  it('switches to Arabic and right-to-left', async () => {
    render(<App inMemoryRouter />);
    await screen.findByText(/Platform Administrator/);
    fireEvent.click(screen.getByRole('button', { name: messages.en.switchLanguage }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(messages.ar.appTitle);
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('offers sign-in when the session is missing', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    vi.stubGlobal('fetch', mockApi({ '/api/v1/me': { status: 401 } }));
    render(<App inMemoryRouter />);
    expect(await screen.findByRole('button', { name: messages.en.signIn })).toBeInTheDocument();
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/keycloak');
  });
});

describe('navigation (spec §61)', () => {
  it('contains every baseline section and all ids are translated in both locales', () => {
    const top = NAVIGATION.map((n) => n.id);
    expect(top).toEqual(['dashboard', 'identityAccess', 'assets', 'accounts', 'privilegedAccess', 'sessions', 'emergency',
      'auditCompliance', 'reports', 'administration']);
    for (const item of flatten()) {
      expect(messages.en.nav).toHaveProperty(item.id);
      expect(messages.ar.nav).toHaveProperty(item.id);
    }
    expect(Object.keys(messages.ar).sort()).toEqual(Object.keys(messages.en).sort());
  });

  it('has unique paths', () => {
    const paths = flatten().map((i) => i.path);
    expect(new Set(paths).size).toBe(paths.length);
  });
});

describe('api client', () => {
  it('sends the CSRF token on state-changing requests only', async () => {
    const fetchFn = vi.fn(async () => new Response('{}', { status: 200 }));
    await apiFetch('/api/v1/x', { method: 'POST', body: '{}' }, { fetchFn, cookies: 'a=1; XSRF-TOKEN=tok%2B1' });
    await apiFetch('/api/v1/x', {}, { fetchFn, cookies: 'XSRF-TOKEN=tok' });
    const post = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const get = fetchFn.mock.calls[1] as unknown as [string, RequestInit];
    expect(new Headers(post[1].headers).get('X-XSRF-TOKEN')).toBe('tok+1');
    expect(new Headers(get[1].headers).get('X-XSRF-TOKEN')).toBeNull();
    expect(post[1].credentials).toBe('same-origin');
  });

  it('surfaces structured errors including STEP_UP_REQUIRED', async () => {
    const fetchFn = vi.fn(async () => new Response(JSON.stringify({ code: 'STEP_UP_REQUIRED', message: 'mfa', retryable: false }), { status: 403 }));
    await expect(apiFetch('/api/v1/role-assignments', { method: 'POST' }, { fetchFn, cookies: '' })).rejects.toBeInstanceOf(ApiError);
  });
});
