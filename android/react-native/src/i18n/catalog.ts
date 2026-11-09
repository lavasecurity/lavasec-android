// Canonical catalog access for the Android launcher (plan 309 F22).
//
// The message key is the canonical SOURCE string, so reuse means "look up the
// pinned translation", never "copy English". User-authored values (filter names,
// domains, provider identifiers) are data and must be rendered verbatim; they
// must never pass through this catalog or a coincidental key collision could
// rewrite a user's name.
import {translations} from '../../canonical/app/translations';
import {CANONICAL_LOCALE, type LavaLocale} from './locales';

/** The canonical table for one shipped locale (always present in the catalog). */
export function canonicalCatalog(locale: LavaLocale): Record<string, string> {
  return translations[CANONICAL_LOCALE[locale]] ?? {};
}

/**
 * Fallback order for a message: override catalog -> base language -> canonical
 * English -> the source string. The final source-string fallback keeps an
 * untranslated key readable and never exposes a key or internal identifier.
 */
export function catalogFallbackChain(locale: LavaLocale): readonly Record<string, string>[] {
  const chain: Record<string, string>[] = [];
  const primary = translations[CANONICAL_LOCALE[locale]];
  if (primary) chain.push(primary);
  const base = translations[locale.split('-')[0]!];
  if (base && !chain.includes(base)) chain.push(base);
  const english = translations['en'];
  if (locale !== 'en' && english && !chain.includes(english)) chain.push(english);
  return chain;
}
