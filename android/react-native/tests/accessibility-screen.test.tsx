import React from 'react';
import {render} from '@testing-library/react-native';

const mockNoop = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: mockNoop, goBack: mockNoop}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/ReviewContext', () => ({
  ...jest.requireActual('../canonical/review/ReviewContext'),
  useReview: () => ({
    live: undefined,
    app: undefined,
    session: require('../canonical/review/session').initialSession,
    appearance: {subscribe: () => () => {}, getSnapshot: () => ({preference: 'system'}), connect: () => () => {}},
  }),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNoop, goBack: mockNoop}),
  previewNotice: mockNoop,
  androidNativeRoutes: {},
}));
jest.mock('../canonical/app/actions', () => ({
  useAppAction: () => mockNoop,
  useExclusiveAppAction: () => ({run: mockNoop, busy: false, isBusy: () => false}),
}));

import {SettingsScreen} from '../canonical/review/SettingsScreens';
import {
  expectHasHeading,
  expectNoClippedPrimaryActions,
  expectStructuralAccessibility,
  headingNodes,
  interactiveNodes,
  mockWindowSize,
  textNodes,
} from './a11yHarness';

afterEach(() => jest.restoreAllMocks());

test('the Settings screen renders labelled controls and section headings at the compact width', () => {
  const window = mockWindowSize(320);
  try {
    const {UNSAFE_root} = render(<SettingsScreen />);
    expectHasHeading(UNSAFE_root);
    expectStructuralAccessibility(UNSAFE_root);
    expectNoClippedPrimaryActions(UNSAFE_root);
    expect(textNodes(UNSAFE_root).length).toBeGreaterThan(0);
    // Not vacuous: the composed Settings screen has several destinations and groups.
    expect(interactiveNodes(UNSAFE_root).length).toBeGreaterThan(6);
    expect(headingNodes(UNSAFE_root).length).toBeGreaterThan(2);
  } finally {
    window.mockRestore();
  }
});

test('the Settings screen keeps the same sound structure at the wide width and enlarged text', () => {
  const window = mockWindowSize(768, 2.5);
  try {
    const {UNSAFE_root} = render(<SettingsScreen />);
    expectHasHeading(UNSAFE_root);
    expectStructuralAccessibility(UNSAFE_root);
    expectNoClippedPrimaryActions(UNSAFE_root);
    expect(interactiveNodes(UNSAFE_root).length).toBeGreaterThan(6);
  } finally {
    window.mockRestore();
  }
});
