import React from 'react';
import {render} from '@testing-library/react-native';

// Wave 1 (commit e9b9a27) added the shared outcome-symbol pair, the reserved
// native-switch footprint and the `LavaToggleControl` leaf to the Android
// canonical set. Pin the token/geometry contract those adopters bind to.
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn()}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));

import {lavaTokens} from '../canonical/src/generated/tokens';
import {foundation} from '../canonical/src/foundation';
import {LavaToggleRow} from '../canonical/src';
import {hostNodes, styleOf, type AuditNode} from './a11yHarness';

test('the outline outcome symbols are the D36 stroke-only pair', () => {
  expect(lavaTokens.outcomeSymbols.blockedOutline).toBe('xmark.circle');
  expect(lavaTokens.outcomeSymbols.allowedOutline).toBe('arrow.right.circle');
  // The filled D36 marks stay distinct; only the list treatment is stroke-only.
  expect(lavaTokens.outcomeSymbols.blocked).toBe('xmark.circle.fill');
  expect(lavaTokens.outcomeSymbols.allowed).toBe('arrow.right.circle.fill');
  expect(foundation.outcome).toBe(lavaTokens.outcomeSymbols);
});

test('the initial switch footprint reserves an Android target until native measurement', () => {
  expect(foundation.nativeSwitch).toEqual({width: 52, height: 48});
  // The value is a literal so a regenerated token cannot silently resize it.
  expect(lavaTokens).toBeDefined();
});

test('foundation.control.target is the shared touch-target measurement', () => {
  expect(foundation.control.target).toBe(48);
  expect(foundation.control.target).toBe(lavaTokens.toolbar.buttonSize);
});

test('row-content reserves foundation.control.target as the control-slot minHeight', () => {
  // `LavaToggleRow` composes the shared `LavaControlContent`, whose control slot
  // owns the `minHeight`. Read the flattened structural style; Jest has no Yoga,
  // so this asserts the token binding, not a measured pixel box (device geometry
  // is F25).
  const {UNSAFE_root} = render(<LavaToggleRow title="Feedback" value={false} onValueChange={() => {}} />);
  const controlSlots = controlsWithMinHeight(UNSAFE_root, foundation.control.target);
  expect(controlSlots.length).toBeGreaterThan(0);
  // The signature is the row-content control slot: a centered horizontal lane.
  expect(controlSlots.some(style => style.flexDirection === 'row' && style.alignItems === 'center')).toBe(true);
});

/** Host nodes whose flattened style pins `minHeight` to the given measurement. */
function controlsWithMinHeight(root: AuditNode, minHeight: number): Record<string, unknown>[] {
  return hostNodes(root)
    .map(node => styleOf(node))
    .filter((style): style is Record<string, unknown> => style !== undefined && style.minHeight === minHeight);
}
