import { createContext, useContext } from 'react';
import { messages, type Locale, type Messages } from './messages';

export interface LocaleState {
  locale: Locale;
  t: Messages;
  toggle: () => void;
}

export const LocaleContext = createContext<LocaleState>({ locale: 'en', t: messages.en, toggle: () => undefined });

export function useLocale(): LocaleState {
  return useContext(LocaleContext);
}
