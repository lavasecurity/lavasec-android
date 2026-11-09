import type {ProductSnapshot} from '../src/productContract';
export const productFixture: ProductSnapshot = {
  schema: 1, revision: 1, generation: '7', available: true, onboardingRequired: false,
  foreground: true, version: '1.0', qaTools: false,
  protection: {lifecycle: 'inactive', connectivity: 'unavailable', action: 'turnOn', disabled: false, canPause: false, pauseUntil: null, message: null, error: null, requested: false, confirmed: false, onboarding: 'ready', recovery: null},
  filters: {locked: false, available: true, rows: [{id: 'filter1', name: 'My filter', active: true, frozen: false, ruleCount: 84}]},
  activity: {locked: false, status: 'ready', allowed: '120', blocked: '15', startDate: '2026-09-26', endDate: '2026-09-27'},
  settings: {resolver: 'MULLVAD_ONLY', securityEnabled: false},
  presentation: {locale: 'en', textScales: null},
  settingsSummary: {dns: 'Mullvad DNS', privacy: 'All local logs on', security: 'Passcode off'},
  backup: {
    enablement: {state: 'off', value: false, canEnable: true, canDisable: false, canBackUp: false, canRestore: true, canChangeAutomatic: false, canRetryDeletion: false},
    configured: false, title: 'Back up your settings securely', summary: 'No backup yet.', detail: null,
    remoteStatus: 'noBackup', remoteAvailable: false, remoteUploadedAt: null, statusUnavailable: false,
    needsAttention: false, deletionPending: false, backingUp: false, busy: false, automatic: false,
  },
  catalog: {
    installed: 'bundled', version: 'built-in-source-url-catalog-v1', generatedAt: 0,
    sourceCount: 34, guardrailCount: 0, fresh: true, ageSeconds: null, refreshing: false, lastStatus: null,
  },
};
