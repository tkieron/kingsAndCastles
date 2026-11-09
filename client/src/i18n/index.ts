import { en, type Key } from './en';
import { pl, plHints } from './pl';

export type Lang = 'en' | 'pl';
export type { Key };

const STORE = 'kc.lang';
let lang: Lang = load();

function load(): Lang {
  try {
    const saved = localStorage.getItem(STORE);
    if (saved === 'en' || saved === 'pl') return saved;
  } catch {
    /* private mode */
  }
  return 'en';
}

export function getLang(): Lang {
  return lang;
}

export function setLang(l: Lang): void {
  lang = l;
  try {
    localStorage.setItem(STORE, l);
  } catch {
    /* private mode */
  }
  document.documentElement.lang = l;
}

export function hasKey(key: string): key is Key {
  return key in en;
}

/** Translates a key and fills {placeholders}. Unknown keys come back as they are, so nothing crashes. */
export function t(key: Key | string, params: Record<string, unknown> = {}): string {
  const dict = lang === 'pl' ? pl : en;
  const text = hasKey(key) ? dict[key] : key;
  return text.replace(/\{(\w+)\}/g, (_, name: string) => (name in params ? String(params[name]) : `{${name}}`));
}

/**
 * The tooltip for a glossary word. In English the child sees the Polish word and a short explanation;
 * in Polish they see the English word, so they learn it too.
 */
export function tipFor(key: Key): string | undefined {
  if (lang === 'pl') {
    return en[key];
  }
  const word = pl[key];
  const hint = plHints[key];
  if (!hint) return word === en[key] ? undefined : word;
  return `${word} – ${hint}`;
}
