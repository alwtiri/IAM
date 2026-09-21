import { fireEvent, render, screen } from '@testing-library/react';
import { App } from './App';
import { messages } from './i18n/messages';

describe('App shell', () => {
  it('renders the platform title in English, left-to-right', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(messages.en.appTitle);
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('switches to Arabic and right-to-left', () => {
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: messages.en.switchLanguage }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(messages.ar.appTitle);
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
  });

  it('keeps every locale complete', () => {
    expect(Object.keys(messages.ar).sort()).toEqual(Object.keys(messages.en).sort());
  });
});
