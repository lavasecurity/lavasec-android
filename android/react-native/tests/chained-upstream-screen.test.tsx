import React from 'react';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import {ChainedUpstreamScreen} from '../src/ChainedUpstreamScreen';
import * as native from '../src/NativeLavaApp';
import type {LavaChainedStatus} from '../src/NativeLavaApp';

jest.mock('../src/NativeLavaApp', () => {
  const actual = jest.requireActual('../src/NativeLavaApp');
  return {
    ...actual,
    getChainedStatus: jest.fn(),
    setChainedEnabled: jest.fn(),
    subscribeChainedActive: jest.fn(() => () => {}),
    subscribeChainedProtection: jest.fn(() => () => {}),
    subscribeChainedComposition: jest.fn(() => () => {}),
  };
});

const mockedGet = native.getChainedStatus as jest.MockedFunction<
  typeof native.getChainedStatus
>;
const mockedSet = native.setChainedEnabled as jest.MockedFunction<
  typeof native.setChainedEnabled
>;
const mockedSubscribe = native.subscribeChainedActive as jest.MockedFunction<
  typeof native.subscribeChainedActive
>;
const mockedSubscribeProtection = native.subscribeChainedProtection as jest.MockedFunction<
  typeof native.subscribeChainedProtection
>;
const mockedSubscribeComposition = native.subscribeChainedComposition as jest.MockedFunction<
  typeof native.subscribeChainedComposition
>;

let activeListener: ((active: boolean) => void) | null = null;
let protectionListener: ((mode: string) => void) | null = null;
let compositionListener: ((available: boolean) => void) | null = null;

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

// The Android Switch host maps `value`/`disabled` onto the accessibility
// state, which is also the platform-neutral thing VoiceOver/TalkBack reads.
function toggle() {
  return screen.getByTestId('chainedUpstream.toggle');
}

beforeEach(() => {
  mockedGet.mockReset();
  mockedSet.mockReset();
  activeListener = null;
  protectionListener = null;
  compositionListener = null;
  mockedSubscribe.mockReset();
  mockedSubscribe.mockImplementation((listener) => {
    activeListener = listener;
    return () => {
      if (activeListener === listener) {
        activeListener = null;
      }
    };
  });
  mockedSubscribeProtection.mockReset();
  mockedSubscribeProtection.mockImplementation((listener) => {
    protectionListener = listener;
    return () => {
      if (protectionListener === listener) {
        protectionListener = null;
      }
    };
  });
  mockedSubscribeComposition.mockReset();
  mockedSubscribeComposition.mockImplementation((listener) => {
    compositionListener = listener;
    return () => {
      if (compositionListener === listener) {
        compositionListener = null;
      }
    };
  });
});

test('renders a not-configured engine as an honest disabled state', async () => {
  // The stub reports eligible=false too; not-configured must still win or
  // every user is wrongly told their device/plan is unsupported.
  mockedGet.mockResolvedValue(
    status({reason: 'not configured', configured: false, eligible: false}),
  );

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Not configured');
  expect(screen.getByText('Chained upstream is not configured yet.')).toBeTruthy();
  expect(screen.queryByText('Not available on this device')).toBeNull();
  expect(toggle().props.accessibilityState.disabled).toBe(true);
  expect(toggle().props.accessibilityState.checked).toBe(false);
  expect(screen.queryByTestId('chainedUpstream.notice')).toBeNull();
  expect(mockedSet).not.toHaveBeenCalled();
});

test('lets a removed configuration clear a sticky enablement', async () => {
  // removeAll() clears the stored configuration but not the durable preference, so the toggle must
  // stay live (checked) to let the user clear it; otherwise a replacement configuration would
  // auto-activate without a fresh opt-in.
  mockedGet
    .mockResolvedValueOnce(
      status({
        reason: 'not configured',
        configured: false,
        eligible: false,
        enabled: true,
        canEnable: false,
      }),
    )
    .mockResolvedValue(
      status({
        reason: 'not configured',
        configured: false,
        eligible: false,
        enabled: false,
        canEnable: false,
      }),
    );
  mockedSet.mockResolvedValue({accepted: true, reason: null});

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Not configured');
  expect(toggle().props.accessibilityState.checked).toBe(true);
  expect(toggle().props.accessibilityState.disabled).toBe(false);

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', false);

  await waitFor(() => expect(mockedSet).toHaveBeenCalledWith(false));
  expect(toggle().props.accessibilityState.checked).toBe(false);
  expect(screen.queryByTestId('chainedUpstream.notice')).toBeNull();
});

test('renders a missing native bridge as unavailable, not not-configured', async () => {
  mockedGet.mockResolvedValue(
    status({
      reason: native.CHAINED_REASON_UNAVAILABLE,
      configured: false,
      eligible: false,
    }),
  );

  render(<ChainedUpstreamScreen />);

  await screen.findByText('The chained engine is not available in this build.');
  expect(screen.queryByText('Not configured')).toBeNull();
  expect(toggle().props.accessibilityState.disabled).toBe(true);
});

test('renders an ineligible device as unavailable with no toggle action', async () => {
  // Configured but the device/plan gate fails: the eligibility branch wins.
  mockedGet.mockResolvedValue(
    status({configured: true, eligible: false, reason: null}),
  );

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Not available on this device');
  expect(toggle().props.accessibilityState.disabled).toBe(true);
  expect(mockedSet).not.toHaveBeenCalled();
});

test('renders recovering as an operational state, never the preference vocabulary', async () => {
  mockedGet.mockResolvedValue(status({reason: 'recovering', enabled: true}));

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Recovering');
  expect(screen.queryByText('Chained upstream on')).toBeNull();
  expect(screen.queryByText('Chained upstream off')).toBeNull();
  expect(toggle().props.accessibilityState.disabled).toBe(true);
});

test('calls the native module on toggle and adopts the authoritative status', async () => {
  mockedGet
    .mockResolvedValueOnce(status({ready: true, enabled: false}))
    .mockResolvedValue(status({ready: true, enabled: true}));
  mockedSet.mockResolvedValue({accepted: true, reason: null});

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream off');

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', true);

  await waitFor(() => expect(mockedSet).toHaveBeenCalledWith(true));
  await screen.findByText('Chained upstream on');
  expect(toggle().props.accessibilityState.checked).toBe(true);
  expect(screen.queryByTestId('chainedUpstream.notice')).toBeNull();
});

test('never claims success when the engine does not honor the enable', async () => {
  mockedGet.mockResolvedValue(status({ready: true, enabled: false}));
  mockedSet.mockResolvedValue({
    accepted: false,
    reason: native.CHAINED_REASON_PREFERENCE_FAILED,
  });

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream off');

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', true);

  await screen.findByTestId('chainedUpstream.notice');
  // The stable reason code is mapped to copy, never rendered directly.
  expect(screen.getByTestId('chainedUpstream.notice').props.children).toBe(
    'Chained upstream could not be changed. Try again.',
  );
  // The switch is driven by the re-read status, so a failed enable stays off.
  expect(toggle().props.accessibilityState.checked).toBe(false);
  expect(screen.queryByText('Chained upstream on')).toBeNull();
});

test('offers an enabled toggle for a disabled-by-user upstream', async () => {
  mockedGet.mockResolvedValue(
    status({
      ready: false,
      enabled: false,
      canEnable: true,
      reason: 'chained-not-ready-disabled-by-user',
    }),
  );

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream off');

  expect(toggle().props.accessibilityState.disabled).toBe(false);
  expect(toggle().props.accessibilityState.checked).toBe(false);
});

test('presents a successful disable as success, not failure', async () => {
  mockedGet
    .mockResolvedValueOnce(status({ready: true, enabled: true, canEnable: true}))
    .mockResolvedValue(
      status({
        ready: false,
        enabled: false,
        canEnable: true,
        reason: 'chained-not-ready-disabled-by-user',
      }),
    );
  mockedSet.mockResolvedValue({accepted: true, reason: null});

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream on');

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', false);

  await waitFor(() => expect(mockedSet).toHaveBeenCalledWith(false));
  await screen.findByText('Chained upstream off');
  expect(screen.queryByTestId('chainedUpstream.notice')).toBeNull();
  expect(toggle().props.accessibilityState.checked).toBe(false);
});

test('shows the durable blocker for a regressed sticky-enabled upstream, keeping disable live', async () => {
  mockedGet
    .mockResolvedValueOnce(
      status({
        ready: false,
        enabled: true,
        canEnable: false,
        reason: 'tunnel-dns-unavailable',
        protection: 'running',
        installedMode: 'dnsOnly',
      }),
    )
    .mockResolvedValue(
      status({
        ready: false,
        enabled: false,
        canEnable: false,
        reason: 'tunnel-dns-unavailable',
      }),
    );
  mockedSet.mockResolvedValue({accepted: true, reason: null});

  render(<ChainedUpstreamScreen />);

  // The blocker is named for remediation even though protection is running in a DNS-only fallback;
  // the state must not read as an endless "fell back"/"recovering".
  await screen.findByText('Chained upstream unavailable');
  expect(screen.getByText('The upstream provides no usable resolver.')).toBeTruthy();
  expect(screen.queryByText('Chained upstream fell back to DNS-only')).toBeNull();
  expect(screen.queryByText('Recovering')).toBeNull();

  expect(toggle().props.accessibilityState.disabled).toBe(false);
  expect(toggle().props.accessibilityState.checked).toBe(true);

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', false);

  await waitFor(() => expect(mockedSet).toHaveBeenCalledWith(false));
  expect(toggle().props.accessibilityState.checked).toBe(false);
  expect(screen.queryByTestId('chainedUpstream.notice')).toBeNull();
});

test('does not render chained-on when the runtime fell back to DNS-only', async () => {
  mockedGet.mockResolvedValue(status({ready: true, enabled: true, active: false}));

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Recovering');
  expect(screen.queryByText('Chained upstream on')).toBeNull();
  expect(toggle().props.accessibilityState.checked).toBe(true);
});

test('renders an intentionally inactive protection as not-in-use, not recovering', async () => {
  mockedGet.mockResolvedValue(
    status({ready: true, enabled: true, active: false, protection: 'off'}),
  );

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Chained upstream not in use');
  expect(screen.queryByText('Recovering')).toBeNull();
  expect(toggle().props.accessibilityState.checked).toBe(true);
});

test('renders a settled DNS-only fallback separately from recovery', async () => {
  mockedGet.mockResolvedValue(
    status({
      ready: true,
      enabled: true,
      active: false,
      protection: 'running',
      installedMode: 'dnsOnly',
    }),
  );

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Chained upstream fell back to DNS-only');
  expect(screen.queryByText('Recovering')).toBeNull();
  expect(toggle().props.accessibilityState.checked).toBe(true);
});

test('refreshes to chained-on when the runtime reports the engine established', async () => {
  // The first read lands during the async handshake (active=false, Recovering); the native
  // runtime later reports the live chained-activity flag changed.
  mockedGet
    .mockResolvedValueOnce(status({ready: true, enabled: true, active: false}))
    .mockResolvedValue(status({ready: true, enabled: true, active: true}));

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Recovering');

  act(() => {
    activeListener?.(true);
  });

  await screen.findByText('Chained upstream on');
  expect(mockedGet.mock.calls.length).toBeGreaterThan(1);
});

test('refreshes when protection starts without a chained-activity publication', async () => {
  // An enabled screen rendered inactive (protection off); protection then starts and awaits a
  // network, which changes the protection mode but publishes no chained activity. Without the
  // protection-state subscription the screen would stay inactive indefinitely.
  mockedGet
    .mockResolvedValueOnce(
      status({
        ready: true,
        enabled: true,
        active: false,
        protection: 'off',
        installedMode: 'none',
      }),
    )
    .mockResolvedValue(
      status({
        ready: true,
        enabled: true,
        active: false,
        protection: 'running',
        installedMode: 'chained',
      }),
    );

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream not in use');
  const callsBefore = mockedGet.mock.calls.length;

  act(() => {
    protectionListener?.('running');
  });

  await waitFor(() => expect(mockedGet.mock.calls.length).toBeGreaterThan(callsBefore));
  await screen.findByText('Recovering');
});

test('refreshes when the credential composition becomes available after mount', async () => {
  // The unlocked graph is built asynchronously, so the first read can see a null composition and
  // render "native module unavailable". Installing it with protection off emits neither activity
  // nor protection changes; without the composition subscription the screen would stay unavailable.
  mockedGet
    .mockResolvedValueOnce(
      status({
        configured: false,
        eligible: false,
        reason: native.CHAINED_REASON_UNAVAILABLE,
      }),
    )
    .mockResolvedValue(status({ready: true, enabled: false, canEnable: true}));

  render(<ChainedUpstreamScreen />);
  await screen.findByText('The chained engine is not available in this build.');

  act(() => {
    compositionListener?.(true);
  });

  await screen.findByText('Chained upstream off');
});

test('refreshes when the runtime reports a settled DNS-only fallback (false to false)', async () => {
  // The enable attempt failed before ever establishing, so `active` stays false; the versioned
  // native signal still notifies JS so the screen can re-read the settled state.
  mockedGet.mockResolvedValue(status({ready: true, enabled: true, active: false}));

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Recovering');
  const callsBefore = mockedGet.mock.calls.length;

  act(() => {
    activeListener?.(false);
  });

  await waitFor(() => expect(mockedGet.mock.calls.length).toBeGreaterThan(callsBefore));
});

test('discards a stale status read that resolves after a newer refresh', async () => {
  // An activity-triggered refresh and a post-toggle refresh can be in flight at once. If the OLDER
  // read resolves last it used to overwrite the newer status and strand the screen on the wrong
  // toggle/runtime state. Only the latest request may update state.
  let resolveOld!: (value: LavaChainedStatus) => void;
  let resolveNew!: (value: LavaChainedStatus) => void;
  mockedGet
    .mockReturnValueOnce(
      new Promise<LavaChainedStatus>((resolve) => {
        resolveOld = resolve;
      }),
    )
    .mockReturnValueOnce(
      new Promise<LavaChainedStatus>((resolve) => {
        resolveNew = resolve;
      }),
    );

  render(<ChainedUpstreamScreen />);

  act(() => {
    activeListener?.(true);
  });

  await act(async () => {
    resolveNew(status({ready: true, enabled: true}));
    resolveOld(status({ready: true, enabled: false}));
  });

  await screen.findByText('Chained upstream on');
  expect(screen.queryByText('Chained upstream off')).toBeNull();
  expect(toggle().props.accessibilityState.checked).toBe(true);
});


test('surfaces a native failure without flipping the control on', async () => {
  mockedGet.mockResolvedValue(status({ready: true, enabled: false}));
  mockedSet.mockRejectedValue(new Error('bridge unavailable'));

  render(<ChainedUpstreamScreen />);
  await screen.findByText('Chained upstream off');

  fireEvent(screen.getByTestId('chainedUpstream.toggle'), 'valueChange', true);

  await screen.findByTestId('chainedUpstream.notice');
  expect(screen.getByTestId('chainedUpstream.notice').props.children).toBe(
    'bridge unavailable',
  );
  expect(toggle().props.accessibilityState.checked).toBe(false);
});

test('offers a navigation entry back to the previous screen', async () => {
  mockedGet.mockResolvedValue(status({ready: true, enabled: false}));
  const back = jest.fn();

  render(<ChainedUpstreamScreen onNavigateBack={back} />);
  await screen.findByText('Chained upstream off');

  fireEvent.press(screen.getByTestId('chainedUpstream.back'));
  expect(back).toHaveBeenCalledTimes(1);
});

test('reports a status-query failure as an error state instead of a preference', async () => {
  mockedGet.mockRejectedValue(new Error('engine offline'));

  render(<ChainedUpstreamScreen />);

  await screen.findByText('Chained upstream unavailable');
  expect(screen.getByText('engine offline')).toBeTruthy();
  expect(toggle().props.accessibilityState.disabled).toBe(true);
});
