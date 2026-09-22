import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router';
import {
  Autocomplete, Avatar, Badge, Box, Button, Collapse, Divider, Drawer, IconButton, InputAdornment, ListItemIcon, Menu, MenuItem, Stack, TextField,
  Tooltip, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { NAVIGATION, flatten, isAvailable, type NavItem } from '../navigation';
import { useLocale } from '../i18n/LocaleContext';
import type { EffectiveAccess } from '../api/types';
import { apiFetch, readCookie } from '../api/client';
import { SIDEBAR, useColorMode, useTokens } from '../theme';
import { Icon } from './Icons';

const SIDEBAR_WIDTH = 268;

function containsPath(item: NavItem, path: string): boolean {
  return item.path === path || !!item.children?.some((c) => containsPath(c, path));
}

/** Sidebar entry: icon + label, collapsible groups with an indented rail; planned pages are marked with their phase. */
function NavEntry({ item, depth, onNavigate }: { item: NavItem; depth: number; onNavigate?: () => void }) {
  const { t } = useLocale();
  const location = useLocation();
  const available = isAvailable(item) || !!item.children?.some(isAvailable);
  const [open, setOpen] = useState(containsPath(item, location.pathname) || (depth === 0 && available && item.id !== 'administration'));
  const label = t.nav[item.id as keyof typeof t.nav] ?? item.id;
  const rowSx = {
    display: 'flex', alignItems: 'center', gap: 1.5, width: '100%', px: 1.25, py: 0.8, borderRadius: 2, fontSize: 14, color: SIDEBAR.text,
    textDecoration: 'none', border: 0, bgcolor: 'transparent', cursor: 'pointer', font: 'inherit', textAlign: 'start', transition: 'background .15s, color .15s',
    '&:hover': { bgcolor: SIDEBAR.hover, color: '#fff' }, '&:focus-visible': { outline: `2px solid ${SIDEBAR.activeBar}` },
  } as const;
  if (item.children) {
    return (
      <Box component="li" sx={{ listStyle: 'none' }}>
        <Box component="button" type="button" onClick={() => setOpen(!open)} aria-expanded={open}
          sx={{ ...rowSx, fontWeight: 600, opacity: available ? 1 : 0.5, color: containsPath(item, location.pathname) ? '#fff' : SIDEBAR.text }}>
          <Icon name={item.id} size={18} />
          <Box component="span" sx={{ flexGrow: 1 }}>{label}</Box>
          <Icon name="chevron" size={14} style={{ transition: 'transform .15s', transform: open ? 'rotate(90deg)' : 'none', opacity: 0.55 }} />
        </Box>
        <Collapse in={open} unmountOnExit>
          <Box component="ul" sx={{ m: 0, p: 0, py: 0.5, marginInlineStart: '20px', paddingInlineStart: '12px', borderInlineStart: `1px solid ${SIDEBAR.border}` }}>
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
          ...rowSx, position: 'relative', color: planned ? SIDEBAR.muted : SIDEBAR.text, py: depth === 0 ? 0.8 : 0.6, fontSize: depth === 0 ? 14 : 13.5,
          fontWeight: depth === 0 ? 600 : 500,
          '&.active': {
            background: SIDEBAR.active, color: '#fff',
            '&::before': { content: '""', position: 'absolute', insetInlineStart: 0, top: 6, bottom: 6, width: 3, borderRadius: 3, bgcolor: SIDEBAR.activeBar },
          },
        }}>
        {depth === 0 && <Icon name={item.id} size={18} />}
        <Box component="span" sx={{ flexGrow: 1 }}>{label}</Box>
        {planned && (
          <Box component="span" sx={{ fontSize: 10, fontWeight: 600, px: 0.75, borderRadius: 1, border: `1px solid ${SIDEBAR.border}`, color: SIDEBAR.muted }}>
            P{item.phase}
          </Box>
        )}
      </Box>
    </Box>
  );
}

const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <Stack sx={{ height: '100%', background: SIDEBAR.background, color: SIDEBAR.text }}>
      <Stack direction="row" alignItems="center" spacing={1.5} sx={{ px: 2.5, height: 64, flexShrink: 0, borderBottom: `1px solid ${SIDEBAR.border}` }}>
        <Box sx={{
          width: 36, height: 36, borderRadius: 2.5, display: 'grid', placeItems: 'center', color: '#fff',
          background: 'linear-gradient(135deg, #6366f1 0%, #0ea5e9 100%)', boxShadow: '0 4px 14px rgba(99,102,241,0.45)',
        }}>
          <Icon name="shieldKey" size={20} />
        </Box>
        <Box sx={{ lineHeight: 1.15 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 15, color: '#fff', letterSpacing: '-0.01em' }}>IAM · PAM</Typography>
          <Typography sx={{ fontSize: 11.5, color: SIDEBAR.muted, fontWeight: 500 }}>Enterprise Control Plane</Typography>
        </Box>
      </Stack>
      <Box component="nav" aria-label="main navigation" sx={{
        flexGrow: 1, overflowY: 'auto', px: 1.5, py: 2,
        '&::-webkit-scrollbar': { width: 6 }, '&::-webkit-scrollbar-thumb': { bgcolor: 'rgba(148,163,184,0.25)', borderRadius: 3 },
      }}>
        <Box component="ul" sx={{ m: 0, p: 0, display: 'grid', gap: 0.25 }}>
          {NAVIGATION.map((item) => <NavEntry key={item.id} item={item} depth={0} onNavigate={onNavigate} />)}
        </Box>
      </Box>
      <Box sx={{ px: 2.5, py: 1.5, borderTop: `1px solid ${SIDEBAR.border}`, fontSize: 11.5, color: SIDEBAR.muted }}>v1.0 · Phase 6</Box>
    </Stack>
  );
}

/** Jump-to-page search over the navigation (available pages only). */
function PageSearch() {
  const { t } = useLocale();
  const navigate = useNavigate();
  const k = useTokens();
  const nav = t.nav as Record<string, string>;
  const inputRef = useRef<HTMLInputElement>(null);
  const options = useMemo(() => flatten().filter((i) => !i.children && isAvailable(i)).map((i) => ({ id: i.id, path: i.path, label: nav[i.id] ?? i.id })), [nav]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <Autocomplete size="small" options={options} getOptionLabel={(o) => o.label} blurOnSelect clearOnBlur value={null}
      onChange={(_, v) => { if (v) navigate(v.path); }} sx={{ width: { xs: 180, md: 340 } }}
      renderInput={(params) => (
        <TextField {...params} inputRef={inputRef} placeholder={t.searchPages} slotProps={{
          input: {
            ...params.InputProps,
            sx: { bgcolor: k.muted, borderRadius: 2.5, '& fieldset': { borderColor: 'transparent !important' } },
            startAdornment: <InputAdornment position="start"><Icon name="search" size={16} style={{ opacity: 0.6 }} /></InputAdornment>,
            endAdornment: (
              <Box component="kbd" sx={{ display: { xs: 'none', md: 'block' }, fontSize: 11, px: 0.75, py: 0.1, borderRadius: 1, border: `1px solid ${k.border}`, color: 'text.secondary', fontFamily: 'inherit' }}>
                Ctrl K
              </Box>
            ),
          },
        }} />
      )} />
  );
}

/** Application frame: dark sidebar, glass header with page search, notifications, theme, language and the user menu. */
export function Shell({ me, children }: { me: EffectiveAccess; children: ReactNode }) {
  const { t, toggle, locale } = useLocale();
  const { mode, toggle: toggleMode } = useColorMode();
  const k = useTokens();
  const theme = useTheme();
  const navigate = useNavigate();
  const desktop = useMediaQuery(theme.breakpoints.up('md'), { defaultMatches: true });
  const [mobileOpen, setMobileOpen] = useState(false);
  const [menu, setMenu] = useState<HTMLElement | null>(null);
  const [pending, setPending] = useState(0);
  const location = useLocation();
  const current = flatten().find((i) => i.path === location.pathname && !i.children);
  const parent = current ? NAVIGATION.find((n) => n.children?.includes(current)) : undefined;
  const nav = t.nav as Record<string, string>;
  useEffect(() => {
    apiFetch<unknown[]>('/api/v1/approvals').then((a) => setPending(a.length), () => setPending(0));
  }, [location.pathname]);
  const iconBtn = { width: 38, height: 38, borderRadius: 2.5, border: `1px solid ${k.border}`, bgcolor: 'background.paper', color: 'text.secondary' } as const;
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      {desktop ? (
        <Box sx={{ width: SIDEBAR_WIDTH, flexShrink: 0, position: 'sticky', top: 0, height: '100vh' }}>
          <Sidebar />
        </Box>
      ) : (
        <Drawer open={mobileOpen} onClose={() => setMobileOpen(false)} slotProps={{ paper: { sx: { width: SIDEBAR_WIDTH, border: 0 } } }}>
          <Sidebar onNavigate={() => setMobileOpen(false)} />
        </Drawer>
      )}
      <Box sx={{ flexGrow: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Box component="header" sx={{
          position: 'sticky', top: 0, zIndex: 10, height: 64, display: 'flex', alignItems: 'center', gap: 1.5, px: { xs: 2, md: 4 },
          borderBottom: `1px solid ${k.border}`, bgcolor: k.header, backdropFilter: 'saturate(180%) blur(12px)',
        }}>
          {!desktop && <IconButton aria-label="menu" onClick={() => setMobileOpen(true)} sx={iconBtn}><Icon name="menu" /></IconButton>}
          <Box sx={{ minWidth: 0, flexShrink: 1 }}>
            <Typography variant="h6" component="h1" noWrap sx={{ fontSize: 12, fontWeight: 500, color: 'text.secondary', lineHeight: 1.3 }}>{t.appTitle}</Typography>
            <Typography noWrap sx={{ fontSize: 15, fontWeight: 700, color: 'text.primary', letterSpacing: '-0.01em' }}>
              {parent && <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500 }}>{nav[parent.id]} / </Box>}
              {current ? nav[current.id] : nav.dashboard}
            </Typography>
          </Box>
          <Box sx={{ flexGrow: 1, display: 'flex', justifyContent: 'center', px: 2 }}>{desktop && <PageSearch />}</Box>
          <Tooltip title={nav.approvals}>
            <IconButton aria-label={nav.approvals} onClick={() => navigate('/identity/approvals')} sx={iconBtn}>
              <Badge badgeContent={pending} color="error" overlap="circular"><Icon name="bell" size={18} /></Badge>
            </IconButton>
          </Tooltip>
          <Tooltip title={mode === 'light' ? t.darkMode : t.lightMode}>
            <IconButton aria-label="toggle color mode" onClick={toggleMode} sx={iconBtn}><Icon name={mode === 'light' ? 'moon' : 'sun'} size={18} /></IconButton>
          </Tooltip>
          <Button onClick={toggle} lang={locale === 'en' ? 'ar' : 'en'} variant="outlined" startIcon={<Icon name="languages" size={16} />}
            sx={{ height: 38, borderRadius: 2.5, bgcolor: 'background.paper' }}>
            {t.switchLanguage}
          </Button>
          <Button onClick={(e) => setMenu(e.currentTarget)} aria-label={t.signedInAs} sx={{ height: 44, px: 1, borderRadius: 2.5, color: 'text.primary', gap: 1.25 }}>
            <Avatar sx={{ width: 34, height: 34, fontSize: 13, fontWeight: 700, background: 'linear-gradient(135deg, #6366f1 0%, #0ea5e9 100%)' }}>
              {initials(me.displayName || me.username)}
            </Avatar>
            <Box sx={{ display: { xs: 'none', lg: 'block' }, textAlign: 'start', lineHeight: 1.2 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 700 }}>{me.displayName}</Typography>
              <Typography sx={{ fontSize: 11.5, color: 'text.secondary', fontWeight: 500 }}>{me.grants[0]?.roleCode ?? me.username}</Typography>
            </Box>
          </Button>
          <Menu anchorEl={menu} open={!!menu} onClose={() => setMenu(null)} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            transformOrigin={{ vertical: 'top', horizontal: 'right' }} slotProps={{ paper: { sx: { mt: 1, minWidth: 240, borderRadius: 3, border: `1px solid ${k.border}` } } }}>
            <Box sx={{ px: 2, py: 1.5 }}>
              <Typography sx={{ fontWeight: 700, fontSize: 14 }}>{me.displayName} ({me.username})</Typography>
              <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{me.grants.map((g) => g.roleCode).join(', ')}</Typography>
            </Box>
            <Divider />
            <Box component="form" method="post" action="/logout" sx={{ m: 0 }}>
              <input type="hidden" name="_csrf" value={decodeURIComponent(readCookie('XSRF-TOKEN') ?? '')} />
              <MenuItem component="button" type="submit" sx={{ width: '100%', color: 'error.main', py: 1.25 }}>
                <ListItemIcon sx={{ color: 'inherit' }}><Icon name="logout" /></ListItemIcon>
                {t.signOut}
              </MenuItem>
            </Box>
          </Menu>
        </Box>
        <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, width: '100%', maxWidth: 1560, mx: 'auto' }}>
          {children}
        </Box>
      </Box>
    </Box>
  );
}
