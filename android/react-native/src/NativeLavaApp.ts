import {DeviceEventEmitter, NativeModules} from 'react-native';

/**
 * Native command/query surface exposed to the Android host (plan Slice 7).
 *
 * This is the TypeScript side of the `NativeLavaApp` module. The native
 * implementation is engine-backed: it reports the real chained-upstream status
 * (configured/eligible/enabled/ready) derived from the shared readiness gate and
 * the per-credential-generation composition, and it never claims success
 * optimistically — `ready` is only true when the runtime can honor the command.
 * The screen derives its honest states (not configured / unavailable / recovering)
 * from these fields.
 */
export const CHAINED_REASON_NOT_CONFIGURED = 'not configured';
export const CHAINED_REASON_RECOVERING = 'recovering';
export const CHAINED_REASON_UNAVAILABLE = 'native module unavailable';
/**
 * The account's Keystore item is missing or unreadable (for example after a device restore that
 * did not back up the key). A distinct code so the UI can tell the user to re-enter the key rather
 * than render the generic "not available" text.
 */
export const CHAINED_REASON_KEY_UNAVAILABLE = 'chained-upstream-key-unavailable';
/**
 * A durable preference write failed. Emitted as a toggle-result reason; the previous value stays
 * authoritative.
 */
export const CHAINED_REASON_PREFERENCE_FAILED = 'chained-preference-write-failed';

/** JS event the native module emits when the live chained-activity flag changes. */
export const CHAINED_ACTIVE_EVENT = 'chainedActiveChanged';

/**
 * JS event the native module emits when the protection-mode code changes (`off`/`paused`/`running`).
 *
 * The runtime can start, await a network, or pause without publishing chained activity, so a screen
 * that renders from `status.protection` must also refresh on this push.
 */
export const CHAINED_PROTECTION_EVENT = 'chainedProtectionChanged';

/**
 * JS event the native module emits when the per-generation chained composition is installed or
 * rotates.
 *
 * The unlocked graph is built asynchronously, so a screen's first `getChainedStatus` can observe a
 * still-null composition and render "native module unavailable"; installing the composition while
 * protection stays off emits neither activity nor protection changes, so this push is what lets the
 * screen correct itself.
 */
export const CHAINED_COMPOSITION_EVENT = 'chainedCompositionChanged';

export interface LavaChainedStatus {
  /** True only when the engine owner can honor enable/disable right now. */
  ready: boolean;
  /** Stable reason code when not `ready`, or `null`; map via `chainedReasonCopy` for user copy. */
  reason: string | null;
  /** Whether chained upstream is currently latched. */
  enabled: boolean;
  /**
   * Whether the LIVE runtime is actually chained.
   *
   * The durable gate (`ready`) can be true while establishment refused and a DNS-only fallback is
   * carrying traffic, so the screen must not present "on" from `enabled`/`ready` alone.
   */
  active: boolean;
  /**
   * The protection runtime mode: `off` when no live runtime exists, `paused` for an intentional
   * pause, `running` while a session is starting/active. Lets the screen tell an intentionally
   * inactive protection apart from a chained session that is settling.
   */
  protection: 'off' | 'paused' | 'running';
  /**
   * The installed protection runtime's mode: `chained` when the engine session is installed
   * (even if the handshake is still pending), `dnsOnly` when a protection runtime is live but not
   * chained, `none` when no runtime is installed. Distinguishes a settled DNS-only fallback from
   * an in-progress chained startup.
   */
  installedMode: 'chained' | 'dnsOnly' | 'none';
  /**
   * Whether the user may turn chaining on, independent of the current preference.
   *
   * `ready` is false for a legitimately-off upstream (the preference is a readiness gate), so
   * the control is offered when `canEnable` is true and its on/off state comes from `enabled`.
   */
  canEnable: boolean;
  /** Whether a chained engine owner is configured at all. */
  configured: boolean;
  /** Whether this device/plan is allowed to run the chained engine. */
  eligible: boolean;
}

export interface LavaChainedToggleResult {
  /**
   * True when the preference was durably committed. This is the success of the command and is
   * deliberately independent of the post-command readiness: a successful disable leaves the
   * upstream legitimately disabled-by-user (`ready` false).
   */
  accepted: boolean;
  /** Honest operational reason when the command was not accepted. */
  reason: string | null;
}

export interface NativeLavaAppSpec {
  getChainedStatus(): Promise<unknown>;
  setChainedEnabled(enabled: boolean): Promise<unknown>;
}

const native = NativeModules.NativeLavaApp as NativeLavaAppSpec | undefined;

export function isNativeLavaAppAvailable(): boolean {
  return native != null;
}

/**
 * Subscribes to live chained-activity changes emitted by the native runtime.
 *
 * The engine establishes asynchronously after the preference/reload, so the screen cannot learn
 * that the chained path became active from a single status read; this push lets it refresh at the
 * transition (and when the runtime falls back). Returns an unsubscribe function.
 */
export function subscribeChainedActive(listener: (active: boolean) => void): () => void {
  const subscription = DeviceEventEmitter.addListener(CHAINED_ACTIVE_EVENT, listener);
  return () => subscription.remove();
}

/**
 * Subscribes to protection-mode changes emitted by the native runtime.
 *
 * The runtime can move `Off`→`AwaitingNetwork`, start, or pause without a chained-activity
 * publication, so a screen that renders from `status.protection` would otherwise stay stale.
 * Returns an unsubscribe function.
 */
export function subscribeChainedProtection(
  listener: (mode: string) => void,
): () => void {
  const subscription = DeviceEventEmitter.addListener(CHAINED_PROTECTION_EVENT, listener);
  return () => subscription.remove();
}

/**
 * Subscribes to chained-composition availability changes emitted by the native runtime.
 *
 * A screen opened before the asynchronously built unlocked graph is ready can read an unavailable
 * status; this push lets it refresh once the composition is installed (or rotates). Returns an
 * unsubscribe function.
 */
export function subscribeChainedComposition(
  listener: (available: boolean) => void,
): () => void {
  const subscription = DeviceEventEmitter.addListener(CHAINED_COMPOSITION_EVENT, listener);
  return () => subscription.remove();
}

/**
 * Maps a native reason code to user-facing copy.
 *
 * The native status carries a stable reason code, never a device-log identifier, so the UI must
 * translate it here rather than render the code. An unknown/absent code falls back to a generic
 * operational message.
 */
export function chainedReasonCopy(reason: string | null): string {
  switch (reason) {
    case CHAINED_REASON_NOT_CONFIGURED:
      return 'Chained upstream is not configured yet.';
    case CHAINED_REASON_UNAVAILABLE:
      return 'The chained engine is not available in this build.';
    case CHAINED_REASON_RECOVERING:
      return 'Lava is recovering the chained upstream.';
    case CHAINED_REASON_KEY_UNAVAILABLE:
      return 'Chained upstream is missing its key. Re-enter the key to continue.';
    case CHAINED_REASON_PREFERENCE_FAILED:
      return 'Chained upstream could not be changed. Try again.';
    case 'not-entitled':
      return 'Chained upstream requires Lava plus.';
    case 'device-ineligible':
      return 'This device does not support the chained engine.';
    case 'disabled-by-user':
      return 'Chained upstream is turned off.';
    case 'tunnel-dns-unavailable':
      return 'The upstream provides no usable resolver.';
    case 'tunnel-dns-malformed':
      return 'The upstream resolver address is not valid.';
    case 'no-ipv4-route':
      return 'The upstream routes no IPv4 traffic.';
    case 'tunnel-dns-unrouted':
      return 'The upstream resolver is not routed by the peer.';
    case 'routes-collide':
      return 'The upstream routes collide with the tunnel.';
    case 'client-dns-unavailable':
      return 'Chained upstream is not available in this build yet.';
    default:
      return 'Chained upstream is not available yet.';
  }
}

function unavailableStatus(reason: string): LavaChainedStatus {
  return {
    ready: false,
    reason,
    enabled: false,
    active: false,
    protection: 'off',
    installedMode: 'none',
    canEnable: false,
    configured: false,
    eligible: false,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export function parseChainedStatus(value: unknown): LavaChainedStatus {
  if (!isRecord(value)) {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-object status');
  }
  const {
    ready,
    reason,
    enabled,
    active,
    protection,
    installedMode,
    canEnable,
    configured,
    eligible,
  } = value;
  if (typeof ready !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "ready"');
  }
  if (reason !== null && typeof reason !== 'string') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned an invalid "reason"');
  }
  if (typeof enabled !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "enabled"');
  }
  if (typeof active !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "active"');
  }
  if (protection !== 'off' && protection !== 'paused' && protection !== 'running') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned an invalid "protection"');
  }
  if (installedMode !== 'chained' && installedMode !== 'dnsOnly' && installedMode !== 'none') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned an invalid "installedMode"');
  }
  if (typeof canEnable !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "canEnable"');
  }
  if (typeof configured !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "configured"');
  }
  if (typeof eligible !== 'boolean') {
    throw new TypeError('NativeLavaApp.getChainedStatus returned a non-boolean "eligible"');
  }
  return {ready, reason, enabled, active, protection, installedMode, canEnable, configured, eligible};
}

export function parseChainedToggleResult(value: unknown): LavaChainedToggleResult {
  if (!isRecord(value)) {
    throw new TypeError('NativeLavaApp.setChainedEnabled returned a non-object result');
  }
  const {accepted, reason} = value;
  if (typeof accepted !== 'boolean') {
    throw new TypeError('NativeLavaApp.setChainedEnabled returned a non-boolean "accepted"');
  }
  if (reason !== null && typeof reason !== 'string') {
    throw new TypeError('NativeLavaApp.setChainedEnabled returned an invalid "reason"');
  }
  return {accepted, reason};
}

export async function getChainedStatus(): Promise<LavaChainedStatus> {
  if (native == null) {
    return unavailableStatus(CHAINED_REASON_UNAVAILABLE);
  }
  return parseChainedStatus(await native.getChainedStatus());
}

export async function setChainedEnabled(enabled: boolean): Promise<LavaChainedToggleResult> {
  if (typeof enabled !== 'boolean') {
    throw new TypeError('NativeLavaApp.setChainedEnabled expects a boolean');
  }
  if (native == null) {
    return {accepted: false, reason: CHAINED_REASON_UNAVAILABLE};
  }
  return parseChainedToggleResult(await native.setChainedEnabled(enabled));
}
