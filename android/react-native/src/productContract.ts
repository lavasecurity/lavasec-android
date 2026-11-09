import type {AppSnapshot} from '../canonical/app/contract';
import {validateAndroidEdition} from '../canonical/android/edition';
import {DeviceEventEmitter, NativeModules} from 'react-native';

/**
 * Closed native-flow vocabulary. The canonical import flows (`import`/`importCode`/`importScan`)
 * are declared so JS can request them; no Android import presentation exists yet, so the native
 * bridge answers them with the typed `import-presentation-unavailable` rejection. The canonical
 * backup flows (`backupSetup`/`backupRestore`) are declared for the same reason: Android has no
 * native setup/restore presentation, so the bridge answers each with its own typed
 * `backup-setup-unavailable`/`backup-restore-unavailable` reason — never a fabricated success, and
 * no recovery material or review handle crosses the wire.
 */
export type NativeFlow = 'onboarding' | 'guard' | 'filters' | 'activity' | 'settings' |
  'resolver' | 'networkActivity' | 'privacy' | 'security' | 'customization' | 'feedback' |
  'legal' | 'diagnostics' | 'account' | 'plus' | 'import' | 'importCode' | 'importScan' |
  'backupSetup' | 'backupRestore';
export type GuardAction = 'turnOn' | 'turnOff' | 'resume' | 'reconnect' | 'settings';
/**
 * The canonical semantic feedback vocabulary, pinned to the iOS `LavaFeedbackSemantic` enum
 * (commit c4658d973bae05dd3b7aa84c014cb2f76ff61a0b). A `haptic` command must carry one of these;
 * the Android bridge rejects any other value instead of treating it as a no-op success.
 */
export type HapticSemantic =
  | 'selected'
  | 'engaged'
  | 'succeeded'
  | 'attentionRequired'
  | 'failed'
  | 'acknowledged'
  | 'inspectionEmpty';
/**
 * Canonical Sudoku game (iOS `ReactNative/app/contract.ts` `SudokuGame` / `review/sudoku-model.ts`).
 * `givens`/`solution` are 81 row-major cells (`0` = empty given); `values` are the player's entries
 * and `notes` the per-cell candidate digits. The snapshot and both `sudoku.*` commands share it.
 */
export interface SudokuPuzzle {givens: number[]; solution: number[]}
export interface SudokuGame {puzzle: SudokuPuzzle; values: number[]; notes: number[][]}
/**
 * Canonical DNS choice (iOS `contract.ts` `DNSChoice`). `id` is an opaque Android wire id
 * (`provider|transportToken`) that round-trips from the `dns.choices`/`dns.tiers` snapshot
 * back through `dns.tiers`; `transport` is the stable menu label (IP/DoH/DoT/DoQ).
 */
export interface DNSChoice {id: string; name: string; nameIsDefault?: boolean; primary: string; secondary: string; transport: string; metadata: string; isEnabled?: boolean}
/** One row of the canonical `VPNDraft` (iOS `contract.ts`). Android projects one row at most. */
export interface VPNDraftRow {name: string; mode: string; isEnabled?: boolean}
/**
 * Canonical VPN configuration editor draft (iOS `contract.ts` `VPNDraft`). Android's authority is
 * a single rotation, so `rows` has at most one entry and `swap` refuses; `removeStaged` is the
 * Android projection of "the one row is staged for removal on commit".
 */
export interface VPNDraft {id: string; revision: number; changed: boolean; containsFullTunnel: boolean; removeStaged?: boolean; rows: VPNDraftRow[]}
/**
 * Canonical `vpn` snapshot family (iOS `AppSnapshot['vpn']`). Omitted entirely when no live
 * chained composition exists, so the shared screen renders honest-unavailable. `generation` is the
 * committed ROTATION generation (the `vpn.begin` fence), distinct from the app graph generation.
 */
export interface VPNSnapshot {
  authorized?:boolean;
  setup: boolean;
  enabled: boolean;
  canEnable: boolean;
  canEdit: boolean;
  fallback: boolean;
  canChangeFallback: boolean;
  needsPlus: boolean;
  busy: boolean;
  restriction: string;
  error: string;
  unavailable: boolean;
  needsRepair?: boolean;
  rotationNote?: string;
  generation: string;
  rows: VPNDraftRow[];
  draft?: VPNDraft | null;
}
/** Confirmed backup enablement. `value` is null for a pending deletion or unavailable evidence. */
export interface BackupEnablement {
  state: 'off' | 'on' | 'setup' | 'busy' | 'deletionPending' | 'unavailable';
  value: boolean | null;
  canEnable: boolean;
  canDisable: boolean;
  canBackUp: boolean;
  canRestore: boolean;
  canChangeAutomatic: boolean;
  canRetryDeletion: boolean;
}
/**
 * Canonical protected-action surface ids (iOS `SecurityProtectedSurface`). The same ids cross in
 * both directions: the owner projects opted-in surfaces in this vocabulary and the shared screen
 * sends them back only through the exact `protectedActions.<title>` keys.
 */
export type SecurityChoiceId = 'appUnlock' | 'protectionControl' | 'protectionPause' | 'filterEditing' | 'activityViewing' | 'appSettings';
/** Stable owner security-failure codes; the screen maps them to copy, never raw native text. */
export type SecurityFailureCode = 'wrongPasscode' | 'secureStorage' | 'operationFailed';
/**
 * Canonical `security` snapshot family (iOS `AppSnapshot.security`), widened with the facts the
 * Android `SecurityOwner` publishes. Only `unavailable` is required so a partial projection still
 * parses and renders its honest unavailable/recovery state. A missing optional field is "not
 * reported", never a fabricated boolean. No credential material, verifier or prompt handle ever
 * appears in this family.
 */
export interface SecuritySnapshot {
  appUnlockCoverChoice?:boolean|null;
  /** Canonical: the Keystore could not be opened; no passcode controls exist. */
  unavailable: boolean;
  readRevision?:number;
  ownerRevision?:string;
  sourceRevision?:string;
  displayClearRevision?:string;
  /** Canonical `hasAuthenticationMethod`: a credential exists (passcode on). Absent = false. */
  hasAuthenticationMethod?: boolean;
  /** Canonical `updatingSurface`: a protected-surface mutation is being applied. */
  updatingSurface?: boolean;
  /** Android owner: the saved credential cannot be read; the user must reset it. */
  recoveryRequired?: boolean;
  /** Android owner: the last mutation failure as a stable code, never raw copy. */
  failure?: SecurityFailureCode | null;
  /** Android owner: the surfaces currently opted in. */
  protectedSurfaces?: SecurityChoiceId[];
  /** Android owner: surfaces granted for the current navigation turn. */
  authenticatedSurfaces?: SecurityChoiceId[];
  /** Canonical: whether this device can present biometric authentication. */
  showBiometrics?: boolean;
  /** Canonical: whether biometrics may be enabled now (a passcode must exist first). */
  canEnableBiometrics?: boolean;
  /** Android owner: biometrics are locked after too many attempts; the passcode still works. */
  biometricLockout?: boolean;
  /** Canonical: the platform biometric name ("Fingerprint"/"Face"/"Biometrics"). */
  biometricTitle?: string;
  /** Canonical: owner-projected status line, already localized by the owner. */
  status?: string;
  /** Android owner: whether biometrics are enabled (the separate `biometrics` setting). */
  biometrics?: boolean;
  /** Android owner: canonical lock-timeout option in minutes. */
  lockTimeoutMinutes?: number | null;
}
/** Canonical backup snapshot family. `remoteStatus` is a stable code, not localized copy. */
export interface BackupSnapshot {
  enablement: BackupEnablement;
  configured: boolean;
  title: string;
  summary: string;
  detail: string | null;
  remoteStatus: 'unavailable' | 'noBackup' | 'checking' | 'available';
  remoteAvailable: boolean;
  remoteUploadedAt: number | null;
  statusUnavailable: boolean;
  needsAttention: boolean;
  deletionPending: boolean;
  backingUp: boolean;
  busy: boolean;
  automatic: boolean;
}
/**
 * Canonical `connection` snapshot family (`canonical/app/contract.ts` `ConnectionSnapshot`),
 * projected read-only from existing Android owners: the active filter, the confirmed resolver
 * selection and the live chained status. Omitted entirely when the product cannot supply a resolver
 * selection, so the shared screens render their own honest fallback.
 */
export interface ConnectionSnapshot {
  filter?: {id: string; name: string; count: string};
  configurationPending?: boolean;
  dns: {
    usesWireGuard?: boolean;
    editable?: boolean;
    primary: {name: string; nameIsDefault?: boolean; detail: string; transport: string};
    fallback?: {name: string; nameIsDefault?: boolean; detail: string; transport: string} | null;
  };
  vpn: {
    eligible: boolean;
    enabled: boolean;
    fallbackEnabled?: boolean | null;
    configured?: boolean;
    name?: string;
    statusLabel?: string;
  };
}
export interface ProductSnapshot {
  domainCheckAvailable?:boolean;
  domainCheckRevision?:string;
  edition?:import('../canonical/android/edition').AndroidEdition;
  portableBackup?:{pending:'none'|'available'|'unreadable'};
  filterPreparationPresented?:boolean;
  preparation?:import('../canonical/src/flows/preparation/contract').PreparationSnapshot&{
    filterId:string;failureReason?:string|null;failureSource?:string|null;
  };
  schema: 1;
  revision: number;
  generation: string | null;
  available: boolean;
  onboardingRequired: boolean;
  onboardingAvailable?:boolean;
  onboardingRecoveryRequired?:boolean;
  onboardingReconstructionInFlight?:boolean;
  /** Originating Android window; a retained root cannot borrow another window's runtime. */
  nativeHostID?:string;
  /** Closed native public entry; its raw intent remains native. */
  navigation?:{serial:number;tab:'GuardTab'|'SettingsTab';screen:'Guard'|'Filters'|'Activity'|'Settings'};
  foreground: boolean;
  version: string;
  /**
   * The native F21 QA/debug capability (`SystemCapabilityCatalog` DEVICE_QA, a DEBUG_ONLY
   * disposition). `true` only in a debug/QA build; a release snapshot reports `false`, so the
   * shared route gate cannot expose the QA/design-gallery destinations. It is capability, never a
   * claim that a QA route is installed.
   */
  qaTools: boolean;
  guardProgress?:{unlockedIDs:string[];qualifiedUsageDays:number};
  protection: {
    lifecycle: string;
    /** Confirmed installed chained owner is still establishing; absent on older producers. */
    chainedEstablishing?: boolean;
    commandInFlight?: boolean;
    connectivity: string;
    action: GuardAction | null;
    disabled: boolean;
    canPause: boolean;
    pauseUntil: number | null;
    message: string | null;
    error: string | null;
    /**
     * Canonical requested-vs-confirmed pair plus the first-run default-filter preparation phase
     * (B18). `requested` is explicit intent, `confirmed` is the platform observation (or its armed
     * projection); they may legitimately disagree. `null` reports a missing lifecycle, never a
     * fabricated value. `onboarding` is `unavailable` before any lifecycle/onboarding authority.
     */
    requested: boolean | null;
    confirmed: boolean | null;
    onboarding: 'freshInstall' | 'preparingDefaultFilter' | 'ready' | 'preparationFailed' | 'unavailable';
    recovery:
      | 'retryDefaultFilterPreparation' | 'retryStart' | 'retryStop' | 'retryPause' | 'retryResume'
      | null;
  };
  filters: {
    locked: boolean;
    available: boolean;
    rows: {id: string; name: string; active: boolean; frozen: boolean; ruleCount: number | null;
      emoji?: string; lists?: string[]; empty?: boolean; blockedDomainCount?: number; allowedExceptionCount?: number}[];
  };
  activity: {
    locked: boolean;
    status: 'locked' | 'loading' | 'failed' | 'ready' | 'unavailable';
    allowed: string | null;
    blocked: string | null;
    startDate: string | null;
    endDate: string | null;
    todayDate?: string | null;
  };
  settings: {resolver: string; securityEnabled: boolean};
  /**
   * Canonical `connection` family, projected only while a confirmed resolver selection exists.
   * Omitted otherwise, so the shared Explore/Settings connection overview uses its own fallback.
   */
  connection?: ConnectionSnapshot;
  /**
   * Canonical `vpn` snapshot family (the single-rotation chained-configuration editor), projected
   * only while a live chained composition exists. Omitted otherwise.
   */
  vpn?: VPNSnapshot;
  /**
   * Canonical Domain History availability: `true` only when the Android owner confirms retained
   * rows, `false` for a confirmed-empty history, and `null` before any owner read. It is never
   * inferred from the retention toggle. `domainHistoryCount` is the currently loaded row count,
   * used only to restart the shared screen's paging window.
   */
  hasDomainHistory?: boolean | null;
  domainHistoryCount?: number | null;
  /**
   * Canonical `security` snapshot family, projected only while a live SecurityOwner exists.
   * Omitted entirely when the owner is absent, so the Security screen never renders controls the
   * owner cannot honor.
   */
  security?: SecuritySnapshot;
  backgroundPrivacyCoverRequired?:boolean;
  presentationBlocked?:boolean;
  presentationError?:string|null;
  appPrivacyCoverRequired?:boolean;
  foregroundFlow?:AppSnapshot['foregroundFlow'];
  foregroundClosing?:string[];
  customEntry?:AppSnapshot['customEntry'];
  onboardingSetup?:AppSnapshot['onboardingSetup'];
  /**
   * Canonical `presentation` family. `locale` is the language the app serves; `textScales`
   * is `null` exactly when the retained preference follows the system text size, otherwise the
   * body-ramp scale Android derives from the retained discrete text size.
   */
  presentation?: {locale: string; textScales: Record<string, number> | null};
  /**
   * Canonical `settingsSummary` family, projected only for fields with a live owner. A missing
   * field means that surface's owner is unavailable, never a fabricated summary.
   */
  settingsSummary?: {dns?: string; privacy?: string; security?: string};
  backup: BackupSnapshot;
  /**
   * Canonical `catalog` sync metadata, present only when the native catalog sync owner exists.
   * `installed` is the provenance of the effective catalog (`bundled` is always valid), and
   * `lastStatus` is the stable code of the last refresh attempt. No source names or hashes cross.
   */
  catalog?: {
    installed: 'bundled' | 'lastKnownGood' | 'remote';
    version: string;
    generatedAt: number;
    sourceCount: number;
    guardrailCount: number;
    fresh: boolean;
    sourceRepairRequired?: boolean;
    ageSeconds: number | null;
    refreshing: boolean;
    lastStatus:
      | 'updated' | 'unchanged' | 'keptInstalled' | 'bundledFallback'
      | 'offline' | 'persistFailed' | 'installFailed' | 'superseded' | null;
  };
  /**
   * Canonical `account` snapshot family, present only while the Android process account owner is
   * installed. Link state comes only from the providers Supabase reported; `googleAvailable`/
   * `appleAvailable` are provider capability, never a linked provider. No token, user id or email
   * crosses the bridge.
   */
  account?: {
    signedIn: boolean;
    status: string;
    detail: string;
    appleTitle: string;
    googleTitle: string;
    appleConnected: boolean;
    googleConnected: boolean;
    appleBusy?: boolean;
    googleBusy?: boolean;
    busy: boolean;
    message: string;
    googleAvailable: boolean;
    appleAvailable: boolean;
  };
  logging?: {keepCounts: boolean; keepHistory: boolean; keepNetworkActivity: boolean; keepLavaGuardProgress: boolean};
  /**
   * Canonical per-category notification preferences, keyed by the user-facing category label
   * (for example `Filter changes`). Omitted entirely when no Android owner projects it, so the
   * customization screen renders no fabricated toggles.
   */
  notifications?: Record<string, boolean>;
  sudoku?: SudokuGame;
  filterSession?: {lease?:string;
    id: string; name: string; editing: boolean; lists: string[]; savedLists: string[];
    blocked: string[]; allowed: string[]; savedBlocked: string[]; savedAllowed: string[];
    names: Record<string,string>;
    ruleCounts?: Record<string,number>;
    customLists?: string[];
  };
  /** Never-persisted editor identity, distinct from the saved filter rows. */
  newFilter?: {id:string;name:string;emoji:string;lists:string[];empty:boolean;blockedDomainCount:number;allowedExceptionCount:number};
  filterEditing?: {
    canSave: boolean;
    reviewCanConfirm: boolean;
    validation: string | null;
    refreshing: boolean;
    lists: {id: string; pending: boolean; undo: boolean}[];
    blocked: {id: string; pending: boolean; undo: boolean}[];
    allowed: {id: string; pending: boolean; undo: boolean}[];
  };
  /**
   * Canonical whole-library edit session (iOS `libraryEditing`). `active` is the session grant,
   * `hasChanges` covers staged additions/renames/emoji changes/deletions, and `deletions` is the
   * sorted staged-removal set. Omitted while no live session owner is available.
   */
  libraryEditing?: {active: boolean; hasChanges: boolean; deletions: string[]};
  /**
   * Canonical `dns` snapshot family. `tiers`/`custom` are CONFIRMED values; `customDraft`
   * and `customDraftToken` are the REQUESTED custom-editor session. Omitted entirely when
   * the product cannot supply a confirmed selection.
   */
  dns?: {
    customDraft?: DNSChoice | null;
    customDraftToken?: string;
    tiers?: DNSChoice[];
    tiersContext?: string;
    choices?: DNSChoice[];
    editable?: boolean;
    transportDetail?: string;
    custom?: {name: string; primary: string; secondary: string; valid: boolean; metadata: string; context: string};
    deviceDetail?: string;
    fallbackDetail?: string;
    providers: {id: string; name: string; address: string; selected: boolean}[];
    customSelected: boolean;
    transports: string[];
  };
}
export type FilterDomainDecision = 'blocked' | 'allowed';
/**
 * The payload of a successful `share.query`: the bare `LF1-` code, the canonical Universal Link,
 * and — where a QR/card renderer exists — a data-URI image. Android ships no renderer, so `image`
 * is always null there and `share.card` is refused with an explicit reason.
 */
export interface ShareQueryResult {
  code: string;
  url: string;
  image: string | null;
}
export type ProductCommand =
  | {type: 'navigation.authorize'; surface: 'activityViewing' | 'filterEditing' | 'credentials'; newTurn?: boolean}
  | {type: 'navigation.endTurn'}
  | {type: 'protection.toggle'; action: GuardAction}
  | {type: 'protection.pause'; minutes: 5 | 10 | 15}
  | {type: 'activity.refresh'}
  | {type: 'filter.open' | 'filter.close' | 'filter.edit' | 'filter.cancel' | 'filter.save' | 'filter.review' |
      'filter.switch' | 'filter.delete'; id: string}
  | {type: 'filter.apply'; id: string; review: string}
  | {type: 'filter.create'; name: string; duplicate?: string}
  | {type: 'filter.rename'; id: string; name: string; emoji?: string}
  | {type: 'filter.domain'; id: string; domain: string; decision: FilterDomainDecision; remove?: boolean}
  | {type: 'filter.removeList'; id: string; sourceID: string}
  | {type: 'filter.restoreDefaults'}
  | {type: 'filter.refresh'; id: string}
  | {type: 'filter.lists'; id: string; ids: string[]}
  | {type: 'share.query' | 'share.copy' | 'share.present'; id: string}
  | {type:'share.card';id:string;token:string}
  | {type: 'library.edit' | 'library.cancel'}
  | {type: 'library.toggleDeletion'; id: string}
  | {type: 'library.form'; form: 'create'; id?: string; name?: string}
  | {type: 'library.form'; form: 'rename'; id: string; name: string; emoji?: string}
  | {type: 'library.form'; form: 'delete'; ids?: string[]}
  | {type: 'native.flow'; flow: NativeFlow}
  | {type: 'haptic'; kind: HapticSemantic; controlID?: string; value?: string}
  | {type: 'dns.tiers'; context: string; tiers: {id: string; name: string; primary: string; secondary: string; isEnabled?: boolean}[]}
  | {type: 'dns.toggle'; context: string; index: number; value: boolean}
  | {type: 'vpn.begin'; id: string; generation: string}
  | {type: 'vpn.cancel' | 'vpn.reset' | 'vpn.swap'; id: string}
  | {type: 'vpn.commit'; id: string}
  | {type: 'vpn.toggle'; key: 'setup' | 'enabled' | 'fallback'; value: boolean}
  | {type: 'vpn.edit' | 'vpn.remove'; id: string; index: number}
  | {type: 'vpn.rowToggle'; generation: string; index: number; value: boolean}
  | {type: 'dns.customDraft'; choice?: DNSChoice}
  | {type: 'dns.custom'; context: string; name: string; primary: string; secondary: string}
  | {type: 'customEntry.dismiss'; id: string}
  | {type: 'sudoku.new'; challenge?: boolean}
  | {type: 'sudoku.save'; game: SudokuGame}
  | {type: 'settings.set'; key: string; value: string | boolean | number}
  | {type: 'account.google' | 'account.apple' | 'account.signOut' | 'account.delete'}
  | {type: 'backup.export' | 'backup.import' | 'backup.pending.resume' | 'backup.pending.discard' | 'backup.refresh' | 'backup.now' | 'backup.disable' | 'backup.delete'};
export interface CommandResult {
  schema: 1;
  status: 'completed' | 'cancelled' | 'rejected';
  reason: string | null;
  /** Canonical command payload: filter.save "saved"/"review", filter.review token, soft rejection. */
  value?: unknown;
}
interface ProductNativeModule {
  getSnapshot(): Promise<string>;
  command(request: string): Promise<string>;
}
const native = NativeModules.NativeLavaApp as ProductNativeModule | undefined;

function record(value: unknown): asserts value is Record<string, unknown> {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid native envelope');
}
function bool(value: unknown): asserts value is boolean {
  if (typeof value !== 'boolean') throw new TypeError('Invalid native boolean');
}
function str(value: unknown): asserts value is string {
  if (typeof value !== 'string') throw new TypeError('Invalid native string');
}
function nullableString(value: unknown): void { if (value !== null) str(value); }
function nullableNumber(value: unknown): void {
  if (value !== null && (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)) {
    throw new TypeError('Invalid native number');
  }
}
function jsonValue(value: unknown): void {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new TypeError('Invalid native value');
    return;
  }
  if (Array.isArray(value)) { value.forEach(jsonValue); return; }
  if (typeof value === 'object') {
    Object.keys(value as Record<string, unknown>).forEach(key => jsonValue((value as Record<string, unknown>)[key]));
    return;
  }
  throw new TypeError('Invalid native value');
}
/** A full 81-cell grid whose cells are integers in [min, max]. */
function sudokuCellGrid(value: unknown, min: number, max: number): void {
  if (!Array.isArray(value) || value.length !== 81) throw new TypeError('Invalid sudoku cell grid');
  value.forEach(cell => {
    if (!Number.isInteger(cell) || (cell as number) < min || (cell as number) > max) throw new TypeError('Invalid sudoku cell');
  });
}
function sudokuNotes(value: unknown): void {
  if (!Array.isArray(value) || value.length !== 81) throw new TypeError('Invalid sudoku notes');
  value.forEach((cell: unknown) => {
    if (!Array.isArray(cell)) throw new TypeError('Invalid sudoku note cell');
    cell.forEach((digit: unknown) => {
      if (!Number.isInteger(digit) || (digit as number) < 1 || (digit as number) > 9) throw new TypeError('Invalid sudoku note');
    });
  });
}
function sudokuGame(value: unknown): void {
  record(value);
  const game = value as Record<string, unknown>;
  record(game.puzzle);
  const puzzle = game.puzzle as Record<string, unknown>;
  sudokuCellGrid(puzzle.givens, 0, 9);
  sudokuCellGrid(puzzle.solution, 1, 9);
  sudokuCellGrid(game.values, 0, 9);
  sudokuNotes(game.notes);
}
function dnsChoice(value: unknown): void {
  record(value);
  const choice = value as Record<string, unknown>;
  ['id', 'name', 'primary', 'secondary', 'transport', 'metadata'].forEach(key => str(choice[key]));
  // Optional canonical per-tier enablement (iOS `DNSChoice.isEnabled`); when present it must be a
  // boolean so the shared tier toggle cannot read a malformed value as enabled.
  if (choice.isEnabled !== undefined) bool(choice.isEnabled);
  if (choice.nameIsDefault !== undefined) bool(choice.nameIsDefault);
}
function dnsFamily(value: unknown): void {
  record(value);
  const dns = value as Record<string, unknown>;
  if (!Array.isArray(dns.providers)) throw new TypeError('Invalid dns providers');
  dns.providers.forEach((provider: unknown) => {
    record(provider);
    const row = provider as Record<string, unknown>;
    str(row.id); str(row.name); str(row.address); bool(row.selected);
  });
  bool(dns.customSelected);
  if (!Array.isArray(dns.transports)) throw new TypeError('Invalid dns transports');
  dns.transports.forEach(str);
  ['editable'].forEach(key => { if (dns[key] !== undefined) bool(dns[key]); });
  ['transportDetail', 'deviceDetail', 'fallbackDetail', 'tiersContext', 'customDraftToken'].forEach(key => {
    if (dns[key] !== undefined) str(dns[key]);
  });
  if (dns.customDraft !== undefined && dns.customDraft !== null) dnsChoice(dns.customDraft);
  if (dns.custom !== undefined) {
    record(dns.custom);
    const custom = dns.custom as Record<string, unknown>;
    ['name', 'primary', 'secondary', 'metadata', 'context'].forEach(key => str(custom[key]));
    bool(custom.valid);
  }
  ['tiers', 'choices'].forEach(key => {
    if (dns[key] === undefined) return;
    if (!Array.isArray(dns[key])) throw new TypeError('Invalid dns rows');
    (dns[key] as unknown[]).forEach(dnsChoice);
  });
}
function vpnDraftRow(value: unknown): void {
  record(value);
  const row = value as Record<string, unknown>;
  str(row.name); str(row.mode);
  if (row.isEnabled !== undefined) bool(row.isEnabled);
}
function vpnDraft(value: unknown): void {
  record(value);
  const draft = value as Record<string, unknown>;
  str(draft.id);
  if (!Number.isSafeInteger(draft.revision) || (draft.revision as number) < 0) throw new TypeError('Invalid vpn draft revision');
  bool(draft.changed); bool(draft.containsFullTunnel);
  if (draft.removeStaged !== undefined) bool(draft.removeStaged);
  if (!Array.isArray(draft.rows)) throw new TypeError('Invalid vpn draft rows');
  draft.rows.forEach(vpnDraftRow);
}
function vpnSnapshot(value: unknown): void {
  record(value);
  const vpn = value as Record<string, unknown>;
  ['setup', 'enabled', 'canEnable', 'canEdit', 'fallback', 'canChangeFallback', 'needsPlus', 'busy', 'unavailable']
    .forEach(key => bool(vpn[key]));
  ['restriction', 'error', 'generation'].forEach(key => str(vpn[key]));
  if (vpn.authorized !== undefined) bool(vpn.authorized);
  if (vpn.needsRepair !== undefined) bool(vpn.needsRepair);
  if (vpn.rotationNote !== undefined) str(vpn.rotationNote);
  if (!Array.isArray(vpn.rows)) throw new TypeError('Invalid vpn rows');
  vpn.rows.forEach(vpnDraftRow);
  if (vpn.draft !== undefined && vpn.draft !== null) vpnDraft(vpn.draft);
}
function backupSnapshot(value: unknown): void {
  record(value);
  const backup = value as Record<string, unknown>;
  record(backup.enablement);
  const enablement = backup.enablement as Record<string, unknown>;
  if (!['off', 'on', 'setup', 'busy', 'deletionPending', 'unavailable'].includes(String(enablement.state))) {
    throw new TypeError('Invalid backup enablement state');
  }
  if (enablement.value !== null) bool(enablement.value);
  // A nullable enablement value is exactly the pending-deletion/unavailable contract; a settled
  // state must not carry null and a nullable state must not carry a boolean.
  const nullable = enablement.state === 'deletionPending' || enablement.state === 'unavailable';
  if (nullable !== (enablement.value === null)) throw new TypeError('Invalid backup enablement value');
  ['canEnable', 'canDisable', 'canBackUp', 'canRestore', 'canChangeAutomatic', 'canRetryDeletion']
    .forEach(key => bool(enablement[key]));
  [backup.configured, backup.remoteAvailable, backup.statusUnavailable, backup.needsAttention,
    backup.deletionPending, backup.backingUp, backup.busy, backup.automatic].forEach(bool);
  str(backup.title); str(backup.summary);
  nullableString(backup.detail);
  if (!['unavailable', 'noBackup', 'checking', 'available'].includes(String(backup.remoteStatus))) {
    throw new TypeError('Invalid backup remote status');
  }
  nullableNumber(backup.remoteUploadedAt);
}
function accountSnapshot(value: unknown): void {
  record(value);
  const account = value as Record<string, unknown>;
  ['signedIn', 'appleConnected', 'googleConnected', 'busy', 'googleAvailable', 'appleAvailable']
    .forEach(key => bool(account[key]));
  ['status', 'detail', 'appleTitle', 'googleTitle', 'message'].forEach(key => str(account[key]));
  ['appleBusy', 'googleBusy'].forEach(key => {
    if (account[key] !== undefined) bool(account[key]);
  });
}
function presentationSnapshot(value: unknown): void {
  record(value);
  const presentation = value as Record<string, unknown>;
  str(presentation.locale);
  if (presentation.textScales !== null) {
    record(presentation.textScales);
    Object.values(presentation.textScales as Record<string, unknown>).forEach(scale => {
      if (typeof scale !== 'number' || !Number.isFinite(scale)) throw new TypeError('Invalid text scale');
    });
  }
}
function settingsSummarySnapshot(value: unknown): void {
  record(value);
  const summary = value as Record<string, unknown>;
  ['dns', 'privacy', 'security'].forEach(key => {
    if (summary[key] !== undefined) str(summary[key]);
  });
}
const SECURITY_CHOICE_IDS = new Set(['appUnlock', 'protectionControl', 'protectionPause', 'filterEditing', 'activityViewing', 'appSettings']);
function securityChoiceIds(value: unknown, label: string): void {
  if (!Array.isArray(value)) throw new TypeError(`Invalid security ${label}`);
  value.forEach(id => { if (!SECURITY_CHOICE_IDS.has(String(id))) throw new TypeError('Invalid security surface'); });
}
function securitySnapshot(value: unknown): void {
  record(value);
  const security = value as Record<string, unknown>;
  bool(security.unavailable);
  if(security.appUnlockCoverChoice!==undefined&&security.appUnlockCoverChoice!==null)bool(security.appUnlockCoverChoice);
  if(security.readRevision!==undefined) {
    if(!Number.isSafeInteger(security.readRevision)||Number(security.readRevision)<0)throw new TypeError('Invalid read revision');
  }
  for(const key of ['ownerRevision','sourceRevision','displayClearRevision'])if(security[key]!==undefined)str(security[key]);
  ['hasAuthenticationMethod', 'updatingSurface', 'recoveryRequired', 'showBiometrics',
    'canEnableBiometrics', 'biometricLockout', 'biometrics']
    .forEach(key => { if (security[key] !== undefined) bool(security[key]); });
  if (security.failure !== undefined && security.failure !== null &&
    !['wrongPasscode', 'secureStorage', 'operationFailed'].includes(String(security.failure))) {
    throw new TypeError('Invalid security failure');
  }
  if (security.protectedSurfaces !== undefined) securityChoiceIds(security.protectedSurfaces, 'protected surfaces');
  if (security.authenticatedSurfaces !== undefined) securityChoiceIds(security.authenticatedSurfaces, 'authenticated surfaces');
  if (security.biometricTitle !== undefined) str(security.biometricTitle);
  if (security.status !== undefined) str(security.status);
  if (security.lockTimeoutMinutes !== undefined) nullableNumber(security.lockTimeoutMinutes);
}
function catalogSnapshot(value: unknown): void {
  record(value);
  const catalog = value as Record<string, unknown>;
  if (!['bundled', 'lastKnownGood', 'remote'].includes(String(catalog.installed))) {
    throw new TypeError('Invalid catalog provenance');
  }
  str(catalog.version);
  if (!Number.isSafeInteger(catalog.generatedAt) || (catalog.generatedAt as number) < 0) {
    throw new TypeError('Invalid catalog timestamp');
  }
  [catalog.sourceCount, catalog.guardrailCount].forEach(count => {
    if (!Number.isSafeInteger(count) || (count as number) < 0) throw new TypeError('Invalid catalog count');
  });
  [catalog.fresh, catalog.refreshing].forEach(bool);
  if(catalog.sourceRepairRequired!==undefined)bool(catalog.sourceRepairRequired);
  nullableNumber(catalog.ageSeconds);
  if (catalog.lastStatus !== null &&
    !['updated', 'unchanged', 'keptInstalled', 'bundledFallback', 'offline', 'persistFailed', 'installFailed', 'superseded']
      .includes(String(catalog.lastStatus))) {
    throw new TypeError('Invalid catalog status');
  }
}
function connectionPart(value: unknown): void {
  record(value);
  const part = value as Record<string, unknown>;
  ['name', 'detail', 'transport'].forEach(key => str(part[key]));
  if (part.nameIsDefault !== undefined) bool(part.nameIsDefault);
}
function connectionSnapshot(value: unknown): void {
  record(value);
  const connection = value as Record<string, unknown>;
  if (connection.filter !== undefined && connection.filter !== null) {
    record(connection.filter);
    const filter = connection.filter as Record<string, unknown>;
    ['id', 'name', 'count'].forEach(key => str(filter[key]));
  }
  if (connection.configurationPending !== undefined) bool(connection.configurationPending);
  record(connection.dns);
  const dns = connection.dns as Record<string, unknown>;
  ['usesWireGuard', 'editable'].forEach(key => { if (dns[key] !== undefined) bool(dns[key]); });
  connectionPart(dns.primary);
  if (dns.fallback !== undefined && dns.fallback !== null) connectionPart(dns.fallback);
  record(connection.vpn);
  const vpn = connection.vpn as Record<string, unknown>;
  ['eligible', 'enabled'].forEach(key => bool(vpn[key]));
  if (vpn.fallbackEnabled !== undefined && vpn.fallbackEnabled !== null) bool(vpn.fallbackEnabled);
  if (vpn.configured !== undefined) bool(vpn.configured);
  ['name', 'statusLabel'].forEach(key => { if (vpn[key] !== undefined) str(vpn[key]); });
}
export function parseProductSnapshot(payload: string): ProductSnapshot {
  const v: unknown = JSON.parse(payload);
  record(v);
  if (v.schema !== 1 || !Number.isSafeInteger(v.revision) || (v.revision as number) < 0) throw new TypeError('Unsupported native snapshot');
  if(v.edition!==undefined)validateAndroidEdition(v.edition);
  if(v.portableBackup!==undefined){
    record(v.portableBackup);
    if(Object.keys(v.portableBackup).length!==1||typeof v.portableBackup.pending!=='string'||!['none','available','unreadable'].includes(v.portableBackup.pending))
      throw new TypeError('Invalid portable backup state');
  }
  nullableString(v.generation);
  [v.available, v.onboardingRequired, v.foreground].forEach(bool);
  if(v.onboardingAvailable!==undefined)bool(v.onboardingAvailable);
  if(v.onboardingRecoveryRequired!==undefined)bool(v.onboardingRecoveryRequired);
  if(v.onboardingReconstructionInFlight!==undefined)bool(v.onboardingReconstructionInFlight);
  if(v.nativeHostID!==undefined)str(v.nativeHostID);
  if(v.filterPreparationPresented!==undefined)bool(v.filterPreparationPresented);
  if(v.preparation!==undefined){
    record(v.preparation);const p=v.preparation;
    [p.id,p.filterId,p.title,p.message].forEach(str);bool(p.busy);
    if(!['filters','domainHistory'].includes(String(p.origin))||
       !['preparing','success','failed'].includes(String(p.state))||
       ![0,.25,.5,.75,1].includes(p.progress as number)||
       (p.state==='success')!==(p.progress===1)||v.filterPreparationPresented!==true)
      throw new TypeError('Invalid native preparation');
    record(p.recovery);[p.recovery.retry,p.recovery.editReturn,p.recovery.keepCurrent].forEach(bool);
    if(p.recovery.upgrade!==null)throw new TypeError('Unsupported preparation recovery');
    if(p.failureReason!==undefined)nullableString(p.failureReason);
    if(p.failureSource!==undefined)nullableString(p.failureSource);
  }
  if(v.navigation!==undefined) {
    record(v.navigation);const request=v.navigation;
    if(Object.keys(request).some(key=>!['serial','tab','screen'].includes(key)) ||
       typeof request.screen!=='string' ||
       !Number.isSafeInteger(request.serial) || (request.serial as number)<=0 ||
       !(request.tab==='GuardTab'&&['Guard','Filters','Activity'].includes(request.screen) || request.tab==='SettingsTab'&&request.screen==='Settings') ||
       v.onboardingRequired || v.generation===null)throw new TypeError('Invalid native navigation');
  }
  str(v.version);
  bool(v.qaTools);
  if(v.domainCheckAvailable!==undefined)bool(v.domainCheckAvailable);
  if(v.domainCheckRevision!==undefined)str(v.domainCheckRevision);
  record(v.protection);
  str(v.protection.lifecycle); str(v.protection.connectivity);
  if(v.protection.chainedEstablishing!==undefined)bool(v.protection.chainedEstablishing);
  if(v.protection.commandInFlight!==undefined)bool(v.protection.commandInFlight);
  if (v.protection.action !== null && !['turnOn', 'turnOff', 'resume', 'reconnect', 'settings'].includes(String(v.protection.action))) throw new TypeError('Invalid Guard action');
  bool(v.protection.disabled); bool(v.protection.canPause);
  nullableNumber(v.protection.pauseUntil);
  nullableString(v.protection.message); nullableString(v.protection.error);
  if (v.protection.requested !== null) bool(v.protection.requested);
  if (v.protection.confirmed !== null) bool(v.protection.confirmed);
  if (!['freshInstall', 'preparingDefaultFilter', 'ready', 'preparationFailed', 'unavailable']
    .includes(String(v.protection.onboarding))) {
    throw new TypeError('Invalid protection onboarding phase');
  }
  if (v.protection.recovery !== null &&
    !['retryDefaultFilterPreparation', 'retryStart', 'retryStop', 'retryPause', 'retryResume']
      .includes(String(v.protection.recovery))) {
    throw new TypeError('Invalid protection recovery action');
  }
  record(v.filters); bool(v.filters.locked); bool(v.filters.available);
  if (!Array.isArray(v.filters.rows)) throw new TypeError('Invalid filter rows');
  v.filters.rows.forEach((row: unknown) => {
    record(row); str(row.id); str(row.name); bool(row.active); bool(row.frozen); nullableNumber(row.ruleCount);
    if (row.emoji !== undefined) str(row.emoji);
    if (row.empty !== undefined) bool(row.empty);
    for (const key of ['blockedDomainCount','allowedExceptionCount']) if (row[key] !== undefined) nullableNumber(row[key]);
    if (row.lists !== undefined) { if (!Array.isArray(row.lists)) throw new TypeError('Invalid filter lists'); row.lists.forEach(str); }
  });
  if (v.filters.locked && v.filters.rows.length !== 0) throw new TypeError('Locked filter evidence');
  record(v.activity); bool(v.activity.locked);
  if (!['locked', 'loading', 'failed', 'ready', 'unavailable'].includes(String(v.activity.status))) throw new TypeError('Invalid activity status');
  [v.activity.allowed, v.activity.blocked].forEach(count => {
    if (count !== null && (typeof count !== 'string' || !/^\d+$/.test(count))) throw new TypeError('Invalid activity counter');
  });
  nullableString(v.activity.startDate); nullableString(v.activity.endDate);
  if (v.activity.todayDate !== undefined) nullableString(v.activity.todayDate);
  if (v.activity.locked && [v.activity.allowed, v.activity.blocked, v.activity.startDate, v.activity.endDate].some(value => value !== null)) throw new TypeError('Locked activity evidence');
  record(v.settings); str(v.settings.resolver); bool(v.settings.securityEnabled);
  if (v.hasDomainHistory !== undefined && v.hasDomainHistory !== null) bool(v.hasDomainHistory);
  if (v.domainHistoryCount !== undefined) nullableNumber(v.domainHistoryCount);
  if (v.presentation !== undefined) presentationSnapshot(v.presentation);
  if (v.settingsSummary !== undefined) settingsSummarySnapshot(v.settingsSummary);
  if(v.backgroundPrivacyCoverRequired!==undefined)bool(v.backgroundPrivacyCoverRequired);
  if(v.presentationBlocked!==undefined)bool(v.presentationBlocked);
  if(v.appPrivacyCoverRequired!==undefined)bool(v.appPrivacyCoverRequired);
  if(v.presentationError!==undefined)nullableString(v.presentationError);
  if(v.foregroundClosing!==undefined) {
    if(!Array.isArray(v.foregroundClosing))throw new TypeError('Invalid foreground retirement');
    v.foregroundClosing.forEach(str);
  }
  if(v.foregroundFlow!==undefined&&v.foregroundFlow!==null) {
    record(v.foregroundFlow);const flow=v.foregroundFlow;
    str(flow.id);str(flow.name);str(flow.emoji);nullableNumber(flow.dismissAttempt);bool(flow.canCreate);
    if(!['createFilter','renameFilter','deleteFilters','automation','licenses','feedback','vpnConfiguration','accountBackup'].includes(String(flow.kind)))throw new TypeError('Invalid foreground kind');
    if(!Array.isArray(flow.templates))throw new TypeError('Invalid foreground templates');
    flow.templates.forEach((item:unknown)=>{record(item);str(item.id);str(item.name);});
    if(flow.notices!==undefined)str(flow.notices);
    if(flow.hasDeletions!==undefined)bool(flow.hasDeletions);
    if(flow.groups!==undefined) {
      if(!Array.isArray(flow.groups))throw new TypeError('Invalid foreground review');
      flow.groups.forEach((item:unknown)=>{record(item);str(item.title);
        for(const key of ['added','removed']){if(!Array.isArray(item[key]))throw new TypeError('Invalid foreground review');item[key].forEach(str);}
      });
    }
    if(flow.feedback!==undefined) {
      record(flow.feedback);const feedback=flow.feedback;
      for(const key of ['topic','site','details','email','revision','review','normalizedSite','normalizedDetails','normalizedEmail','error','receipt'])str(feedback[key]);
      for(const key of ['diagnostics','canContinue','dirty','busy','prepared','sent','copied'])bool(feedback[key]);
      for(const key of ['step','furthest','count']) {
        if(!Number.isSafeInteger(feedback[key])||Number(feedback[key])<0)throw new TypeError('Invalid feedback state');
      }
      if(!Array.isArray(feedback.topics))throw new TypeError('Invalid feedback topics');
      feedback.topics.forEach((item:unknown)=>{record(item);str(item.id);str(item.title);});
    }
    if(flow.kind==='accountBackup') {
      record(flow.accountBackup);const state=flow.accountBackup;
      if(!['account','backupSetup','backupRestore'].includes(String(state.kind)) ||
        !['overview','validatePasskey','recoveryPhrase','upload','complete'].includes(String(state.step)) ||
        !['deviceKey','passkey','recoveryCode'].includes(String(state.mode)))throw new TypeError('Invalid account/backup stage');
      for(const key of ['title','error','uploadError'])str(state[key]);
      for(const key of ['busy','canBack','canClose','success','deleting','copied','canContinue','supportsPasskey',
        'selectedPasskey','saved','understands','uploading','resolverConfirmationRequired','resolverConfirmed'])bool(state[key]);
      if(Object.keys(state).some(key=>/phrase|secret|credential|email|token/i.test(key)))throw new TypeError('Confidential account/backup projection');
      if(!Array.isArray(state.connections)||!Array.isArray(state.modes))throw new TypeError('Invalid account/backup options');
      state.connections.forEach((row:unknown)=>{record(row);str(row.title);if(!['apple','google'].includes(String(row.provider)))throw new TypeError('Invalid account provider');});
      state.modes.forEach((row:unknown)=>{record(row);str(row.title);bool(row.unavailable);if(!['deviceKey','passkey','recoveryCode'].includes(String(row.id)))throw new TypeError('Invalid recovery mode');});
      if(state.review!==null){
        if(!Array.isArray(state.review))throw new TypeError('Invalid backup review');
        state.review.forEach((section:unknown)=>{record(section);str(section.id);
          if(!Array.isArray(section.rows)||!Array.isArray(section.notes))throw new TypeError('Invalid backup review section');
          section.rows.forEach((row:unknown)=>{record(row);str(row.title);str(row.before);str(row.after);});
          section.notes.forEach((note:unknown)=>{record(note);str(note.text);if(!['contentChange','cacheWarning'].includes(String(note.role)))throw new TypeError('Invalid backup note');});
        });
      }
      if(state.status!==null)throw new TypeError('Unsupported account/backup status');
      if(!v.foreground||!v.available||v.presentationBlocked===true)throw new TypeError('Covered account/backup projection');
      if(v.security){
        record(v.security);const security=v.security;
        if(security.unavailable===true||['appUnlock','appSettings',...(state.kind==='backupRestore'?['filterEditing']:[])].some(surface=>
          Array.isArray(security.protectedSurfaces)&&security.protectedSurfaces.includes(surface)&&
          !(Array.isArray(security.authenticatedSurfaces)&&security.authenticatedSurfaces.includes(surface))))
          throw new TypeError('Locked account/backup projection');
      }
      if(state.success&&state.uploading)throw new TypeError('Unconfirmed backup completion');
    }
    if(flow.vpnEditor!==undefined&&flow.vpnEditor!==null) {
      record(flow.vpnEditor);const editor=flow.vpnEditor;
      str(editor.name);str(editor.error);nullableNumber(editor.nameResetRevision);
      for(const key of ['dirty','hasContent','concealed','reading','canEdit','canSave'])bool(editor[key]);
      // The confidential buffer is a native leaf; never accept configuration/key text in JS.
      if(Object.keys(editor).some(key=>/content|configuration|privateKey|presharedKey|text/i.test(key)&&key!=='hasContent'))throw new TypeError('Confidential editor projection');
    }
  }
  if(v.customEntry!==undefined&&v.customEntry!==null) {
    record(v.customEntry);const entry=v.customEntry;
    for(const key of ['id','name','primary','secondary'])str(entry[key]);
    bool(entry.allowed);bool(entry.overBudget);
    if(entry.kind!=='dns'&&entry.kind!=='blocklist')throw new TypeError('Invalid custom entry');
  }
  if(v.onboardingSetup!==undefined&&v.onboardingSetup!==null) {
    record(v.onboardingSetup);const setup=v.onboardingSetup;
    for(const key of ['id','busy','error'])str(setup[key]);nullableNumber(setup.page);
    for(const key of ['mock','fallback','dnsProfile','supportsDNSProfile','vpnInstalled','notifications'])bool(setup[key]);
    for(const key of ['history','visited']) {
      if(!Array.isArray(setup[key]))throw new TypeError('Invalid onboarding path');
      setup[key].forEach(nullableNumber);
    }
    if(!['essential','balanced','comprehensive'].includes(String(setup.level)))throw new TypeError('Invalid onboarding level');
    if(!['setup','arriving','ready','opening','released'].includes(String(setup.phase)))throw new TypeError('Invalid onboarding phase');
  }
  if (v.security !== undefined) securitySnapshot(v.security);
  if (v.account !== undefined) accountSnapshot(v.account);
  if (v.vpn !== undefined) vpnSnapshot(v.vpn);
  backupSnapshot(v.backup);
  if (v.catalog !== undefined) catalogSnapshot(v.catalog);
  if (v.filterSession !== undefined) {
    if (v.filters.locked || !v.foreground || !v.available) throw new TypeError('Locked filter session');
    const session = v.filterSession; record(session); str(session.id); str(session.name); bool(session.editing);
    if(session.lease!==undefined)str(session.lease);
    for (const key of ['lists','savedLists','blocked','allowed','savedBlocked','savedAllowed']) {
      if (!Array.isArray(session[key])) throw new TypeError('Invalid filter session');
      (session[key] as unknown[]).forEach(str);
    }
    record(session.names); Object.values(session.names).forEach(str);
    if(session.ruleCounts!==undefined){record(session.ruleCounts);Object.values(session.ruleCounts).forEach(count=>{
      if(!Number.isSafeInteger(count)||Number(count)<0)throw new TypeError("Invalid source rule count");
    });}
    if(session.customLists!==undefined){if(!Array.isArray(session.customLists))throw new TypeError("Invalid custom lists");session.customLists.forEach(str);}
  }
  if(v.preparation!==undefined&&(v.filters.locked||!v.foreground||!v.available||v.presentationBlocked===true))
    throw new TypeError('Locked filter preparation');
  if (v.newFilter !== undefined) {
    if (v.filters.locked || !v.foreground || !v.available) throw new TypeError('Locked new filter');
    const created = v.newFilter; record(created); str(created.id); str(created.name); str(created.emoji);
    bool(created.empty);
    for (const key of ['blockedDomainCount', 'allowedExceptionCount']) {
      if (created[key] === null) throw new TypeError('Missing new filter count');
      nullableNumber(created[key]);
    }
    if (!Array.isArray(created.lists)) throw new TypeError('Invalid new filter lists');
    created.lists.forEach(str);
    if (v.filters.rows.some((row: Record<string, unknown>) => row.id === created.id)) throw new TypeError('New filter is already saved');
  }
  if (v.filterEditing !== undefined) {
    const editing = v.filterEditing;
    record(editing);
    bool(editing.canSave); bool(editing.reviewCanConfirm);
    nullableString(editing.validation); bool(editing.refreshing);
    ['lists', 'blocked', 'allowed'].forEach(name => {
      const rows = editing[name];
      if (!Array.isArray(rows)) throw new TypeError('Invalid filter editing rows');
      rows.forEach((row: unknown) => { record(row); str(row.id); bool(row.pending); bool(row.undo); });
    });
  }
  if (v.libraryEditing !== undefined) {
    const editing = v.libraryEditing;
    record(editing);
    bool(editing.active); bool(editing.hasChanges);
    if (!Array.isArray(editing.deletions)) throw new TypeError('Invalid library editing deletions');
    editing.deletions.forEach(str);
  }
  if (v.logging !== undefined) {
    record(v.logging);
    for (const key of ['keepCounts', 'keepHistory', 'keepNetworkActivity', 'keepLavaGuardProgress']) bool(v.logging[key]);
  }
  if (v.notifications !== undefined) {
    record(v.notifications);
    Object.values(v.notifications).forEach(bool);
  }
  if (v.guardProgress !== undefined) {
    record(v.guardProgress);
    if (!Array.isArray(v.guardProgress.unlockedIDs)) throw new TypeError('Invalid Guardian achievements');
    v.guardProgress.unlockedIDs.forEach(str);
    if (!Number.isSafeInteger(v.guardProgress.qualifiedUsageDays)||Number(v.guardProgress.qualifiedUsageDays)<0) throw new TypeError('Invalid Guardian usage days');
  }
  if (v.sudoku !== undefined) sudokuGame(v.sudoku);
  if (v.dns !== undefined) dnsFamily(v.dns);
  if (v.connection !== undefined) connectionSnapshot(v.connection);
  return v as unknown as ProductSnapshot;
}
export function parseCommandResult(payload: string): CommandResult {
  const v: unknown = JSON.parse(payload); record(v);
  if (v.schema !== 1 || !['completed', 'cancelled', 'rejected'].includes(String(v.status))) throw new TypeError('Invalid command result');
  nullableString(v.reason);
  if (v.value !== undefined) jsonValue(v.value);
  return v as unknown as CommandResult;
}
export async function getProductSnapshot(): Promise<ProductSnapshot> {
  if (native?.getSnapshot == null) throw new Error('Native product is unavailable');
  return parseProductSnapshot(await native.getSnapshot());
}
export async function sendProductCommand(command: ProductCommand, generation: string): Promise<CommandResult> {
  if (native?.command == null) throw new Error('Native product is unavailable');
  return parseCommandResult(await native.command(JSON.stringify({...command, schema: 1, generation})));
}
export function subscribeProductSnapshot(listener: (snapshot: ProductSnapshot) => void, onError: () => void): () => void {
  const subscription = DeviceEventEmitter.addListener('lavaAppSnapshot', (payload: string) => {
    try { listener(parseProductSnapshot(payload)); } catch { onError(); }
  });
  return () => subscription.remove();
}
