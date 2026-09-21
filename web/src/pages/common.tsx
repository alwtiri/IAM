import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Alert, Box, Button, CircularProgress, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { ApiError, apiFetch, startStepUp } from '../api/client';
import type { Page } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';

export function ErrorAlert({ error }: { error: unknown }) {
  const { t } = useLocale();
  if (error instanceof ApiError && error.body.code === 'STEP_UP_REQUIRED') {
    return (
      <Alert severity="warning" action={<Button onClick={() => startStepUp()}>{t.stepUpButton}</Button>}>{t.stepUpRequired}</Alert>
    );
  }
  if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
    return <Alert severity="info">{t.noAccess}</Alert>;
  }
  const message = error instanceof ApiError ? `${error.body.code}: ${error.body.message}` : String(error);
  const corr = error instanceof ApiError && error.body.correlationId ? ` (${error.body.correlationId})` : '';
  return <Alert severity="error">{t.errorPrefix}: {message}{corr}</Alert>;
}

/** Loads a cursor-paginated collection. */
export function usePaged<T>(path: string) {
  const [items, setItems] = useState<T[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async (next?: string | null) => {
    setLoading(true);
    try {
      const sep = path.includes('?') ? '&' : '?';
      const page = await apiFetch<Page<T>>(next ? `${path}${sep}cursor=${encodeURIComponent(next)}` : path);
      setItems((prev) => (next ? [...prev, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    void load();
  }, [load]);

  return { items, cursor, loading, error, loadMore: () => load(cursor) };
}

export function DataTable<T>({ title, columns, rows, rowKey, footer }: {
  title: string;
  columns: { header: string; cell: (row: T) => ReactNode }[];
  rows: T[];
  rowKey: (row: T) => string;
  footer?: ReactNode;
}) {
  return (
    <Box>
      <Typography variant="h5" component="h2" gutterBottom>{title}</Typography>
      <Table size="small" aria-label={title}>
        <TableHead>
          <TableRow>{columns.map((c) => <TableCell key={c.header}>{c.header}</TableCell>)}</TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={rowKey(r)}>{columns.map((c) => <TableCell key={c.header}>{c.cell(r)}</TableCell>)}</TableRow>
          ))}
        </TableBody>
      </Table>
      {footer}
    </Box>
  );
}

export function PagedView<T>({ path, title, columns, rowKey }: {
  path: string;
  title: string;
  columns: { header: string; cell: (row: T) => ReactNode }[];
  rowKey: (row: T) => string;
}) {
  const { t } = useLocale();
  const { items, cursor, loading, error, loadMore } = usePaged<T>(path);
  if (error) {
    return <ErrorAlert error={error} />;
  }
  return (
    <DataTable title={title} columns={columns} rows={items} rowKey={rowKey}
      footer={loading ? <CircularProgress size={24} aria-label={t.loading} /> : cursor ? <Button onClick={() => void loadMore()}>{t.loadMore}</Button> : null} />
  );
}
