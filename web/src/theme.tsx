import { type ReactNode, useMemo } from 'react';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';

/** MUI theme + Emotion cache that flips styles for RTL locales. */
export function DirectionalTheme({ direction, children }: { direction: 'ltr' | 'rtl'; children: ReactNode }) {
  const cache = useMemo(
    () =>
      createCache({
        key: direction === 'rtl' ? 'mui-rtl' : 'mui',
        stylisPlugins: direction === 'rtl' ? [prefixer, rtlPlugin] : [prefixer],
      }),
    [direction],
  );
  const theme = useMemo(() => createTheme({
    direction,
    palette: {
      mode: 'light',
      primary: { main: '#4f46e5', dark: '#3730a3', light: '#818cf8' },
      secondary: { main: '#0ea5e9' },
      success: { main: '#16a34a' },
      warning: { main: '#d97706' },
      error: { main: '#dc2626' },
      background: { default: '#f4f6fb', paper: '#ffffff' },
      text: { primary: '#0f172a', secondary: '#64748b' },
      divider: '#e2e8f0',
    },
    shape: { borderRadius: 12 },
    typography: {
      fontFamily: direction === 'rtl'
        ? '"Segoe UI", Tahoma, "Noto Sans Arabic", "Geeza Pro", Arial, sans-serif'
        : 'Inter, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
      h4: { fontWeight: 700, letterSpacing: '-0.02em' },
      h5: { fontWeight: 700, letterSpacing: '-0.01em' },
      h6: { fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    components: {
      MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
      MuiCard: { defaultProps: { elevation: 0 }, styleOverrides: { root: { border: '1px solid #e2e8f0', boxShadow: '0 1px 2px rgba(15,23,42,0.04)' } } },
      MuiButton: { defaultProps: { disableElevation: true } },
      MuiChip: { styleOverrides: { root: { fontWeight: 600 } } },
      MuiTableCell: { styleOverrides: { head: { fontWeight: 600, color: '#475569', backgroundColor: '#f8fafc' } } },
      MuiDialog: { styleOverrides: { paper: { borderRadius: 16 } } },
    },
  }), [direction]);
  return (
    <CacheProvider value={cache}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </CacheProvider>
  );
}
