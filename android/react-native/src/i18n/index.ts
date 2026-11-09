// Minimal localization scaffold over the canonical iOS catalog (plan 309 F22).
//
// The shared app resolves copy through `canonical/app/presentation.localized`,
// which uses the raw owner tag and has no locale inventory or Android spelling
// mapping. This module adds those: {@link resolveLavaLocale} normalises any
// Android/BCP-47/canonical tag to the ten shipped locales, and
// {@link useLavaStrings} is the React path over `snapshot.presentation.locale`.
//
// User-authored values are DATA, not messages: filter names, allowed domains and
// provider identifiers must be rendered verbatim and never sent through `t` /
// `format`. A coincidental key collision could otherwise rewrite a user's name.
import {useMemo} from 'react';
import {catalogFallbackChain} from './catalog';
import {resolveLavaLocale, type LavaLocale} from './locales';

export {LAVA_LOCALES, CANONICAL_LOCALE, resolveLavaLocale} from './locales';
export type {LavaLocale} from './locales';
export {canonicalCatalog, catalogFallbackChain} from './catalog';

let activeLocale: LavaLocale = 'en';

/** Seed the non-React locale (view models, summaries). Returns the resolved locale. */
export function setLavaLocale(input?: string | null): LavaLocale {
  activeLocale = resolveLavaLocale(input);
  return activeLocale;
}

/** The locale last seeded by {@link setLavaLocale}. */
export function currentLavaLocale(): LavaLocale {
  return activeLocale;
}

function lookup(locale: LavaLocale, source: string): string {
  for (const table of catalogFallbackChain(locale)) {
    const value = table[source];
    if (value !== undefined) return value;
  }
  return source;
}

// Native catalog object/integer placeholders, including positional forms. The
// translated format is selected before values are inserted so the key stays intact.
const PLACEHOLDER = /%%|%(?:(\d+)\$)?(?:@|(?:ll)?d)/g;

function formatIn(locale: LavaLocale, source: string, ...values: (string | number)[]): string {
  let next = 0;
  return lookup(locale, source).replace(PLACEHOLDER, (placeholder, index: string | undefined) =>
    placeholder === '%%' ? '%' : String(values[index === undefined ? next++ : Number(index) - 1] ?? placeholder),
  );
}

/** Non-React lookup in the module locale. */
export function localized(source: string): string {
  return lookup(activeLocale, source);
}

/** Non-React format in the module locale. */
export function format(source: string, ...values: (string | number)[]): string {
  return formatIn(activeLocale, source, ...values);
}

/** Locale-aware number rendering in the module locale. */
export function localizedNumber(value: number): string {
  return value.toLocaleString(activeLocale);
}

export type LavaStrings = {
  locale: LavaLocale;
  /** Translate a canonical source string. Never pass user-authored data. */
  t: (source: string) => string;
  /** Translate then insert `%@` / `%d` placeholders. */
  format: (source: string, ...values: (string | number)[]) => string;
  /** Locale-aware number rendering. */
  number: (value: number) => string;
};

/**
 * The React path. Pass the owner's `snapshot.presentation.locale` so a language
 * change re-renders consumers; omit it to read the module locale.
 */
export function useLavaStrings(locale?: string | null): LavaStrings {
  const resolved = useMemo(() => resolveLavaLocale(locale ?? activeLocale), [locale]);
  return useMemo(
    () => ({
      locale: resolved,
      t: (source: string) => lookup(resolved, source),
      format: (source: string, ...values: (string | number)[]) => formatIn(resolved, source, ...values),
      number: (value: number) => value.toLocaleString(resolved),
    }),
    [resolved],
  );
}
