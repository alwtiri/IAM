import { createContext, type ReactNode, useContext, useMemo } from 'react';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';

export type ColorMode = 'light' | 'dark';

/**
 * Design tokens after the shadcn/ui "zinc" palette: neutral surfaces, 1px borders, 8px radius, primary = near-black
 * (near-white in dark mode). Status colours are kept for chips and alerts.
 */
export const TOKENS = {
  light: {
    background: '#ffffff', foreground: '#09090b', card: '#ffffff', muted: '#f4f4f5', mutedForeground: '#71717a', border: '#e4e4e7',
    primary: '#18181b', primaryForeground: '#fafafa', accent: '#f4f4f5', sidebar: '#fafafa', ring: '#a1a1aa', chart: ['#e76e50', '#2a9d90', '#274754', '#e8c468', '#f4a462'],
  },
  dark: {
    background: '#09090b', foreground: '#fafafa', card: '#09090b', muted: '#27272a', mutedForeground: '#a1a1aa', border: '#27272a',
    primary: '#fafafa', primaryForeground: '#18181b', accent: '#27272a', sidebar: '#18181b', ring: '#52525b', chart: ['#2662d9', '#2eb88a', '#e88c30', '#af57db', '#e23670'],
  },
} as const;

export const ColorModeContext = createContext<{ mode: ColorMode; toggle: () => void }>({ mode: 'light', toggle: () => undefined });

export function useColorMode() {
  return useContext(ColorModeContext);
}

export function useTokens() {
  return TOKENS[useColorMode().mode];
}

const FONT = 'Inter, "Geist", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';
const FONT_AR = '"IBM Plex Sans Arabic", "Noto Sans Arabic", "Segoe UI", Tahoma, "Geeza Pro", ui-sans-serif, system-ui, sans-serif';

/** MUI theme + Emotion cache that flips styles for RTL locales. */
export function DirectionalTheme({ direction, mode = 'light', children }: { direction: 'ltr' | 'rtl'; mode?: ColorMode; children: ReactNode }) {
  const cache = useMemo(
    () =>
      createCache({
        key: direction === 'rtl' ? 'mui-rtl' : 'mui',
        stylisPlugins: direction === 'rtl' ? [prefixer, rtlPlugin] : [prefixer],
      }),
    [direction],
  );
  const theme = useMemo(() => {
    const k = TOKENS[mode];
    return createTheme({
      direction,
      palette: {
        mode,
        primary: { main: k.primary, contrastText: k.primaryForeground },
        secondary: { main: mode === 'light' ? '#3f3f46' : '#d4d4d8' },
        success: { main: mode === 'light' ? '#16a34a' : '#22c55e' },
        warning: { main: mode === 'light' ? '#d97706' : '#f59e0b' },
        error: { main: mode === 'light' ? '#dc2626' : '#ef4444' },
        info: { main: mode === 'light' ? '#2563eb' : '#3b82f6' },
        background: { default: k.background, paper: k.card },
        text: { primary: k.foreground, secondary: k.mutedForeground },
        divider: k.border,
        action: { hover: k.accent, selected: k.accent },
      },
      shape: { borderRadius: 8 },
      typography: {
        fontFamily: direction === 'rtl' ? FONT_AR : FONT,
        fontSize: 14,
        h4: { fontWeight: 700, fontSize: 28, letterSpacing: '-0.025em' },
        h5: { fontWeight: 600, fontSize: 20, letterSpacing: '-0.02em' },
        h6: { fontWeight: 600, fontSize: 16 },
        subtitle1: { fontWeight: 600 },
        button: { textTransform: 'none', fontWeight: 500 },
      },
      components: {
        MuiCssBaseline: { styleOverrides: { body: { WebkitFontSmoothing: 'antialiased' } } },
        MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
        MuiCard: {
          defaultProps: { elevation: 0 },
          styleOverrides: { root: { border: `1px solid ${k.border}`, borderRadius: 12, boxShadow: '0 1px 2px 0 rgb(0 0 0 / 0.05)' } },
        },
        MuiCardContent: { styleOverrides: { root: { padding: 24, '&:last-child': { paddingBottom: 24 } } } },
        MuiButton: {
          defaultProps: { disableElevation: true },
          styleOverrides: {
            root: { borderRadius: 6, fontWeight: 500, paddingInline: 14, minHeight: 36, whiteSpace: 'nowrap' },
            sizeSmall: { minHeight: 30, paddingInline: 10, fontSize: 13 },
            outlined: { borderColor: k.border, color: k.foreground, backgroundColor: k.background, '&:hover': { backgroundColor: k.accent, borderColor: k.border } },
            text: { color: k.foreground, '&:hover': { backgroundColor: k.accent } },
          },
        },
        MuiChip: {
          styleOverrides: {
            root: { borderRadius: 6, fontWeight: 500, fontSize: 12 },
            sizeSmall: { height: 22 },
            outlined: { borderColor: k.border },
          },
        },
        MuiOutlinedInput: {
          styleOverrides: {
            root: {
              borderRadius: 6,
              '& .MuiOutlinedInput-notchedOutline': { borderColor: k.border },
              '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: k.ring, borderWidth: 1, boxShadow: `0 0 0 3px ${k.ring}33` },
            },
          },
        },
        MuiTableContainer: { styleOverrides: { root: { border: `1px solid ${k.border}`, borderRadius: 12 } } },
        MuiTableCell: {
          styleOverrides: {
            root: { borderColor: k.border, paddingTop: 10, paddingBottom: 10 },
            head: { fontWeight: 500, color: k.mutedForeground, backgroundColor: 'transparent', fontSize: 13 },
          },
        },
        MuiTableRow: { styleOverrides: { root: { '&:hover td': { backgroundColor: mode === 'light' ? '#fafafa' : '#18181b' } } } },
        MuiDialog: { styleOverrides: { paper: { borderRadius: 12, border: `1px solid ${k.border}`, boxShadow: '0 10px 38px -10px rgb(0 0 0 / 0.35)' } } },
        MuiAlert: { styleOverrides: { root: { borderRadius: 8, border: '1px solid', borderColor: 'currentColor' } } },
        MuiTabs: { styleOverrides: { root: { minHeight: 36 }, indicator: { backgroundColor: k.primary, height: 2 } } },
        MuiTab: { styleOverrides: { root: { minHeight: 36, textTransform: 'none', fontWeight: 500, '&.Mui-selected': { color: k.foreground } } } },
        MuiLinearProgress: { styleOverrides: { root: { borderRadius: 4, backgroundColor: k.muted } } },
        MuiTooltip: { styleOverrides: { tooltip: { backgroundColor: k.primary, color: k.primaryForeground, borderRadius: 6, fontSize: 12 } } },
      },
    });
  }, [direction, mode]);
  return (
    <CacheProvider value={cache}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </CacheProvider>
  );
}
