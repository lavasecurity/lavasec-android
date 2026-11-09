// Locale inventory for the Android launcher (plan 309 F22).
//
// The inventory is exactly the ten locales the Android resources already ship
// (`android/presentation/src/main/res/values*`). The canonical iOS catalog
// (`canonical/app/translations.ts`, generated from `Localizable.xcstrings`)
// spells the Chinese locales `zh-Hans` / `zh-Hant`; this file owns the mapping
// and nothing else is translated or invented here.

export const LAVA_LOCALES = ['en', 'de', 'es', 'fr', 'it', 'ja', 'ko', 'pt-BR', 'zh-CN', 'zh-TW'] as const;

export type LavaLocale = (typeof LAVA_LOCALES)[number];

/** Android inventory tag -> canonical catalog key. Every entry exists in the catalog. */
export const CANONICAL_LOCALE: Readonly<Record<LavaLocale, string>> = {
  en: 'en',
  de: 'de',
  es: 'es',
  fr: 'fr',
  it: 'it',
  ja: 'ja',
  ko: 'ko',
  'pt-BR': 'pt-BR',
  'zh-CN': 'zh-Hans',
  'zh-TW': 'zh-Hant',
};

/**
 * Normalise any owner/platform locale tag to one of the ten shipped locales.
 *
 * Accepts Java/Android tags (`zh_CN`, `pt_BR`), BCP-47 tags with a script or
 * region (`zh-Hans`, `en-US`, `fr-CA`) and unknown input, which resolves to
 * `en`. The result is always a member of {@link LAVA_LOCALES}.
 */
export function resolveLavaLocale(input?: string | null): LavaLocale {
  const raw = typeof input === 'string' ? input.trim().replace(/_/g, '-') : '';
  if (raw.length === 0) return 'en';
  const parts = raw.toLowerCase().split('-');
  // Select the most specific supported prefix before any region fallback.
  // An explicit zh-Hans script remains Simplified even in HK or TW.
  for (let length = parts.length; length > 0; length--) {
    const candidate = parts.slice(0, length).join('-');
    const match = LAVA_LOCALES.find(
      locale => locale.toLowerCase() === candidate || CANONICAL_LOCALE[locale].toLowerCase() === candidate,
    );
    if (match) return match;
  }
  if (parts[0] === 'zh') return parts.includes('hant') || parts.some(part => ['tw', 'hk', 'mo'].includes(part)) ? 'zh-TW' : 'zh-CN';
  if (parts[0] === 'pt') return 'pt-BR';
  return 'en';
}
