import React from 'react';
import {screen, waitFor} from '@testing-library/react-native';

// Plan 309 F22/F20 adopter audit. Settings (accessibility-screen.test.tsx) is covered
// directly; this file structurally audits three CANONICAL adopter screens that were only
// exercised indirectly by their own behavior tests, rendering them on the REAL canonical
// AppStore + Android runtime over the `productFixture` wire.
//
// The harness reads roles, labels, heading semantics and hidden decorations — never a
// measured pixel box. Jest has no Yoga, so geometry/clipping causes are asserted
// structurally; real TalkBack order, focus order and pixel layout stay F25 device work.
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn(), getState: () => undefined}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined, key: 'k'}),
  usePreventRemove: jest.fn(),
}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844}),
  useSafeAreaInsets: () => ({top: 47, left: 0, right: 0, bottom: 34}),
}));

import {installLifecycleMock, mountJourney, type Wire} from './interruptionHarness';
import {productFixture} from './productFixture';
import {DNSScreen} from '../canonical/review/DNSScreen';
import {ExploreScreen} from '../canonical/review/ExploreScreen';
import {FiltersScreen} from '../canonical/review/FilterScreens';
import {
  expectHasHeading,
  expectStructuralAccessibility,
  headingNodes,
  hostNodes,
  interactiveNodes,
  type AuditNode,
} from './a11yHarness';

let lifecycle: ReturnType<typeof installLifecycleMock>;
beforeEach(() => {
  lifecycle = installLifecycleMock();
});
afterEach(() => jest.restoreAllMocks());

// The Android projection reads `dns.tiers`, so the wire must carry a confirmed ladder
// whose `dnsChoice` fields (including `secondary`) are all present.
const dnsWire: Wire = {
  ...productFixture,
  dns: {
    providers: [],
    customSelected: false,
    transports: ['IP', 'DoH'],
    editable: true,
    tiers: [
      {id: 'cloudflare|IP', name: 'Cloudflare', primary: '1.1.1.1', secondary: '1.0.0.1', transport: 'IP', metadata: 'Recommended'},
      {id: 'quad9|DoH', name: 'Quad9', primary: 'https://dns.quad9.net/dns-query', secondary: '', transport: 'DoH', metadata: 'Encrypted'},
    ],
    tiersContext: 'ctx',
  },
};

/** Every rendered `LavaDecoration` is a withdrawn illustration, never an interactive stop. */
function expectDecorationsHidden(root: AuditNode): void {
  const decorations = hostNodes(root).filter(node => node.type === 'LavaDecoration');
  expect(decorations.length).toBeGreaterThan(0);
  for (const node of decorations) {
    expect(node.props.accessible).toBe(false);
    expect(interactiveNodes(node)).toEqual([]);
  }
}

test('DNSScreen exposes labelled tiers and section headings over the owner snapshot', async () => {
  const harness = mountJourney({lifecycle, wire: dnsWire, render: () => <DNSScreen />});
  await waitFor(() => expect(screen.getByText('Cloudflare')).toBeTruthy());
  const root = screen.UNSAFE_root;
  expectHasHeading(root);
  expectStructuralAccessibility(root);
  // The confirmed ladder drives real rows, not an empty scaffold.
  expect(screen.getByText('Quad9')).toBeTruthy();
  expect(headingNodes(root).length).toBeGreaterThanOrEqual(1);
  expectDecorationsHidden(root);
  harness.unmount();
});

test('FiltersScreen exposes the active filter, the manage row and its headings', async () => {
  const harness = mountJourney({lifecycle, render: () => <FiltersScreen />});
  await waitFor(() => expect(screen.getByLabelText(/Now filtering/)).toBeTruthy());
  const root = screen.UNSAFE_root;
  expectHasHeading(root);
  expectStructuralAccessibility(root);
  expect(screen.getByRole('button', {name: 'Switch or manage filters'})).toBeTruthy();
  expect(interactiveNodes(root).length).toBeGreaterThanOrEqual(2);
  expectDecorationsHidden(root);
  harness.unmount();
});

test('ExploreScreen exposes the play control, headings and withdrawn diagram glyphs', async () => {
  const harness = mountJourney({lifecycle, render: () => <ExploreScreen />});
  await waitFor(() => expect(screen.getByRole('button', {name: 'Play demo'})).toBeTruthy());
  const root = screen.UNSAFE_root;
  expectHasHeading(root);
  expectStructuralAccessibility(root);
  expect(screen.getByTestId('explore.play')).toBeTruthy();
  expectDecorationsHidden(root);
  harness.unmount();
});
