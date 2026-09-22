import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, CardContent, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { apiFetch } from '../api/client';
import type { OrgUnit, Page } from '../api/types';
import { useLocale } from '../i18n/LocaleContext';
import { DataTable, ErrorAlert } from './common';

const KINDS = ['BUSINESS_UNIT', 'DEPARTMENT', 'TEAM'];

/** Organization structure: business units, departments and teams (the scope for roles, users and servers). */
export function OrgUnitsPage() {
  const { t } = useLocale();
  const [units, setUnits] = useState<OrgUnit[]>();
  const [error, setError] = useState<unknown>();
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<OrgUnit & { version?: number }>();
  const [newName, setNewName] = useState('');
  const [renameError, setRenameError] = useState<unknown>();

  const load = useCallback(async () => {
    try {
      const p = await apiFetch<Page<OrgUnit>>('/api/v1/org-units?limit=200');
      setUnits([...p.items].sort((a, b) => a.path.localeCompare(b.path)));
    } catch (e) {
      setError(e);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <ErrorAlert error={error} />;
  if (!units) return <CircularProgress aria-label={t.loading} />;
  const depth = (u: OrgUnit) => Math.max(0, u.path.split('/').filter(Boolean).length - 1);
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h5" component="h2">{t.nav.directory}</Typography>
        <Button variant="contained" onClick={() => setAdding(true)}>{t.addOrgUnit}</Button>
      </Stack>
      {units.length === 0 ? <Alert severity="info">{t.noData}</Alert> : (
        <Card><CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
          <DataTable<OrgUnit> title="" rows={units} rowKey={(u) => u.id} columns={[
            { header: t.name, cell: (u) => <Typography sx={{ ps: depth(u) * 3, fontWeight: depth(u) === 0 ? 700 : 400 }}>{depth(u) > 0 ? '└ ' : ''}{u.name}</Typography> },
            { header: t.code, cell: (u) => u.code },
            { header: t.type, cell: (u) => <Chip size="small" variant="outlined" label={u.kind} /> },
            { header: 'Path', cell: (u) => <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>{u.path}</Typography> },
            { header: t.action, cell: (u) => <Button size="small" onClick={() => { setRenaming(u); setNewName(u.name); setRenameError(undefined); }}>{t.edit}</Button> },
          ]} />
        </CardContent></Card>
      )}
      {renaming && (
        <Dialog open onClose={() => setRenaming(undefined)} fullWidth maxWidth="sm">
          <DialogTitle>{t.edit} — {renaming.code}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              {renameError !== undefined && <ErrorAlert error={renameError} />}
              <TextField required label={t.name} value={newName} onChange={(e) => setNewName(e.target.value)} />
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setRenaming(undefined)}>{t.cancel}</Button>
            <Button variant="contained" disabled={!newName.trim()} onClick={() => {
              apiFetch(`/api/v1/org-units/${renaming.id}`, { method: 'PATCH', headers: { 'If-Match': String(renaming.version ?? 0) },
                body: JSON.stringify({ name: newName.trim() }) })
                .then(() => { setRenaming(undefined); void load(); }, setRenameError);
            }}>{t.save}</Button>
          </DialogActions>
        </Dialog>
      )}
      {adding && <AddOrgUnitDialog units={units} onClose={() => setAdding(false)} onCreated={() => { setAdding(false); void load(); }} />}
    </Stack>
  );
}

function AddOrgUnitDialog({ units, onClose, onCreated }: { units: OrgUnit[]; onClose: () => void; onCreated: () => void }) {
  const { t } = useLocale();
  const [form, setForm] = useState({ name: '', code: '', kind: 'DEPARTMENT', parentId: '' });
  const [error, setError] = useState<unknown>();
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const save = async () => {
    try {
      await apiFetch('/api/v1/org-units', { method: 'POST', body: JSON.stringify({ ...form, parentId: form.parentId || null, code: form.code.toUpperCase() }) });
      onCreated();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t.addOrgUnit}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error !== undefined && <ErrorAlert error={error} />}
          <TextField required label={t.name} value={form.name} onChange={set('name')} />
          <TextField required label={t.code} value={form.code} onChange={set('code')} helperText="IT, FIN-OPS …" />
          <TextField select label={t.type} value={form.kind} onChange={set('kind')}>
            {KINDS.map((k) => <MenuItem key={k} value={k}>{k}</MenuItem>)}
          </TextField>
          <TextField select label={t.parentUnit} value={form.parentId} onChange={set('parentId')}>
            <MenuItem value="">{t.noParent}</MenuItem>
            {units.map((u) => <MenuItem key={u.id} value={u.id}>{u.path}</MenuItem>)}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.cancel}</Button>
        <Button variant="contained" disabled={!form.name || !form.code} onClick={() => void save()}>{t.save}</Button>
      </DialogActions>
    </Dialog>
  );
}
