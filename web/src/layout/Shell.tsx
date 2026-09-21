import { useState, type ReactNode } from 'react';
import { NavLink } from 'react-router';
import {
  AppBar, Box, Button, Chip, Collapse, Drawer, List, ListItemButton, ListItemText, Toolbar, Typography,
} from '@mui/material';
import { NAVIGATION, isAvailable, type NavItem } from '../navigation';
import { useLocale } from '../i18n/LocaleContext';
import type { EffectiveAccess } from '../api/types';
import { readCookie } from '../api/client';

const DRAWER_WIDTH = 280;

function NavEntry({ item, depth }: { item: NavItem; depth: number }) {
  const { t } = useLocale();
  const [open, setOpen] = useState(item.phase <= 2);
  const label = t.nav[item.id as keyof typeof t.nav] ?? item.id;
  if (item.children) {
    return (
      <>
        <ListItemButton onClick={() => setOpen(!open)} sx={{ ps: 2 + depth * 2 }}>
          <ListItemText primary={label} slotProps={{ primary: { fontWeight: 600 } }} />
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
    <ListItemButton component={NavLink} to={item.path} end sx={{ ps: 2 + depth * 2, opacity: isAvailable(item) ? 1 : 0.6 }}>
      <ListItemText primary={label} />
      {!isAvailable(item) && <Chip size="small" label={`P${item.phase}`} variant="outlined" />}
    </ListItemButton>
  );
}

/** Application frame: header with language switch and user, side navigation (spec §61), content area. */
export function Shell({ me, children }: { me: EffectiveAccess; children: ReactNode }) {
  const { t, toggle, locale } = useLocale();
  return (
    <Box sx={{ display: 'flex' }}>
      <AppBar position="fixed" color="default" elevation={1} sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="h1" sx={{ flexGrow: 1 }}>{t.appTitle}</Typography>
          <Typography variant="body2" aria-label={t.signedInAs}>{me.displayName} ({me.username})</Typography>
          <Button onClick={toggle} lang={locale === 'en' ? 'ar' : 'en'}>{t.switchLanguage}</Button>
          <form method="post" action="/logout">
            <input type="hidden" name="_csrf" value={decodeURIComponent(readCookie('XSRF-TOKEN') ?? '')} />
            <Button type="submit" variant="outlined" size="small">{t.signOut}</Button>
          </form>
        </Toolbar>
      </AppBar>
      <Drawer variant="permanent" sx={{ width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}>
        <Toolbar />
        <List component="nav" aria-label="main navigation">
          {NAVIGATION.map((item) => <NavEntry key={item.id} item={item} depth={0} />)}
        </List>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: 3 }}>
        <Toolbar />
        {children}
      </Box>
    </Box>
  );
}
