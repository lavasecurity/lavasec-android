/**
 * Android RN mirror of the native system-surface capability catalog
 * (`android/app/src/main/kotlin/com/lavasec/app/notification/SystemCapabilityCatalog.kt`, plan 309
 * F21). It exists so the shared React route gate can consult the same dispositions and wire ids the
 * native notification routing and QA entry already use, without a second hand-maintained table.
 *
 * Only [isCapabilityAvailable] is build-dependent: `DEBUG_ONLY` follows the caller's debug/QA flag
 * (which the snapshot projects as `qaTools`), so a release build cannot reach a debug-only route.
 * iOS-only features are explicitly `unavailable`, never mapped to a fake Android equivalent.
 */
export type CapabilityDisposition =
  | 'available'
  | 'equivalent'
  | 'unavailable'
  | 'deferred'
  | 'debugOnly';

export type SystemCapabilityId =
  | 'dnsPatch'
  | 'liveActivity'
  | 'focusFilter'
  | 'appIntent'
  | 'alternateIcon'
  | 'widget'
  | 'shortcut'
  | 'autoSwitch'
  | 'deviceQA'
  | 'designGallery'
  | 'notificationActions'
  | 'deepLinks'
  | 'vpnPermissionExplanation'
  | 'systemSettingsLinks'
  | 'bootReturn';

export interface SystemCapabilityEntry {
  /** The canonical wire id shared with the native catalog. */
  readonly wireId: string;
  readonly disposition: CapabilityDisposition;
  readonly iosOnly: boolean;
  readonly androidEquivalent: string | null;
}

export const SYSTEM_CAPABILITIES: Readonly<Record<SystemCapabilityId, SystemCapabilityEntry>> = {
  dnsPatch: {wireId: 'dnsPatch', disposition: 'unavailable', iosOnly: true, androidEquivalent: null},
  liveActivity: {
    wireId: 'liveActivity',
    disposition: 'equivalent',
    iosOnly: false,
    androidEquivalent: 'Ongoing ambient protection-status notification',
  },
  focusFilter: {wireId: 'focusFilter', disposition: 'unavailable', iosOnly: true, androidEquivalent: null},
  appIntent: {wireId: 'appIntent', disposition: 'unavailable', iosOnly: true, androidEquivalent: null},
  alternateIcon: {wireId: 'alternateIcon', disposition: 'unavailable', iosOnly: true, androidEquivalent: null},
  widget: {wireId: 'widget', disposition: 'deferred', iosOnly: false, androidEquivalent: null},
  shortcut: {wireId: 'shortcut', disposition: 'deferred', iosOnly: false, androidEquivalent: null},
  // Frozen 3bd86116 AutoSwitchHowToContent (FilterLibraryView.swift) only explains Apple
  // Shortcuts/Focus setup; SwitchFilterIntent and SetFocusFilterIntent own its OS entry points.
  // There is no in-app scheduler or rule editor to port, or accepted Android equivalent.
  autoSwitch: {wireId: 'autoSwitch', disposition: 'unavailable', iosOnly: true, androidEquivalent: null},
  deviceQA: {
    wireId: 'deviceQA',
    disposition: 'debugOnly',
    iosOnly: false,
    androidEquivalent: 'Debug/authorized QA builds only',
  },
  designGallery: {
    wireId: 'designGallery',
    disposition: 'debugOnly',
    iosOnly: false,
    androidEquivalent: 'Debug builds only',
  },
  notificationActions: {
    wireId: 'notificationActions',
    disposition: 'available',
    iosOnly: false,
    androidEquivalent: 'Notification actions routed to the VPN service owner or a validated deep link',
  },
  deepLinks: {wireId: 'deepLinks', disposition: 'available', iosOnly: false, androidEquivalent: 'lavasecurity:// product routes'},
  vpnPermissionExplanation: {
    wireId: 'vpnPermissionExplanation',
    disposition: 'available',
    iosOnly: false,
    androidEquivalent: 'Onboarding VPN consent and notification permission explanation',
  },
  systemSettingsLinks: {
    wireId: 'systemSettingsLinks',
    disposition: 'available',
    iosOnly: false,
    androidEquivalent: 'App notification and VPN system-settings destinations',
  },
  bootReturn: {
    wireId: 'bootReturn',
    disposition: 'available',
    iosOnly: false,
    androidEquivalent: 'User-unlocked direct-boot receiver resumes deferred retention work',
  },
};

/** Whether the build can honour a capability; debug-only follows the supplied build flag. */
export function isCapabilityAvailable(id: SystemCapabilityId, debugBuild: boolean): boolean {
  switch (SYSTEM_CAPABILITIES[id].disposition) {
    case 'available':
    case 'equivalent':
      return true;
    case 'debugOnly':
      return debugBuild;
    case 'unavailable':
    case 'deferred':
      return false;
  }
}

/** Routes whose availability is owned by a capability; everything else is ungated. */
export function capabilityForRoute(route: string): SystemCapabilityId | null {
  switch (route) {
    case 'DNSPatch':
      return 'dnsPatch';
    case 'AutoSwitch':
      return 'autoSwitch';
    case 'DeviceQA':
    case 'phoneQA':
      return 'deviceQA';
    case 'Components':
      return 'designGallery';
    default:
      return null;
  }
}

export function routeAllowed(route: string, debugBuild: boolean): boolean {
  const capability = capabilityForRoute(route);
  return capability === null ? true : isCapabilityAvailable(capability, debugBuild);
}

/**
 * Facts the route gate needs about the chained-configuration journey. `ownerPresent` means the
 * native chained owner projected the `vpn` snapshot family for the live credential graph, so the
 * owner can actually serve the editor; entitlement is NOT part of the gate, because a non-entitled
 * user must still reach the destination to see the honest Lava Plus path.
 */
export interface VpnRouteFacts {
  ownerPresent: boolean;
}

/**
 * The Android review-stack route gate, replacing the inline route filter. It mirrors the canonical
 * iOS gate while consulting the native capability policy:
 *
 * - `DeviceQA` renders the native `phoneQA` page, so it maps to `deviceQA` (DEBUG_ONLY) and is
 *   offered only with QA tools in a build where the capability is honoured.
 * - `Components` maps to `designGallery` (DEBUG_ONLY); the component gallery is still reachable in
 *   review-gallery mode (`!hasApp`), which is the fixture host rather than a shipped QA surface.
 * - `VPNChaining` has no native system capability of its own; it is gated on the live chained
 *   owner projecting the `vpn` family (`VpnRouteFacts.ownerPresent`), so it is release-reachable
 *   rather than QA-only.
 * - `Passcode` is only declared when no native app is connected (review mode); the shipped app
 *   presents passcode through the native flow host instead.
 * - `DNSPatch`/`AutoSwitch` are intentionally absent from the real screens: [androidUnavailableScreenRoutes]
 *   maps them to the shared "Coming to Android" placeholder, matching the catalog's
 *   `unavailable` disposition for these iOS-only capabilities.
 */
export function androidReviewRouteAllowed(route: string, qaTools: boolean, hasApp: boolean, vpn?: VpnRouteFacts): boolean {
  switch (route) {
    case 'Components':
      return !hasApp || isCapabilityAvailable('designGallery', qaTools);
    case 'DeviceQA':
      return hasApp && isCapabilityAvailable('deviceQA', qaTools);
    case 'VPNChaining':
      // Release-reachable for an owner-projected chained graph — never the QA flag. The screen
      // renders the editor for an entitled user and the Upgrade/unavailable path otherwise.
      return hasApp && vpn?.ownerPresent === true;
    case 'Passcode':
      return !hasApp;
    default:
      return true;
  }
}

/**
 * Review-stack routes that render the shared "Coming to Android" placeholder instead of a real
 * screen. `Guardian` is intentionally absent: the shared Guardian screen renders the real projected
 * `guards` catalog using native availability for Original/current-look courtesy and earned/Plus
 * looks, with the remaining rows disabled, so the
 * Customization "Choose Lava Guard" row is a real destination rather than a dead end. `Passcode`
 * stays for the review gallery, and `AutoSwitch`/`DNSPatch` are unavailable iOS-only capabilities.
 * The shared `AddBlocklist` picker uses the production
 * catalog query and filter-draft owners on Android.
 */
export const androidUnavailableScreenRoutes: ReadonlySet<string> = new Set([
  'Passcode',
  'AutoSwitch',
  'DNSPatch',
]);
