import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import { messages } from '../i18n/messages';
import { waitForOperation } from '../api/operations';
import { ServersPage } from './Servers';

const t = messages.en;
const server = { id: 's1', name: 'srv01', hostname: 'srv01.example.org', type: 'LINUX_SERVER', environment: 'PRODUCTION', criticality: 'HIGH', status: 'ACTIVE' };
const binding = { targetId: 's1', providerInstanceId: 'p1', providerType: 'linux-ssh', providerName: 'srv01-ssh', channel: null };
const account = { id: 'a1', targetId: 's1', targetName: 'srv01', providerInstanceId: 'p1', providerType: 'linux-ssh', nativeId: 'bob', name: 'bob',
  displayName: null, type: 'HUMAN', privileged: false, privilegeReason: null, governanceState: 'DISCOVERED', nativeStatus: 'ENABLED',
  attributes: {}, lastSeenAt: null, lastLoginAt: null, openFindings: ['NO_OWNER'], version: 1 };

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown };

function install(handler: Handler) {
  const calls: { url: string; method: string; body?: string }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
    const r = handler(url, init);
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status: r.status });
  }));
  return calls;
}

describe('servers page', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lists servers, tests a connection and shows an honest outcome', async () => {
    let polls = 0;
    const calls = install((url, init) => {
      if (url.startsWith('/api/v1/targets?type=LINUX_SERVER')) return { status: 200, body: { items: [server], nextCursor: null, limit: 100 } };
      if (url.startsWith('/api/v1/targets?type=WINDOWS_SERVER')) return { status: 200, body: { items: [], nextCursor: null, limit: 100 } };
      if (url.endsWith('/provider-bindings')) return { status: 200, body: [binding] };
      if (url.endsWith('/discovery-runs') && (init?.method ?? 'GET') === 'GET') return { status: 200, body: [] };
      if (url.startsWith('/api/v1/accounts')) return { status: 200, body: { items: [account], nextCursor: null, limit: 200 } };
      if (url.endsWith(':test-connection')) return { status: 202, body: { operationId: 'op1', discoveryRunId: null } };
      if (url === '/api/v1/operations/op1') {
        polls++;
        return { status: 200, body: { id: 'op1', type: 'VALIDATE_CONNECTION', status: polls < 2 ? 'RUNNING' : 'FAILED', statusReason: null,
          errorCode: 'AUTHENTICATION_FAILED', errorMessage: 'authentication or host key verification failed', verificationSummary: null, finishedAt: null } };
      }
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<ServersPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'srv01' }));
    expect(await screen.findByText('srv01-ssh')).toBeInTheDocument();
    expect(await screen.findByText('NO_OWNER')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t.testConnection }));
    await vi.advanceTimersByTimeAsync(2000);
    expect(await screen.findByText(/AUTHENTICATION_FAILED/)).toBeInTheDocument();
    const post = calls.find((c) => c.url.endsWith(':test-connection'));
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ providerInstanceId: 'p1' });
    vi.useRealTimers();
  });

  it('asks for confirmation and a reason before disabling an account', async () => {
    const calls = install((url) => {
      if (url.startsWith('/api/v1/targets?type=LINUX_SERVER')) return { status: 200, body: { items: [server], nextCursor: null, limit: 100 } };
      if (url.startsWith('/api/v1/targets?type=')) return { status: 200, body: { items: [], nextCursor: null, limit: 100 } };
      if (url.endsWith('/provider-bindings') || url.endsWith('/discovery-runs')) return { status: 200, body: [] };
      if (url.startsWith('/api/v1/accounts?')) return { status: 200, body: { items: [account], nextCursor: null, limit: 200 } };
      if (url.endsWith(':disable')) return { status: 202, body: { operationId: 'op2', discoveryRunId: null } };
      if (url === '/api/v1/operations/op2') {
        return { status: 200, body: { id: 'op2', type: 'DISABLE_ACCOUNT', status: 'SUCCESS', statusReason: null, errorCode: null,
          errorMessage: null, verificationSummary: 'account bob reads back locked and expired', finishedAt: null } };
      }
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    render(<ServersPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'srv01' }));
    fireEvent.click(await screen.findByRole('button', { name: t.disable }));
    expect(await screen.findByText(t.confirmTitle)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(t.reason), { target: { value: 'leaver' } });
    fireEvent.click(screen.getAllByRole('button', { name: t.disable }).at(-1) as HTMLElement);
    expect(await screen.findByText(/reads back locked and expired/)).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url.endsWith(':disable') && c.body === JSON.stringify({ reason: 'leaver' }))).toBe(true));
  });
});

describe('operation polling', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('stops at the wait limit and returns the last observed state', async () => {
    install(() => ({ status: 200, body: { id: 'x', type: 'DISCOVER_ACCOUNTS', status: 'RUNNING' } }));
    const p = waitForOperation('x', { intervalMs: 100, timeoutMs: 300 });
    await vi.advanceTimersByTimeAsync(1000);
    expect((await p).status).toBe('RUNNING');
  });
});
