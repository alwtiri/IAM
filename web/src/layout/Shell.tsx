import { useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router';
import { Avatar, Box, Button, Collapse, Drawer, IconButton, Stack, Tooltip, Typography, useMediaQuery, useTheme } from '@mui/material';
import { NAVIGATION, flatten, isAvailable, type NavItem } from '../navigation';
import { useLocale } from '../i18n/LocaleContext';
import type { EffectiveAccess } from '../api/types';
import { readCookie } from '../api/client';
import { useColorMode, useTokens } from '../theme';
import { Icon } from './Icons';

const SIDEBAR_WIDTH = 264;

function containsPath(item: NavItem, path: string): boolean {
  return item.path === path || !!item.children?.some((c) => containsPath(c, path));
}

/** Sidebar entry in the shadcn/ui sidebar style: icon + label, collapsible groups with an indented sub-menu rail. */
function NavEntry({ item, depth, onNavigate }: { item: NavItem; depth: number; onNavigate?: () => void }) {
  const { t } = useLocale();
  const k = useTokens();
  const location = useLocation();
  const available = isAvailable(item) || !!item.children?.some(isAvailable);
  const [open, setOpen] = useState(containsPath(item, location.pathname) || (depth === 0 && available && item.id !== 'administration'));
  const label = t.nav[item.id as keyof typeof t.nav] ?? item.id;
  const rowSx = {
    display: 'flex', alignItems: 'center', gap: 1.25, width: '100%', px: 1, py: 0.75, borderRadius: 1.5, fontSize: 14, color: 'text.primary',
    textDecoration: 'none', border: 0, bgcolor: 'transparent', cursor: 'pointer', font: 'inherit', textAlign: 'start',
    '&:hover': { bgcolor: k.accent }, '&:focus-visible': { outline: `2px solid ${k.ring}` },
  } as const;
  if (item.children) {
    return (
      <Box component="li" sx={{ listStyle: 'none' }}>
        <Box component="button" type="button" onClick={() => setOpen(!open)} aria-expanded={open} sx={{ ...rowSx, fontWeight: 500, opacity: available ? 1 : 0.55 }}>
          {depth === 0 && <Icon name={item.id} />}
          <Box component="span" sx={{ flexGrow: 1 }}>{label}</Box>
          <Icon name="chevron" size={14} style={{ transition: 'transform .15s', transform: open ? 'rotate(90deg)' : 'none', opacity: 0.6 }} />
        </Box>
        <Collapse in={open} unmountOnExit>
          <Box component="ul" sx={{ m: 0, p: 0, py: 0.25, marginInlineStart: '17px', paddingInlineStart: '10px', borderInlineStart: `1px solid ${k.border}` }}>
            {item.children.map((c) => <NavEntry key={c.id} item={c} depth={depth + 1} onNavigate={onNavigate} />)}
          </Box>
        </Collapse>
      </Box>
    );
  }
  const planned = !isAvailable(item);
  return (
    <Box component="li" sx={{ listStyle: 'none' }}>
      <Box component={NavLink} to={item.path} end onClick={onNavigate}
        sx={{
          ...rowSx, color: planned ? 'text.secondary' : 'text.primary', py: depth === 0 ? 0.75 : 0.6, fontSize: depth === 0 ? 14 : 13.5,
          '&.active': { bgcolor: k.accent, fontWeight: 600 },
        }}>
        {depth === 0 && <Icon name={item.id} />}
        <Box component="span" sx={{ flexGrow: 1 }}>{label}</Box>
        {planned && (
          <Box component="span" sx={{ fontSize: 10.5, px: 0.75, py: 0.1, borderRadius: 1, border: `1px solid ${k.border}`, color: 'text.secondary' }}>
            P{item.phase}
          </Box>
        )}
      </Box>
    </Box>
  );
}

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');

function Sidebar({ me, onNavigate }: { me: EffectiveAccess; onNavigate?: () => void }) {
  const k = useTokens();
  return (
    <Stack sx={{ height: '100%', bgcolor: k.sidebar, borderInlineEnd: `1px solid ${k.border}` }}>
      <Stack direction="row" alignItems="center" spacing={1.25} sx={{ px: 2, height: 60, flexShrink: 0 }}>
        <Box sx={{ width: 32, height: 32, borderRadius: 1.5, bgcolor: k.primary, color: k.primaryForeground, display: 'grid', placeItems: 'center' }}>
          <Icon name="shieldKey" size={18} />
        </Box>
        <Box sx={{ lineHeight: 1.15 }}>
          <Typography sx={{ fontWeight: 600, fontSize: 14 }}>IAM · PAM</Typography>
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Enterprise</Typography>
        </Box>
      </Stack>
      <Box component="nav" aria-label="main navigation" sx={{ flexGrow: 1, overflowY: 'auto', px: 1.25, pb: 2 }}>
        <Box component="ul" sx={{ m: 0, p: 0, display: 'grid', gap: 0.25 }}>
          {NAVIGATION.map((item) => <NavEntry key={item.id} item={item} depth={0} onNavigate={onNavigate} />)}
        </Box>
      </Box>
      <Stack direction="row" alignItems="center" spacing={1.25} sx={{ p: 1.5, m: 1.25, borderRadius: 2, border: `1px solid ${k.border}`, bgcolor: 'background.paper' }}>
        <Avatar sx={{ width: 32, height: 32, borderRadius: 1.5, bgcolor: k.muted, color: 'text.primary', fontSize: 12, fontWeight: 600 }}>
          {initials(me.displayName || me.username)}
        </Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 13, fontWeight: 600 }}>{me.displayName}</Typography>
          <Typography noWrap sx={{ fontSize: 12, color: 'text.secondary' }}>{me.username}</Typography>
        </Box>
      </Stack>
    </Stack>
  );
}

/** Application frame in the shadcn/ui dashboard layout: sidebar, sticky header with breadcrumb, content inset. */
export function Shell({ me, children }: { me: EffectiveAccess; children: ReactNode }) {
  const { t, toggle, locale } = useLocale();
  const { mode, toggle: toggleMode } = useColorMode();
  const k = useTokens();
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'), { defaultMatches: true });
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const current = flatten().find((i) => i.path === location.pathname && !i.children);
  const parent = current ? NAVIGATION.find((n) => n.children?.includes(current)) : undefined;
  const nav = t.nav as Record<string, string>;
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      {desktop ? (
        <Box sx={{ width: SIDEBAR_WIDTH, flexShrink: 0, position: 'sticky', top: 0, height: '100vh' }}>
          <Sidebar me={me} />
        </Box>
      ) : (
        <Drawer open={mobileOpen} onClose={() => setMobileOpen(false)} slotProps={{ paper: { sx: { width: SIDEBAR_WIDTH, border: 0 } } }}>
          <Sidebar me={me} onNavigate={() => setMobileOpen(false)} />
        </Drawer>
      )}
      <Box sx={{ flexGrow: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Box component="header" sx={{
          position: 'sticky', top: 0, zIndex: 10, height: 60, display: 'flex', alignItems: 'center', gap: 1.5, px: { xs: 2, md: 3 },
          borderBottom: `1px solid ${k.border}`, bgcolor: mode === 'light' ? 'rgba(255,255,255,0.85)' : 'rgba(9,9,11,0.85)', backdropFilter: 'blur(8px)',
        }}>
          {!desktop && (
            <IconButton size="small" aria-label="menu" onClick={() => setMobileOpen(true)}><Icon name="menu" /></IconButton>
          )}
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="h6" component="h1" noWrap sx={{ fontSize: 14, fontWeight: 500, color: 'text.secondary' }}>{t.appTitle}</Typography>
            <Typography noWrap sx={{ fontSize: 13, color: 'text.primary', display: { xs: 'none', sm: 'block' } }}>
              {[parent ? nav[parent.id] : null, current ? nav[current.id] : nav.dashboard].filter(Boolean).join('  ›  ')}
            </Typography>
          </Box>
          <Tooltip title={mode === 'light' ? 'Dark mode' : 'Light mode'}>
            <IconButton size="small" aria-label="toggle color mode" onClick={toggleMode} sx={{ border: `1px solid ${k.border}`, borderRadius: 1.5 }}>
              <Icon name={mode === 'light' ? 'moon' : 'sun'} />
            </IconButton>
          </Tooltip>
          <Button onClick={toggle} lang={locale === 'en' ? 'ar' : 'en'} size="small" variant="outlined">{t.switchLanguage}</Button>
          <Box aria-label={t.signedInAs} sx={{ display: { xs: 'none', lg: 'block' }, textAlign: 'end' }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, lineHeight: 1.2 }}>{me.displayName} ({me.username})</Typography>
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{me.grants.map((g) => g.roleCode).slice(0, 2).join(', ')}</Typography>
          </Box>
          <form method="post" action="/logout">
            <input type="hidden" name="_csrf" value={decodeURIComponent(readCookie('XSRF-TOKEN') ?? '')} />
            <Button type="submit" variant="contained" size="small" startIcon={<Icon name="logout" size={14} />}>{t.signOut}</Button>
          </form>
        </Box>
        <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, width: '100%', maxWidth: 1480, mx: 'auto' }}>
          {children}
        </Box>
      </Box>
    </Box>
  );
}
