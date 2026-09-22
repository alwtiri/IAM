import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { messages } from '../i18n/messages';
import { MeContext } from '../MeContext';
import { UsersPage } from './Users';

const t = messages.en;
const admin = { identityId: 'me', username: 'admin', displayName: 'Admin', authenticationContext: 'mfa', grants: [{ roleCode: 'IAM_ADMINISTRATOR',
  permissions: ['identity:read', 'identity:write', 'identity:lifecycle', 'identity:platform-user', 'person:read', 'person:write', 'role-assignment:write', 'audit:read'],
  scope: [{ type: 'GLOBAL', value: '*' }], validUntil: null }] };
const identity = { id: 'i9', personId: 'p9', displayName: 'Sara Ali', type: 'EMPLOYEE', username: 'sara.ali', state: 'ACTIVE', validUntil: null, platformUser: false,
  orgUnitPath: '/IT', version: 1 };
const person = { id: 'p9', orgUnitId: 'ou1', employeeId: null, givenName: 'Sara', familyName: 'Ali', displayName: 'Sara Ali', positionId: null, locationId: null,
  managerPersonId: null, employmentStatus: 'ACTIVE', startDate: null, endDate: null, email: 'sara@example.org', phone: null, version: 3 };

function install() {
  const calls: { url: string; method: string; body?: string; headers?: HeadersInit }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    calls.push({ url, method, body: init?.body as string | undefined, headers: init?.headers });
    const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
    if (url.startsWith('/api/v1/identities?')) return json(200, { items: url.includes('q=zzz') ? [] : [identity], nextCursor: null, limit: 100 });
    if (url.startsWith('/api/v1/org-units')) return json(200, { items: [{ id: 'ou1', parentId: null, kind: 'DEPARTMENT', code: 'IT', name: 'IT', path: '/IT' }], nextCursor: null, limit: 100 });
    if (url === '/api/v1/users' && method === 'POST') return json(201, identity);
    if (url === '/api/v1/identities/i9') return json(200, identity);
    if (url === '/api/v1/persons/p9' && method === 'PATCH') return json(200, { ...person, phone: '+967 1 234', version: 4 });
    if (url === '/api/v1/persons/p9') return json(200, person);
    if (url.startsWith('/api/v1/role-assignments')) return json(200, { items: [{ id: 'g1', identityId: 'i9', roleId: 'r1', roleCode: 'HELPDESK', scope: [{ type: 'GLOBAL', value: '*' }], status: 'ACTIVE', grantedAt: '2026-09-22T10:00:00Z', reason: null }], nextCursor: null, limit: 100 });
    if (url === '/api/v1/roles') return json(200, [{ id: 'r1', code: 'HELPDESK', name: 'Helpdesk', description: null, builtIn: true, permissions: ['person:read', 'identity:read'] }]);
    if (url.startsWith('/api/v1/audit-events')) return json(200, { items: [{ id: 'a1', seq: 1, occurredAt: '2026-09-22T10:00:00Z', actorType: 'USER', action: 'identity.created', objectType: 'identity', objectId: 'i9', result: 'SUCCESS', correlationId: null }], nextCursor: null, limit: 50 });
    if (url === '/api/v1/identities/i9:disable') return json(200, { ...identity, state: 'DISABLED' });
    return json(404, { code: 'NOT_FOUND', message: url, retryable: false });
  }));
  return calls;
}

const renderPage = () => render(<MeContext.Provider value={admin}><UsersPage /></MeContext.Provider>);

describe('users page', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('creates a user in one step and opens it with profile, permissions and activity', async () => {
    const calls = install();
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: t.addUser }));
    fireEvent.change(await screen.findByLabelText(new RegExp(t.givenName)), { target: { value: 'Sara' } });
    fireEvent.change(screen.getByLabelText(new RegExp(t.familyName)), { target: { value: 'Ali' } });
    await waitFor(() => expect((screen.getByLabelText(new RegExp(`^${t.username}`)) as HTMLInputElement).value).toBe('sara.ali'));
    fireEvent.click(screen.getByRole('button', { name: t.save }));
    expect(await screen.findByText(t.roleAssignments)).toBeInTheDocument();
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/users')?.body ?? '{}')).toMatchObject({ givenName: 'Sara', familyName: 'Ali', orgUnitId: 'ou1', username: 'sara.ali', activate: true });
    expect(await screen.findByText('sara@example.org')).toBeInTheDocument();
    expect(await screen.findByText('person:read')).toBeInTheDocument();
    expect(await screen.findByText('identity.created')).toBeInTheDocument();
  });

  it('searches on the server and shows the empty state', async () => {
    const calls = install();
    renderPage();
    await screen.findByText('Sara Ali');
    fireEvent.change(screen.getByLabelText(t.search), { target: { value: 'zzz' } });
    expect(await screen.findByText(t.noUsers)).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('q=zzz'))).toBe(true);
  });

  it('edits the profile with optimistic locking and deactivates only with a reason', async () => {
    const calls = install();
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Sara Ali' }));
    fireEvent.click(await screen.findByRole('button', { name: t.editProfile }));
    fireEvent.change(screen.getByLabelText(t.phone), { target: { value: '+967 1 234' } });
    fireEvent.click(screen.getByRole('button', { name: t.save }));
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/persons/p9' && c.method === 'PATCH')).toBe(true));
    const patch = calls.find((c) => c.method === 'PATCH');
    expect(new Headers(patch?.headers).get('If-Match')).toBe('3');
    expect(JSON.parse(patch?.body ?? '{}')).toMatchObject({ phone: '+967 1 234', givenName: 'Sara' });

    fireEvent.click(await screen.findByRole('button', { name: t.deactivateUser }));
    const confirm = screen.getAllByRole('button', { name: t.deactivateUser }).at(-1) as HTMLElement;
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText(t.reasonRequired), { target: { value: 'left the company' } });
    fireEvent.click(confirm);
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/identities/i9:disable' && c.body === JSON.stringify({ reason: 'left the company' }))).toBe(true));
  });
});
