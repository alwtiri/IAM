import { createContext, type ReactNode, useContext, useMemo } from 'react';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';

export type ColorMode = 'light' | 'dark';

/**
 * Design tokens: soft neutral surfaces, 1px borders, 14px cards, indigo brand colour, dark enterprise sidebar,
 * light and dark mode. Status colours are kept for chips and alerts.
 */
export const TOKENS = {
  light: {
    background: '#f5f7fb', foreground: '#0f172a', card: '#ffffff', muted: '#f1f5f9', mutedForeground: '#64748b', border: '#e6e8ef',
    primary: '#4f46e5', primaryForeground: '#ffffff', accent: '#eef2ff', ring: '#818cf8', header: 'rgba(255,255,255,0.8)',
    chart: ['#f43f5e', '#6366f1', '#0ea5e9', '#f59e0b', '#10b981'],
  },
  dark: {
    background: '#0b0f19', foreground: '#e5e7eb', card: '#111827', muted: '#1f2937', mutedForeground: '#94a3b8', border: '#1f2937',
    primary: '#6366f1', primaryForeground: '#ffffff', accent: '#1e1b4b', ring: '#6366f1', header: 'rgba(11,15,25,0.8)',
    chart: ['#fb7185', '#818cf8', '#38bdf8', '#fbbf24', '#34d399'],
  },
} as const;

/** The sidebar stays dark in both modes (enterprise console look). */
export const SIDEBAR = {
  background: 'linear-gradient(180deg, #0b1220 0%, #0f172a 60%, #111a33 100%)', text: '#cbd5e1', muted: '#64748b', border: 'rgba(148,163,184,0.12)',
  hover: 'rgba(255,255,255,0.06)', active: 'linear-gradient(90deg, rgba(99,102,241,0.28) 0%, rgba(99,102,241,0.06) 100%)', activeBar: '#818cf8',
};

/** Soft coloured tiles for icons and KPI accents. */
export const TONES = {
  indigo: { fg: '#4f46e5', bg: 'rgba(79,70,229,0.10)' },
  sky: { fg: '#0284c7', bg: 'rgba(2,132,199,0.10)' },
  amber: { fg: '#d97706', bg: 'rgba(217,119,6,0.12)' },
  rose: { fg: '#e11d48', bg: 'rgba(225,29,72,0.10)' },
  emerald: { fg: '#059669', bg: 'rgba(5,150,105,0.10)' },
  violet: { fg: '#7c3aed', bg: 'rgba(124,58,237,0.10)' },
  slate: { fg: '#475569', bg: 'rgba(71,85,105,0.10)' },
} as const;
export type Tone = keyof typeof TONES;

export const ColorModeContext = createContext<{ mode: ColorMode; toggle: () => void }>({ mode: 'light', toggle: () => undefined });

export function useColorMode() {
  return useContext(ColorModeContext);
}

export function useTokens() {
  return TOKENS[useColorMode().mode];
}

const FONT = 'Inter, "Segoe UI Variable", "Geist", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';
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
        secondary: { main: mode === 'light' ? '#0ea5e9' : '#38bdf8' },
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
          styleOverrides: { root: { border: `1px solid ${k.border}`, borderRadius: 14, boxShadow: mode === 'light' ? '0 1px 2px rgba(16,24,40,0.04), 0 1px 3px rgba(16,24,40,0.06)' : 'none' } },
        },
        MuiCardContent: { styleOverrides: { root: { padding: 24, '&:last-child': { paddingBottom: 24 } } } },
        MuiButton: {
          defaultProps: { disableElevation: true },
          styleOverrides: {
            root: { borderRadius: 8, fontWeight: 600, paddingInline: 14, minHeight: 36, whiteSpace: 'nowrap' },
            containedPrimary: { background: `linear-gradient(135deg, ${k.primary} 0%, #6366f1 100%)`, boxShadow: '0 1px 2px rgba(79,70,229,0.3)' },
            sizeSmall: { minHeight: 30, paddingInline: 10, fontSize: 13 },
            outlined: { borderColor: k.border, color: k.foreground, backgroundColor: k.background, '&:hover': { backgroundColor: k.accent, borderColor: k.border } },
            textPrimary: { color: k.primary, '&:hover': { backgroundColor: k.accent } },
          },
        },
        MuiChip: {
          variants: (['success', 'error', 'warning', 'info'] as const).map((color) => {
            const soft = {
              success: mode === 'light' ? ['#dcfce7', '#15803d'] : ['rgba(34,197,94,0.15)', '#4ade80'],
              error: mode === 'light' ? ['#fee2e2', '#b91c1c'] : ['rgba(239,68,68,0.15)', '#f87171'],
              warning: mode === 'light' ? ['#fef3c7', '#b45309'] : ['rgba(245,158,11,0.15)', '#fbbf24'],
              info: mode === 'light' ? ['#dbeafe', '#1d4ed8'] : ['rgba(59,130,246,0.15)', '#60a5fa'],
            }[color];
            return { props: { variant: 'filled' as const, color }, style: { backgroundColor: soft[0], color: soft[1] } };
          }),
          styleOverrides: {
            root: { borderRadius: 6, fontWeight: 600, fontSize: 12 },
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
        MuiTableContainer: { styleOverrides: { root: { border: `1px solid ${k.border}`, borderRadius: 14, backgroundColor: k.card } } },
        MuiTableCell: {
          styleOverrides: {
            root: { borderColor: k.border, paddingTop: 10, paddingBottom: 10 },
            head: { fontWeight: 600, color: k.mutedForeground, backgroundColor: mode === 'light' ? '#f8fafc' : '#0f172a', fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.04em' },
          },
        },
        MuiTableRow: { styleOverrides: { root: { '&:hover td': { backgroundColor: mode === 'light' ? '#f8fafc' : '#0f172a' }, '&:last-child td': { borderBottom: 0 } } } },
        MuiDialog: { styleOverrides: { paper: { borderRadius: 12, border: `1px solid ${k.border}`, boxShadow: '0 10px 38px -10px rgb(0 0 0 / 0.35)' } } },
        MuiAlert: { styleOverrides: { root: { borderRadius: 8, border: '1px solid', borderColor: 'currentColor' } } },
        MuiTabs: { styleOverrides: { root: { minHeight: 40 }, indicator: { backgroundColor: k.primary, height: 2, borderRadius: 2 } } },
        MuiTab: { styleOverrides: { root: { minHeight: 36, textTransform: 'none', fontWeight: 500, '&.Mui-selected': { color: k.foreground } } } },
        MuiLinearProgress: { styleOverrides: { root: { borderRadius: 4, backgroundColor: k.muted } } },
        MuiTooltip: { styleOverrides: { tooltip: { backgroundColor: '#0f172a', color: '#f8fafc', borderRadius: 6, fontSize: 12 } } },
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
