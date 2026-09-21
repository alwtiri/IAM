import { useEffect, useState } from 'react';
import { AppBar, Box, Button, Chip, Container, Toolbar, Typography } from '@mui/material';
import { DirectionalTheme } from './theme';
import { directionOf, messages, type Locale } from './i18n/messages';

/**
 * Phase 1 application shell. Shows platform identity and language/RTL support only.
 * The baseline navigation (spec §61), authentication (BFF) and pages arrive in Phase 2.
 */
export function App({ initialLocale = 'en' }: { initialLocale?: Locale }) {
  const [locale, setLocale] = useState<Locale>(initialLocale);
  const t = messages[locale];
  const direction = directionOf(locale);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = direction;
  }, [locale, direction]);

  return (
    <DirectionalTheme direction={direction}>
      <AppBar position="static" color="default" elevation={1}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="h1" sx={{ flexGrow: 1 }}>
            {t.appTitle}
          </Typography>
          <Button onClick={() => setLocale(locale === 'en' ? 'ar' : 'en')} lang={locale === 'en' ? 'ar' : 'en'}>
            {t.switchLanguage}
          </Button>
        </Toolbar>
      </AppBar>
      <Container maxWidth="md" sx={{ py: 6 }}>
        <Typography variant="h5" component="p" gutterBottom>
          {t.appSubtitle}
        </Typography>
        <Typography color="text.secondary" gutterBottom>
          {t.phaseNotice}
        </Typography>
        <Box sx={{ mt: 3, display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography component="span">{t.healthLabel}:</Typography>
          <Chip label={t.healthUnknown} variant="outlined" />
        </Box>
      </Container>
    </DirectionalTheme>
  );
}
