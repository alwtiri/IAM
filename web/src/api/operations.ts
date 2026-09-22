import { apiFetch } from './client';
import type { Operation, Submitted } from './types';

export const FINAL_STATUSES = ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED', 'PARTIAL', 'UNKNOWN'];

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Submits an asynchronous request (202 + operationId) and polls the operation until it reaches a final status or
 * the wait limit passes (the operation keeps running on the server; the last observed state is returned).
 */
export async function submitAndWait(path: string, body: unknown, opts: { intervalMs?: number; timeoutMs?: number } = {}): Promise<Operation> {
  const submitted = await apiFetch<Submitted>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
  return waitForOperation(submitted.operationId, opts);
}

export async function waitForOperation(id: string, { intervalMs = 1500, timeoutMs = 120_000 }: { intervalMs?: number; timeoutMs?: number } = {}): Promise<Operation> {
  const until = Date.now() + timeoutMs;
  let op = await apiFetch<Operation>(`/api/v1/operations/${id}`);
  while (!FINAL_STATUSES.includes(op.status) && Date.now() < until) {
    await sleep(intervalMs);
    op = await apiFetch<Operation>(`/api/v1/operations/${id}`);
  }
  return op;
}
