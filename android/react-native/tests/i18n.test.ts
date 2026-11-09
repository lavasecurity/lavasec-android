import {renderHook} from '@testing-library/react-native';
import {
  CANONICAL_LOCALE,
  LAVA_LOCALES,
  currentLavaLocale,
  format,
  localized,
  localizedNumber,
  resolveLavaLocale,
  setLavaLocale,
  useLavaStrings,
} from '../src/i18n';
import {translations} from '../canonical/app/translations';

afterEach(() => setLavaLocale('en'));

test('the inventory is exactly the ten shipped Android locales', () => {
  expect([...LAVA_LOCALES]).toEqual(['en', 'de', 'es', 'fr', 'it', 'ja', 'ko', 'pt-BR', 'zh-CN', 'zh-TW']);
  for (const locale of LAVA_LOCALES) {
    expect(translations[CANONICAL_LOCALE[locale]]).toBeDefined();
  }
});

test.each([
  [undefined, 'en'],
  [null, 'en'],
  ['', 'en'],
  ['en', 'en'],
  ['en-US', 'en'],
  ['de', 'de'],
  ['de-DE', 'de'],
  ['fr-CA', 'fr'],
  ['pt', 'pt-BR'],
  ['pt_BR', 'pt-BR'],
  ['pt-PT', 'pt-BR'],
  ['zh_CN', 'zh-CN'],
  ['zh-Hans', 'zh-CN'],
  ['zh-Hant', 'zh-TW'],
  ['zh-TW', 'zh-TW'],
  ['zh-HK', 'zh-TW'],
  ['zh-Hans-HK', 'zh-CN'],
  ['zh-Hans-TW', 'zh-CN'],
  ['zh-Hant-CN', 'zh-TW'],
  ['ZH-hant-mo', 'zh-TW'],
  ['zh_MO', 'zh-TW'],
  ['ko-KR', 'ko'],
  ['xx-YY', 'en'],
])('resolveLavaLocale(%p) -> %s', (input, expected) => {
  expect(resolveLavaLocale(input)).toBe(expected);
});

test('resolution can never yield a locale outside the inventory', () => {
  const probes = ['', 'not-a-locale', 'en', 'zh-Hant', 'pt', 'xx', 'zh-Hans', 'ja-JP', 'ko'];
  for (const probe of probes) {
    expect(LAVA_LOCALES).toContain(resolveLavaLocale(probe));
  }
});

test('the Chinese inventory tags resolve to the canonical script catalogs', () => {
  expect(translations[CANONICAL_LOCALE['zh-CN']]?.['Settings']).toBe('设置');
  expect(translations[CANONICAL_LOCALE['zh-TW']]?.['Settings']).toBe('設定');
  setLavaLocale('zh-CN');
  expect(localized('Settings')).toBe(translations[CANONICAL_LOCALE['zh-CN']]?.['Settings']);
  expect(localized('Settings')).not.toBe('Settings');
  // The current iOS catalog translates this navigation surface in both scripts.
  expect(localized('Guard')).toBe('防护');
  setLavaLocale('zh-TW');
  expect(localized('Guard')).toBe('防護');
});

test('an untranslated key falls back to the readable source string', () => {
  setLavaLocale('de');
  expect(localized('__no_such_message__')).toBe('__no_such_message__');
});

test('format substitutes translated placeholders and keeps positional forms', () => {
  setLavaLocale('en');
  expect(format('%d of %d', 1, 3)).toBe('1 of 3');
  expect(format('%% literal', 9)).toBe('% literal');
  setLavaLocale('zh-CN');
  const translated = translations[CANONICAL_LOCALE['zh-CN']]?.['%@ rules'] ?? '%@ rules';
  expect(format('%@ rules', 5)).toBe(translated.replace('%@', '5'));
});

test('number rendering follows the resolved locale', () => {
  setLavaLocale('en');
  expect(localizedNumber(1234)).toBe((1234).toLocaleString('en'));
});

test('useLavaStrings reads the owner presentation locale and re-renders', () => {
  const {result, rerender} = renderHook(({locale}: {locale: string}) => useLavaStrings(locale), {
    initialProps: {locale: 'zh-CN'},
  });
  expect(result.current.locale).toBe('zh-CN');
  expect(result.current.t('Guard')).toBe(translations[CANONICAL_LOCALE['zh-CN']]?.['Guard']);
  rerender({locale: 'de'});
  expect(result.current.locale).toBe('de');
  expect(result.current.t('Guard')).toBe(translations[CANONICAL_LOCALE['de']]?.['Guard']);
  rerender({locale: 'unknown-tag'});
  expect(result.current.locale).toBe('en');
});

test('the module locale is used when no override is supplied', () => {
  setLavaLocale('ja');
  expect(currentLavaLocale()).toBe('ja');
  const {result} = renderHook(() => useLavaStrings());
  expect(result.current.locale).toBe('ja');
});
