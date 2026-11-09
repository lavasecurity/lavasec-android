/**
 * Locale production-wiring contract (plan 309 F22/V2).
 *
 * The confirmed defect was that a raw Android/BCP-47 tag reached the shared `localized()` lookup
 * and fell back to English. This traverses the real chain — native wire -> `projectAndroidSnapshot`
 * -> the Android `applyAndroidLocale` adapter used by the production root -> the canonical catalog
 * — rather than testing the resolver helper alone.
 */
import {createElement} from 'react';
import {render, screen} from '@testing-library/react-native';
import {NativeModules} from 'react-native';
import {productFixture} from './productFixture';
import {projectAndroidSnapshot} from '../canonical/android/runtime';
import {applyAndroidLocale} from '../canonical/android/locale';
import {currentLavaLocale, format, localized} from '../src/i18n';
import {translations} from '../canonical/app/translations';
import {configurePresentation, localizedFormat, PresentationContext, Text} from '../canonical/app/presentation';

const port = {getSnapshot: jest.fn(), command: jest.fn()};
NativeModules.NativeLavaApp = port;

afterEach(() => {
  applyAndroidLocale({locale: 'en', textScales: null});
  configurePresentation();
  jest.restoreAllMocks();
});

test.each([
  ['zh-CN', 'zh-Hans'],
  ['zh_TW', 'zh-Hant'],
  ['zh-Hans', 'zh-Hans'],
  ['zh-Hant', 'zh-Hant'],
  ['pt-PT', 'pt-BR'],
  ['pt-BR', 'pt-BR'],
  ['en-US', 'en'],
  ['xx-YY', 'en'],
])('the production root normalises %s to the canonical catalog key %s', (wire, canonical) => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: wire, textScales: null}});
  const applied = applyAndroidLocale(projected.presentation);
  expect(applied.locale).toBe(canonical);
  expect(currentLavaLocale()).toBeDefined();
});

test('a Chinese Android tag renders Chinese through the shared catalog, never the English source', () => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: 'zh-CN', textScales: null}});
  applyAndroidLocale(projected.presentation);
  const source = 'Settings';
  expect(localized(source)).toBe(translations['zh-Hans']?.[source]);
  expect(localized(source)).not.toBe(source);
});

test('a Traditional Chinese tag resolves to the Traditional catalog, not the Simplified one', () => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: 'zh-TW', textScales: null}});
  applyAndroidLocale(projected.presentation);
  expect(localized('Guard')).toBe(translations['zh-Hant']?.['Guard']);
});

test('the adapter preserves text scales and treats a missing presentation as English', () => {
  expect(applyAndroidLocale({locale: 'ja', textScales: {body: 1.2}}).textScales).toEqual({body: 1.2});
  expect(applyAndroidLocale(undefined)).toEqual({locale: 'en', textScales: null});
});

// Independent values from frozen iOS 3bd86116: a newly added message, changed
// existing copy, and the Android overlay exercise the actual owner-locale path.
test.each([
  ['de-DE', 'Verfügbare Listen werden geprüft…', 'Dein VPN wird neu verbunden', 'Google Play-Nutzungsbedingungen'],
  ['en-US', 'Checking available lists…', 'Reconnecting your VPN', 'Google Play Terms'],
  ['fr-FR', 'Vérification des listes disponibles…', 'Reconnexion de votre VPN', 'Conditions de Google Play'],
  ['ja-JP', '利用可能なリストを確認中…', 'お使いのVPNに再接続しています', 'Google Play 利用規約'],
  ['zh-CN', '正在检查可用列表…', '正在重新连接你的 VPN', 'Google Play 条款'],
  ['zh-TW', '正在檢查可用清單…', '正在重新連線你的 VPN', 'Google Play 條款'],
  ['es-ES', 'Comprobando listas disponibles…', 'Reconectando tu VPN', 'Condiciones de Google Play'],
  ['ko-KR', '사용 가능한 목록 확인 중…', '내 VPN에 다시 연결하는 중', 'Google Play 약관'],
  ['pt-PT', 'Verificando listas disponíveis…', 'Reconectando sua VPN', 'Termos do Google Play'],
  ['it-IT', 'Verifica delle liste disponibili…', 'Riconnessione della tua VPN', 'Termini di Google Play'],
])('the frozen catalog delta and Android billing overlay render through locale %s', (locale, checking, reconnecting, playTerms) => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale, textScales: null}});
  applyAndroidLocale(projected.presentation);
  expect(localized('Checking available lists…')).toBe(checking);
  expect(localized('Reconnecting VPN')).toBe(reconnecting);
  expect(localized('Google Play Terms')).toBe(playTerms);
  const billing = 'Monthly and yearly plans auto-renew. Payment is charged through Google Play at purchase and at the start of each billing period unless you cancel. Manage or cancel your subscription in Google Play. After cancellation, access continues until the end of the paid period.';
  expect(localized(billing)).toContain('Google Play');
});

test('Android runtime messages use the frozen catalog and retain absent keys', () => {
  applyAndroidLocale({locale: 'zh-CN', textScales: null});
  expect(localized('Retrying DNS')).toBe('正在重试 DNS');
  expect(localized('DNS filtering is on. Waiting to confirm VPN traffic.'))
    .toBe('防护中。正在等待确认你的 VPN 正常工作。');
});

// Android-only native menu labels are additions to the frozen catalog, not iOS copy changes.
test.each([
  ['de-DE', 'Optionen', 'Weitere Optionen'],
  ['en-US', 'Options', 'More options'],
  ['fr-FR', 'Options', 'Plus d’options'],
  ['ja-JP', 'オプション', 'その他のオプション'],
  ['zh-CN', '选项', '更多选项'],
  ['zh-TW', '選項', '更多選項'],
  ['es-ES', 'Opciones', 'Más opciones'],
  ['ko-KR', '옵션', '추가 옵션'],
  ['pt-PT', 'Opções', 'Mais opções'],
  ['it-IT', 'Opzioni', 'Altre opzioni'],
])('Android menu labels render through the owner locale %s', (locale, options, moreOptions) => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale, textScales: null}});
  applyAndroidLocale(projected.presentation);
  expect(localized('Options')).toBe(options);
  expect(localized('More options')).toBe(moreOptions);
});

test('new catalog messages insert source names as data after choosing the translation', () => {
  applyAndroidLocale({locale: 'de-DE', textScales: null});
  expect(format('%@ list wasn’t imported because it’s no longer available.', 'Guard'))
    .toBe('Guard Liste wurde nicht importiert, da sie nicht mehr verfügbar ist.');
  applyAndroidLocale({locale: 'en-US', textScales: null});
  expect(localized('Keep Editing')).toBe('Cancel');
});

// Selected #872/#878 behavior: explicit supported script outranks region,
// and raw regional/script owner tags reach the actual shared Text renderer.
test.each([
  ['zh-Hant-TW', 'zh-Hant'],
  ['zh_TW', 'zh-Hant'],
  ['zh-HK', 'zh-Hant'],
  ['ZH-hant-mo', 'zh-Hant'],
  ['zh-Hans-HK', 'zh-Hans'],
  ['zh-Hans-TW', 'zh-Hans'],
  ['zh-Hant-CN', 'zh-Hant'],
  ['zh-MO', 'zh-Hant'],
  ['zh-CN', 'zh-Hans'],
  ['fr-CA', 'fr'],
  ['de-DE', 'de'],
  ['pt-PT', 'pt-BR'],
  ['pt', 'pt-BR'],
  ['en-GB', 'en'],
  ['pl-PL', 'en'],
])('the production owner tag %s renders the supported %s shared catalog', (wire, canonical) => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: wire, textScales: null}});
  const applied = applyAndroidLocale(projected.presentation);
  configurePresentation(applied);
  render(createElement(PresentationContext.Provider, {value: applied}, createElement(Text, null, 'VPN chaining')));
  expect(screen.getByText(translations[canonical]!['VPN chaining']!)).toBeOnTheScreen();
});

test('resolved shared formatting preserves a runtime name and verbatim text identity', () => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: 'zh-Hans-HK', textScales: null}});
  const applied = applyAndroidLocale(projected.presentation);
  configurePresentation(applied);
  const source = 'Your primary DNS is %@. For more details, check DNS settings.';
  expect(localizedFormat(source, 'Cancel')).toBe(translations['zh-Hans']![source]!.replace('%@', 'Cancel'));
  render(createElement(PresentationContext.Provider, {value: applied}, createElement(Text, {verbatim: true}, 'Cancel')));
  expect(screen.getByText('Cancel')).toBeOnTheScreen();
});

const portableEditionCopy = [
  'Local encrypted backup',
  'Export encrypted backup',
  'Import encrypted backup',
  'Local Backup',
  'Save an encrypted file and restore it on another device or Lava edition. Keep your password or recovery phrase separately; Lava cannot recover it.',
  'FOSS edition · All local features are available. Accounts and cloud backup are available in the Google Play edition.',
  'Contact support in browser',
  'Unlock Lava and finish setup before exporting a backup.',
  'Lava could not save the encrypted file. Choose a writable location and try again.',
  'This service is not available in the FOSS edition. All local features remain available.',
];

test.each([
  ['de-DE', 'Verschlüsselte lokale Sicherung', 'Verschlüsselte Sicherung exportieren'],
  ['es-ES', 'Copia de seguridad local cifrada', 'Exportar copia de seguridad cifrada'],
  ['fr-FR', 'Sauvegarde locale chiffrée', 'Exporter une sauvegarde chiffrée'],
  ['it-IT', 'Backup locale crittografato', 'Esporta backup crittografato'],
  ['ja-JP', '暗号化したローカルバックアップ', '暗号化したバックアップを書き出す'],
  ['ko-KR', '암호화된 로컬 백업', '암호화된 백업 내보내기'],
  ['pt-BR', 'Backup local criptografado', 'Exportar backup criptografado'],
  ['zh-CN', '加密的本地备份', '导出加密备份'],
  ['zh-TW', '加密的本機備份', '匯出加密備份'],
])('portable backup and edition copy render through production locale %s without English fallback', (wire, title, exportTitle) => {
  const projected = projectAndroidSnapshot({...productFixture, presentation: {locale: wire, textScales: null}});
  const applied = applyAndroidLocale(projected.presentation);
  configurePresentation(applied);
  for (const source of portableEditionCopy) {
    expect(localized(source)).not.toBe(source);
    expect(translations[applied.locale]?.[source]).toBe(localized(source));
  }
  expect(localized('Export encrypted backup')).toBe(exportTitle);
  render(createElement(PresentationContext.Provider, {value: applied}, createElement(Text, null, 'Local encrypted backup')));
  expect(screen.getByText(title)).toBeOnTheScreen();
});
