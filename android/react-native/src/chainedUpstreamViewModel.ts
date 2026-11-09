import {
  CHAINED_REASON_NOT_CONFIGURED,
  CHAINED_REASON_RECOVERING,
  CHAINED_REASON_UNAVAILABLE,
  chainedReasonCopy,
  type LavaChainedStatus,
} from './NativeLavaApp';

/**
 * Pure presentation mapping for the chained-upstream screen.
 *
 * Kept separate from the component so the honest-state rules are unit
 * testable: an operational failure is never rendered in the user's
 * preference vocabulary (e.g. "off" for something that never turned on).
 *
 * Precedence matters:
 *  - A missing native bridge reports unavailable with `configured = false`;
 *    it must be handled before the configured check or it reads as a
 *    configuration problem.
 *  - A not-configured engine reports `eligible = false` too, so the
 *    configured check runs BEFORE the eligibility gate; otherwise a stub with
 *    no engine owner would tell every user their device/plan is unsupported.
 */
export type ChainedPresentation =
  | 'loading'
  | 'error'
  | 'unavailable'
  | 'notConfigured'
  | 'inactive'
  | 'paused'
  | 'fellBack'
  | 'recovering'
  | 'ready';

export interface ChainedUpstreamViewModel {
  presentation: ChainedPresentation;
  title: string;
  detail: string;
  notice: string | null;
  toggleTitle: string;
  toggleValue: boolean;
  toggleDisabled: boolean;
}

const TOGGLE_TITLE = 'Chained upstream';

export function presentChainedUpstream(
  status: LavaChainedStatus | null,
  options: {loadError?: string | null; notice?: string | null; busy?: boolean} = {},
): ChainedUpstreamViewModel {
  const notice = options.notice ?? null;
  const loadError = options.loadError ?? null;
  const busy = options.busy ?? false;

  if (loadError != null) {
    return {
      presentation: 'error',
      title: 'Chained upstream unavailable',
      detail: loadError,
      notice,
      toggleTitle: TOGGLE_TITLE,
      toggleValue: false,
      toggleDisabled: true,
    };
  }

  if (status == null) {
    return {
      presentation: 'loading',
      title: 'Chained upstream',
      detail: 'Checking chained upstream status…',
      notice,
      toggleTitle: TOGGLE_TITLE,
      toggleValue: false,
      toggleDisabled: true,
    };
  }

  if (status.reason === CHAINED_REASON_UNAVAILABLE) {
    return {
      presentation: 'unavailable',
      title: 'Chained upstream unavailable',
      detail: 'The chained engine is not available in this build.',
      notice,
      toggleTitle: TOGGLE_TITLE,
      toggleValue: false,
      toggleDisabled: true,
    };
  }

  if (!status.configured || status.reason === CHAINED_REASON_NOT_CONFIGURED) {
    return {
      presentation: 'notConfigured',
      title: 'Not configured',
      detail: chainedReasonCopy(status.reason),
      notice,
      toggleTitle: TOGGLE_TITLE,
      // Removing the configuration (or losing its decodable key) does NOT clear the durable
      // preference. Preserve it and keep the disable direction live so the user can clear the
      // sticky enablement; otherwise saving a replacement configuration would auto-activate
      // chaining without a fresh opt-in. With no preference set there is nothing to disable and
      // no configuration to enable, so the control stays disabled.
      toggleValue: status.enabled,
      toggleDisabled: status.enabled ? busy : true,
    };
  }

  if (!status.eligible) {
    return {
      presentation: 'unavailable',
      title: 'Not available on this device',
      detail:
        'Chained upstream requires Lava plus on a device that supports the chained engine.',
      notice,
      toggleTitle: TOGGLE_TITLE,
      // An already-enabled user whose entitlement/eligibility lapsed must still be able to turn
      // the sticky preference OFF; only the enable direction is forbidden here.
      toggleValue: status.enabled,
      toggleDisabled: status.enabled ? busy : true,
    };
  }

  if (status.reason === CHAINED_REASON_RECOVERING) {
    return {
      presentation: 'recovering',
      title: 'Recovering',
      detail:
        'Lava is recovering the chained upstream. This is an operational state, not your setting.',
      notice,
      toggleTitle: TOGGLE_TITLE,
      toggleValue: status.enabled,
      toggleDisabled: true,
    };
  }

  // `ready` folds in the stored preference, so it is false for a legitimately-off upstream and
  // must NOT gate the control. The control is offered when the user may enable (`canEnable`) OR
  // when the sticky preference is already on, so an enabled upstream whose enabling gate is
  // currently unavailable can still be turned OFF; the on/off state comes from `enabled`.
  if (status.canEnable || status.enabled) {
    if (!status.enabled) {
      return {
        presentation: 'ready',
        title: 'Chained upstream off',
        detail: 'Turn on to route traffic through your chained upstream.',
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: false,
        toggleDisabled: busy,
      };
    }
    // "On" additionally requires the LIVE runtime to be chained: `ready` can be true while
    // establishment refused and a DNS-only fallback is carrying traffic.
    if (status.ready && status.active) {
      return {
        presentation: 'ready',
        title: 'Chained upstream on',
        detail: 'Traffic is routed through your chained upstream.',
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: true,
        toggleDisabled: busy,
      };
    }
    // A sticky-enabled upstream can become unusable (key unreadable, routes invalid, client-DNS
    // egress unavailable). `canEnable` is the preference-independent gate, so when it is false the
    // durable blocker (`reason`) must be rendered for remediation BEFORE any operational state:
    // otherwise an unusable upstream reads as merely "recovering"/"fell back" forever and the user
    // is never told what to fix. The toggle stays live so the sticky preference can still be
    // turned off.
    if (!status.canEnable) {
      return {
        presentation: 'unavailable',
        title: 'Chained upstream unavailable',
        detail: chainedReasonCopy(status.reason),
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: true,
        toggleDisabled: busy,
      };
    }
    // Enabled, enableable, but not carrying chained traffic. An intentionally inactive protection
    // (off) or a pause is NOT a recovery — no chained runtime is expected to run — so the screen
    // must not claim Lava is "recovering".
    if (status.protection === 'off') {
      return {
        presentation: 'inactive',
        title: 'Chained upstream not in use',
        detail: 'Turn on protection to route traffic through your chained upstream.',
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: true,
        toggleDisabled: busy,
      };
    }
    if (status.protection === 'paused') {
      return {
        presentation: 'paused',
        title: 'Chained upstream paused',
        detail: 'Protection is paused, so your chained upstream is not in use.',
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: true,
        toggleDisabled: busy,
      };
    }
    // Protection is on but the installed runtime is DNS-only: a settled fallback, not an
    // in-progress chained startup. Render it separately so the screen does not claim "recovering"
    // indefinitely when no retry is scheduled.
    if (status.installedMode === 'dnsOnly') {
      return {
        presentation: 'fellBack',
        title: 'Chained upstream fell back to DNS-only',
        detail:
          'Protection is still on, but traffic is not routed through your chained upstream.',
        notice,
        toggleTitle: TOGGLE_TITLE,
        toggleValue: true,
        toggleDisabled: busy,
      };
    }
    // Protection is running a chained runtime that is not established yet (handshake pending): an
    // operational state, never the user's preference. The toggle stays live so the user can turn
    // the sticky preference off even though enabling is currently forbidden.
    return {
      presentation: 'recovering',
      title: 'Recovering',
      detail:
        'Lava is recovering the chained upstream. This is an operational state, not your setting.',
      notice,
      toggleTitle: TOGGLE_TITLE,
      toggleValue: true,
      toggleDisabled: busy,
    };
  }

  // Configured and eligible, but not enableable for a platform/configuration reason (for example
  // client DNS over the tunnel is not available yet). Honest unavailable, not "not configured".
  return {
    presentation: 'unavailable',
    title: 'Chained upstream unavailable',
    detail: chainedReasonCopy(status.reason),
    notice,
    toggleTitle: TOGGLE_TITLE,
    toggleValue: false,
    toggleDisabled: true,
  };
}
