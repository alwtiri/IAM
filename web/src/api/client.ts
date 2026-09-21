/**
 * Minimal API client for the Core BFF (ADR-0016): same-origin cookies, CSRF header for state-changing requests,
 * structured errors (spec §73), redirect to login on 401, explicit step-up on STEP_UP_REQUIRED.
 */
export interface ApiErrorBody {
  code: string;
  message: string;
  retryable: boolean;
  correlationId?: string | null;
  details?: { field: string; code: string; message?: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.status = status;
    this.body = body;
  }
}

export const LOGIN_PATH = '/oauth2/authorization/keycloak';

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  return cookieString
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(name + '='))
    ?.substring(name.length + 1);
}

export interface Navigator {
  assign(url: string): void;
}

const browserNavigator: Navigator = { assign: (url) => window.location.assign(url) };

export function startLogin(nav: Navigator = browserNavigator): void {
  nav.assign(LOGIN_PATH);
}

/** Forces re-authentication with MFA, then returns to the current page. */
export function startStepUp(nav: Navigator = browserNavigator): void {
  nav.assign(LOGIN_PATH + '?stepup=1');
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  deps: { fetchFn?: typeof fetch; nav?: Navigator; cookies?: string } = {},
): Promise<T> {
  const fetchFn = deps.fetchFn ?? fetch;
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = readCookie('XSRF-TOKEN', deps.cookies ?? document.cookie);
    if (token) {
      headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
    }
  }
  const response = await fetchFn(path, { ...init, method, headers, credentials: 'same-origin' });
  if (response.status === 401) {
    startLogin(deps.nav);
    throw new ApiError(401, { code: 'AUTHENTICATION_REQUIRED', message: 'Authentication required', retryable: false });
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const json: unknown = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    const body = (json as ApiErrorBody | undefined) ?? { code: 'INTERNAL_ERROR', message: response.statusText, retryable: false };
    throw new ApiError(response.status, body);
  }
  return json as T;
}
