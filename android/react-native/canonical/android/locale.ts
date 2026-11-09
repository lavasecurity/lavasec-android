import {CANONICAL_LOCALE, resolveLavaLocale, setLavaLocale} from '../../src/i18n';
import type {Presentation} from '../app/presentation';

/**
 * Normalise the owner wire locale to a canonical catalog key before the shared presentation layer
 * consumes it (plan 309 F22/V2). The native `presentation.locale` is usually already canonical
 * (`zh-Hans`), but an Android owner or a persisted preference can report an Android/BCP-47 tag
 * (`zh_CN`, `zh-TW`, `pt-PT`). The shared `localized()` looks the value up directly in the
 * canonical `translations` table, so an unconverted tag falls back to English. Resolving through
 * one authority (`resolveLavaLocale` -> `CANONICAL_LOCALE`) keeps the module locale and the React
 * `useLavaStrings` consumers in agreement, and `setLavaLocale` seeds the non-React path.
 */
export function applyAndroidLocale(value?: Presentation): Presentation {
  const wire = value?.locale;
  setLavaLocale(wire);
  return {locale: CANONICAL_LOCALE[resolveLavaLocale(wire)], textScales: value?.textScales ?? null};
}
