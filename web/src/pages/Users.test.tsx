import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { messages } from '../i18n/messages';
import { UsersPage } from './Users';

const t = messages.en;

describe('users page', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('creates a person and an identity, activates it and opens the user', async () => {
    const calls: { url: string; method: string; body?: string }[] = [];
    const identity = { id: 'i9', personId: 'p9', displayName: 'Sara Ali', type: 'EMPLOYEE', username: 'sara.ali', state: 'PENDING', validUntil: null, platformUser: false };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body as string | undefined });
      const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
      if (url.startsWith('/api/v1/identities?')) return json(200, { items: [], nextCursor: null, limit: 100 });
      if (url.startsWith('/api/v1/org-units')) return json(200, { items: [{ id: 'ou1', parentId: null, kind: 'DEPARTMENT', code: 'IT', name: 'IT', path: '/IT' }], nextCursor: null, limit: 100 });
      if (url === '/api/v1/persons') return json(201, { id: 'p9' });
      if (url === '/api/v1/identities' && method === 'POST') return json(201, identity);
      if (url === '/api/v1/identities/i9:activate') return json(200, { ...identity, state: 'ACTIVE' });
      if (url === '/api/v1/identities/i9') return json(200, { ...identity, state: 'ACTIVE' });
      if (url.startsWith('/api/v1/role-assignments')) return json(200, { items: [], nextCursor: null, limit: 100 });
      if (url === '/api/v1/roles') return json(200, []);
      return json(404, { code: 'NOT_FOUND', message: url, retryable: false });
    }));
    render(<UsersPage />);
    fireEvent.click(await screen.findByRole('button', { name: t.addUser }));
    fireEvent.change(await screen.findByLabelText(new RegExp(t.givenName)), { target: { value: 'Sara' } });
    fireEvent.change(screen.getByLabelText(new RegExp(t.familyName)), { target: { value: 'Ali' } });
    await waitFor(() => expect((screen.getByLabelText(new RegExp(`^${t.username}`)) as HTMLInputElement).value).toBe('sara.ali'));
    fireEvent.click(screen.getByRole('button', { name: t.save }));
    expect(await screen.findByText(t.roleAssignments)).toBeInTheDocument();
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/persons')?.body ?? '{}')).toMatchObject({ givenName: 'Sara', familyName: 'Ali', orgUnitId: 'ou1' });
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/identities' && c.method === 'POST')?.body ?? '{}')).toMatchObject({ personId: 'p9', username: 'sara.ali' });
    expect(calls.some((c) => c.url === '/api/v1/identities/i9:activate')).toBe(true);
  });
});
