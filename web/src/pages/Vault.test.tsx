import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { messages } from '../i18n/messages';
import { CredentialRequestsPage, MyCheckoutsPage } from './Vault';

const t = messages.en;
const checkout = { id: 'c1', accountId: 'a1', accountName: 'root', targetName: 'srv01', identityName: 'Sara', requestId: 'r1', reason: 'incident',
  startedAt: '2026-09-22T10:00:00Z', notAfter: '2026-09-22T14:00:00Z', status: 'ACTIVE', endedAt: null, revealCount: 0 };

function install(handler: (url: string, init?: RequestInit) => { status: number; body?: unknown }) {
  const calls: { url: string; method: string; body?: string }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
    const r = handler(url, init);
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status: r.status });
  }));
  return calls;
}

describe('password vault', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reveals the password of an active checkout and checks it in', async () => {
    const calls = install((url) => {
      if (url.startsWith('/api/v1/credential-checkouts?mine=true')) {
        return { status: 200, body: [calls.some((c) => c.url.endsWith(':check-in')) ? { ...checkout, status: 'CHECKED_IN' } : checkout] };
      }
      if (url.endsWith(':reveal')) {
        return { status: 200, body: { checkoutId: 'c1', accountName: 'root', targetName: 'srv01', password: 'Zq9-secret', alternatePassword: null,
          notAfter: checkout.notAfter } };
      }
      if (url.endsWith(':check-in')) return { status: 200, body: { ...checkout, status: 'CHECKED_IN' } };
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    render(<MyCheckoutsPage />);
    fireEvent.click(await screen.findByRole('button', { name: t.reveal }));
    expect(await screen.findByDisplayValue('Zq9-secret')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t.close }));
    fireEvent.click(await screen.findByRole('button', { name: t.checkIn }));
    expect(await screen.findByText('CHECKED_IN')).toBeInTheDocument();
  });

  it('requests a checkout with a justification and duration', async () => {
    const calls = install((url, init) => {
      if (url === '/api/v1/access-requests/requestable-credentials') {
        return { status: 200, body: [{ accountId: 'a1', accountName: 'root', targetName: 'srv01', providerType: 'linux-ssh', available: true,
          unavailableReason: null, allowed: true, approvals: ['ROLE:PAM_ADMINISTRATOR'], requireJustification: true, explanation: 'allowed by P-300' }] };
      }
      if (url.startsWith('/api/v1/credential-checkouts')) return { status: 200, body: [] };
      if (url === '/api/v1/access-requests/credential' && init?.method === 'POST') return { status: 201, body: {} };
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    render(<CredentialRequestsPage />);
    fireEvent.click(await screen.findByRole('button', { name: t.requestCheckout }));
    fireEvent.change(screen.getByLabelText(new RegExp(t.justification)), { target: { value: 'incident 42' } });
    fireEvent.click(screen.getAllByRole('button', { name: t.requestCheckout }).at(-1) as HTMLElement);
    expect(await screen.findByText(t.checkoutRequested)).toBeInTheDocument();
    await waitFor(() => expect(JSON.parse(calls.find((c) => c.method === 'POST')?.body ?? '{}'))
      .toMatchObject({ accountId: 'a1', justification: 'incident 42', durationHours: 2 }));
  });
});
