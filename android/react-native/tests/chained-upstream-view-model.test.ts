import {presentChainedUpstream} from '../src/chainedUpstreamViewModel';
import {
  CHAINED_REASON_KEY_UNAVAILABLE,
  CHAINED_REASON_UNAVAILABLE,
  type LavaChainedStatus,
} from '../src/NativeLavaApp';

function status(partial: Partial<LavaChainedStatus> = {}): LavaChainedStatus {
  return {
    ready: false,
    reason: null,
    enabled: false,
    active: true,
    protection: 'running',
    installedMode: 'chained',
    canEnable: true,
    configured: true,
    eligible: true,
    ...partial,
  };
}

test('loading and load errors are distinct from user preference', () => {
  const loading = presentChainedUpstream(null);
  expect(loading.presentation).toBe('loading');
  expect(loading.toggleDisabled).toBe(true);

  const failed = presentChainedUpstream(null, {loadError: 'engine offline'});
  expect(failed.presentation).toBe('error');
  expect(failed.detail).toBe('engine offline');
  expect(failed.toggleValue).toBe(false);
});

test('ready status mirrors the authoritative enabled flag', () => {
  const on = presentChainedUpstream(status({ready: true, enabled: true}));
  expect(on.presentation).toBe('ready');
  expect(on.toggleValue).toBe(true);
  expect(on.toggleDisabled).toBe(false);

  const off = presentChainedUpstream(status({ready: true, enabled: false}));
  expect(off.title).toBe('Chained upstream off');
  expect(off.toggleValue).toBe(false);
});

test('does not claim chained traffic while the chained handshake is still pending', () => {
  // `ready` is the durable gate and can be true while the chained runtime is installed but not
  // established; only `active` proves traffic is routed upstream.
  const view = presentChainedUpstream(
    status({ready: true, enabled: true, active: false, installedMode: 'chained'}),
  );
  expect(view.presentation).toBe('recovering');
  expect(view.title).not.toBe('Chained upstream on');
  expect(view.toggleValue).toBe(true);
});

test('a settled DNS-only fallback is rendered separately from recovery', () => {
  const view = presentChainedUpstream(
    status({
      ready: true,
      enabled: true,
      active: false,
      protection: 'running',
      installedMode: 'dnsOnly',
    }),
  );
  expect(view.presentation).toBe('fellBack');
  expect(view.title).toBe('Chained upstream fell back to DNS-only');
  expect(view.title).not.toBe('Recovering');
  expect(view.toggleValue).toBe(true);
});

test('an enabled upstream with protection off is not shown as recovering', () => {
  const view = presentChainedUpstream(
    status({ready: true, enabled: true, active: false, protection: 'off'}),
  );
  expect(view.presentation).toBe('inactive');
  expect(view.title).not.toBe('Recovering');
  expect(view.toggleValue).toBe(true);
  expect(view.toggleDisabled).toBe(false);
});

test('an enabled upstream with protection paused shows paused, not recovering', () => {
  const view = presentChainedUpstream(
    status({ready: true, enabled: true, active: false, protection: 'paused'}),
  );
  expect(view.presentation).toBe('paused');
  expect(view.title).not.toBe('Recovering');
});

test('an enabled upstream with protection running but not yet active is recovering', () => {
  const view = presentChainedUpstream(
    status({ready: true, enabled: true, active: false, protection: 'running'}),
  );
  expect(view.presentation).toBe('recovering');
});

test('recovering never renders the preference vocabulary', () => {
  const view = presentChainedUpstream(status({reason: 'recovering', enabled: true}));
  expect(view.presentation).toBe('recovering');
  expect(view.title).toBe('Recovering');
  expect(view.title).not.toMatch(/on|off/i);
  expect(view.toggleDisabled).toBe(true);
});

test('a missing native bridge is unavailable, not a configuration problem', () => {
  // The JS wrapper reports the absent bridge with configured=false; that must
  // not fall into the not-configured branch.
  const view = presentChainedUpstream(
    status({
      configured: false,
      eligible: false,
      reason: CHAINED_REASON_UNAVAILABLE,
    }),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.title).toBe('Chained upstream unavailable');
  expect(view.title).not.toBe('Not configured');
  expect(view.toggleDisabled).toBe(true);
});

test('not-configured wins over an otherwise-failing eligibility gate', () => {
  // A stub with no engine owner reports configured=false and eligible=false;
  // the user must see "not configured", not a device/plan dead end.
  const notConfigured = presentChainedUpstream(
    status({configured: false, eligible: false, reason: 'not configured'}),
  );
  expect(notConfigured.presentation).toBe('notConfigured');
  expect(notConfigured.title).toBe('Not configured');
});

test('a removed configuration does not strand a sticky enablement', () => {
  // removeAll() clears the stored configuration but not the durable preference. The toggle must
  // stay live (checked) so the user can clear the sticky enable; otherwise a replacement
  // configuration would auto-activate without a fresh opt-in.
  const view = presentChainedUpstream(
    status({
      configured: false,
      eligible: false,
      enabled: true,
      canEnable: false,
      reason: 'not configured',
    }),
  );
  expect(view.presentation).toBe('notConfigured');
  expect(view.title).toBe('Not configured');
  expect(view.toggleValue).toBe(true);
  expect(view.toggleDisabled).toBe(false);
});

test('a not-configured upstream with no preference keeps the control disabled', () => {
  const view = presentChainedUpstream(
    status({configured: false, eligible: false, enabled: false, reason: 'not configured'}),
  );
  expect(view.presentation).toBe('notConfigured');
  expect(view.toggleValue).toBe(false);
  expect(view.toggleDisabled).toBe(true);
});

test('an ineligible configured engine is unavailable', () => {
  const view = presentChainedUpstream(
    status({configured: true, eligible: false, reason: null}),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.toggleDisabled).toBe(true);
});

test('an enabled user whose eligibility lapsed can still turn chaining off', () => {
  // Entitlement/device eligibility loss must not trap the sticky preference on.
  const view = presentChainedUpstream(
    status({configured: true, eligible: false, enabled: true, reason: null}),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.toggleValue).toBe(true);
  expect(view.toggleDisabled).toBe(false);
});

test('an eligible configured disabled-by-user upstream offers an enabled toggle', () => {
  // `ready` is false because the preference is off, but the user must be able to turn it on.
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: false,
      canEnable: true,
      reason: 'chained-not-ready-disabled-by-user',
    }),
  );
  expect(view.presentation).toBe('ready');
  expect(view.title).toBe('Chained upstream off');
  expect(view.toggleValue).toBe(false);
  expect(view.toggleDisabled).toBe(false);
});

test('a surface that cannot enable keeps the toggle disabled', () => {
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: false,
      canEnable: false,
      reason: 'chained-not-ready-no-usable-tunnel-dns',
    }),
  );
  expect(view.toggleDisabled).toBe(true);
});

test('a configured eligible upstream that cannot be enabled is unavailable, not not-configured', () => {
  // The platform capability refusal must read as unavailable, not as a missing configuration.
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: false,
      canEnable: false,
      configured: true,
      eligible: true,
      reason: 'client-dns-unavailable',
    }),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.title).not.toBe('Not configured');
  expect(view.toggleDisabled).toBe(true);
});

test('a readiness refusal renders user copy, never a device-log identifier', () => {
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: false,
      canEnable: false,
      configured: true,
      eligible: true,
      reason: 'routes-collide',
    }),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.detail).toBe('The upstream routes collide with the tunnel.');
  expect(view.detail).not.toMatch(/chained-not-ready/);
});

test('an enabled upstream whose enabling gate regressed shows the blocker, not recovering', () => {
  // The sticky preference is on but the route gate regressed. The screen must name the durable
  // blocker for remediation rather than render it as an endless "recovering"/"fell back", while
  // the toggle stays live to turn it OFF.
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: true,
      canEnable: false,
      reason: 'tunnel-dns-unavailable',
      protection: 'running',
      installedMode: 'dnsOnly',
    }),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.detail).toBe('The upstream provides no usable resolver.');
  expect(view.presentation).not.toBe('fellBack');
  expect(view.toggleValue).toBe(true);
  expect(view.toggleDisabled).toBe(false);
});

test('an enabled upstream whose key became unreadable tells the user to re-enter it', () => {
  const view = presentChainedUpstream(
    status({
      ready: false,
      enabled: true,
      canEnable: false,
      reason: CHAINED_REASON_KEY_UNAVAILABLE,
      protection: 'running',
      installedMode: 'chained',
      active: false,
    }),
  );
  expect(view.presentation).toBe('unavailable');
  expect(view.detail).toBe('Chained upstream is missing its key. Re-enter the key to continue.');
  expect(view.presentation).not.toBe('recovering');
  expect(view.toggleValue).toBe(true);
  expect(view.toggleDisabled).toBe(false);
});
