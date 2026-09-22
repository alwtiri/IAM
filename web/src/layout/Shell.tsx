import { useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router';
import {
  AppBar, Avatar, Box, Button, Chip, Collapse, Drawer, List, ListItemButton, ListItemText, Stack, Toolbar, Typography,
} from '@mui/material';
import { NAVIGATION, isAvailable, type NavItem } from '../navigation';
import { useLocale } from '../i18n/LocaleContext';
import type { EffectiveAccess } from '../api/types';
import { readCookie } from '../api/client';

const DRAWER_WIDTH = 272;
const SIDEBAR_BG = 'linear-gradient(180deg, #0f172a 0%, #111c3a 100%)';

function containsPath(item: NavItem, path: string): boolean {
  return item.path === path || !!item.children?.some((c) => containsPath(c, path));
}

function NavEntry({ item, depth }: { item: NavItem; depth: number }) {
  const { t } = useLocale();
  const location = useLocation();
  const available = isAvailable(item) || !!item.children?.some(isAvailable);
  const [open, setOpen] = useState(containsPath(item, location.pathname) || (depth === 0 && available && item.id !== 'administration'));
  const label = t.nav[item.id as keyof typeof t.nav] ?? item.id;
  if (item.children) {
    return (
      <>
        <ListItemButton onClick={() => setOpen(!open)} sx={{ mx: 1, borderRadius: 2, ps: 2 + depth * 1.5, opacity: available ? 1 : 0.5 }}>
          <ListItemText primary={label} slotProps={{ primary: { fontWeight: 600, fontSize: 13, letterSpacing: '0.02em' } }} />
          <Typography sx={{ fontSize: 12, opacity: 0.6 }}>{open ? '▾' : '▸'}</Typography>
        </ListItemButton>
        <Collapse in={open} unmountOnExit>
          <List disablePadding>
            {item.children.map((c) => <NavEntry key={c.id} item={c} depth={depth + 1} />)}
          </List>
        </Collapse>
      </>
    );
  }
  return (
    <ListItemButton component={NavLink} to={item.path} end
      sx={{
        mx: 1, my: 0.25, borderRadius: 2, ps: 2 + depth * 1.5, py: 0.6, opacity: isAvailable(item) ? 1 : 0.45,
        '&.active': { bgcolor: 'rgba(129,140,248,0.18)', color: '#fff', boxShadow: 'inset 3px 0 0 #818cf8' },
        '&:hover': { bgcolor: 'rgba(255,255,255,0.06)' },
      }}>
      <ListItemText primary={label} slotProps={{ primary: { fontSize: 14 } }} />
      {!isAvailable(item) && <Chip size="small" label={`P${item.phase}`} variant="outlined" sx={{ color: '#94a3b8', borderColor: '#334155', height: 20 }} />}
    </ListItemButton>
  );
}

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');

/** Application frame: header with language switch and user, side navigation (spec §61), content area. */
export function Shell({ me, children }: { me: EffectiveAccess; children: ReactNode }) {
  const { t, toggle, locale } = useLocale();
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="fixed" color="inherit" elevation={0}
        sx={{ zIndex: (theme) => theme.zIndex.drawer + 1, bgcolor: 'rgba(255,255,255,0.92)', backdropFilter: 'blur(8px)', borderBottom: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 2 }}>
          <Stack direction="row" alignItems="center" spacing={1.5} sx={{ width: DRAWER_WIDTH - 24, flexShrink: 0 }}>
            <Box sx={{ width: 34, height: 34, borderRadius: 2, background: 'linear-gradient(135deg,#4f46e5,#0ea5e9)', display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 800 }}>ID</Box>
            <Typography variant="subtitle1" component="span" sx={{ fontWeight: 700, lineHeight: 1.1 }}>IAM · PAM</Typography>
          </Stack>
          <Typography variant="h6" component="h1" sx={{ flexGrow: 1, fontSize: 15, color: 'text.secondary', fontWeight: 500 }}>{t.appTitle}</Typography>
          <Button onClick={toggle} lang={locale === 'en' ? 'ar' : 'en'} size="small">{t.switchLanguage}</Button>
          <Stack direction="row" alignItems="center" spacing={1} aria-label={t.signedInAs}>
            <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 13 }}>{initials(me.displayName || me.username)}</Avatar>
            <Box sx={{ display: { xs: 'none', md: 'block' } }}>
              <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }}>{me.displayName} ({me.username})</Typography>
              <Typography variant="caption" color="text.secondary">{me.grants.map((g) => g.roleCode).slice(0, 2).join(', ')}</Typography>
            </Box>
          </Stack>
          <form method="post" action="/logout">
            <input type="hidden" name="_csrf" value={decodeURIComponent(readCookie('XSRF-TOKEN') ?? '')} />
            <Button type="submit" variant="outlined" size="small">{t.signOut}</Button>
          </form>
        </Toolbar>
      </AppBar>
      <Drawer variant="permanent"
        sx={{ width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH, background: SIDEBAR_BG, color: '#cbd5e1', borderRight: 0 } }}>
        <Toolbar />
        <List component="nav" aria-label="main navigation" sx={{ py: 1.5 }}>
          {NAVIGATION.map((item) => <NavEntry key={item.id} item={item} depth={0} />)}
        </List>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, maxWidth: 1480 }}>
        <Toolbar />
        {children}
      </Box>
    </Box>
  );
}
