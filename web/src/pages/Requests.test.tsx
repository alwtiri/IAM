import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { messages } from '../i18n/messages';
import { AccessRequestsPage, ApprovalsPage } from './Requests';

const t = messages.en;
const pending = { id: 'r1', requesterName: 'Sara Ali', beneficiaryName: 'Sara Ali', roleCode: 'HELPDESK', scopeType: 'ORG_UNIT', justification: 'on-call',
  durationDays: 30, status: 'PENDING_APPROVAL', statusReason: null, policyExplanation: 'allowed by P-100', matchedPolicies: ['P-100'], sodConflicts: [],
  steps: [{ stepNo: 1, approverType: 'MANAGER', approverRole: null, approverName: 'Omar', status: 'PENDING', decidedByName: null, decidedAt: null, comment: null, note: null }],
  validUntil: null, createdAt: '2026-09-22T10:00:00Z', canDecide: true, canCancel: false };

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

describe('access requests', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('submits a request with the policy hints and shows the outcome', async () => {
    const calls = install((url, init) => {
      if (url.startsWith('/api/v1/access-requests?mine=true')) return { status: 200, body: [] };
      if (url === '/api/v1/access-requests/requestable-roles') {
        return { status: 200, body: [{ id: 'role1', code: 'HELPDESK', name: 'Helpdesk', description: null, held: false, allowed: true, approvals: ['MANAGER'],
          requireJustification: true, maxDurationDays: 180, explanation: 'allowed by P-100' }] };
      }
      if (url === '/api/v1/access-requests' && init?.method === 'POST') return { status: 201, body: pending };
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    render(<AccessRequestsPage />);
    fireEvent.click(await screen.findByRole('button', { name: t.newRequest }));
    expect(await screen.findByText(/Approvals needed: Manager/)).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: t.submit });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText(new RegExp(t.justification)), { target: { value: 'on-call' } });
    fireEvent.click(submit);
    expect(await screen.findByText(/Sent for approval/)).toBeInTheDocument();
    expect(JSON.parse(calls.find((c) => c.method === 'POST')?.body ?? '{}')).toMatchObject({ roleId: 'role1', durationDays: 30, justification: 'on-call' });
  });

  it('lets an approver reject only with a reason', async () => {
    const calls = install((url) => {
      if (url === '/api/v1/approvals') return { status: 200, body: calls.some((c) => c.url.endsWith(':reject')) ? [] : [pending] };
      if (url.endsWith(':reject')) return { status: 200, body: { ...pending, status: 'REJECTED' } };
      return { status: 404, body: { code: 'NOT_FOUND', message: url, retryable: false } };
    });
    render(<ApprovalsPage />);
    fireEvent.click(await screen.findByRole('button', { name: t.reject }));
    const confirm = screen.getAllByRole('button', { name: t.reject }).at(-1) as HTMLElement;
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'not needed' } });
    fireEvent.click(confirm);
    expect(await screen.findByText('HELPDESK: REJECTED')).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/access-requests/r1:reject' && c.body === JSON.stringify({ comment: 'not needed' }))).toBe(true));
  });
});
