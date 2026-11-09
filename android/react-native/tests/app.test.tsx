import React from 'react';
import {AppState, BackHandler, StatusBar, StyleSheet, type ViewStyle} from 'react-native';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import App from '../App';
import {getProductSnapshot, sendProductCommand, subscribeProductSnapshot, type ProductSnapshot} from '../src/productContract';
import {productFixture} from './productFixture';

jest.mock('../src/productContract', () => ({
  getProductSnapshot: jest.fn(), sendProductCommand: jest.fn(), subscribeProductSnapshot: jest.fn(),
}));
jest.mock('../src/NativeLavaApp', () => ({
  ...jest.requireActual('../src/NativeLavaApp'),
  getChainedStatus: jest.fn().mockResolvedValue({ready: false, reason: 'not configured', enabled: false, active: false, protection: 'off', installedMode: 'none', canEnable: false, configured: false, eligible: false}),
}));
let push: (snapshot: ProductSnapshot) => void;
let back: () => boolean | null | undefined;
let appState: (state: 'active' | 'background') => void;
const unsubscribe = jest.fn();
const originalAppState = AppState.currentState;
beforeEach(() => {
  AppState.currentState = 'active';
  jest.mocked(getProductSnapshot).mockResolvedValue(productFixture);
  jest.mocked(sendProductCommand).mockResolvedValue({schema: 1, status: 'completed', reason: null});
  jest.mocked(subscribeProductSnapshot).mockImplementation(listener => { push = listener; return unsubscribe; });
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, listener) => {
    back = () => listener({type: 'hardwareBackPress', timeStamp: 0}); return {remove: jest.fn()};
  });
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    appState = listener as typeof appState; return {remove: jest.fn()};
  });
});
afterEach(() => { AppState.currentState = originalAppState; jest.restoreAllMocks(); jest.clearAllMocks(); });

test('foreground transition between render and subscription cannot leave startup loading', async () => {
  AppState.currentState = 'background';
  jest.mocked(subscribeProductSnapshot).mockImplementation(listener => {
    // Native resume can update AppState before our effect subscribes to change events.
    AppState.currentState = 'active';
    push = listener;
    return unsubscribe;
  });
  render(<App />);
  await screen.findByText('Protection is off');
  expect(screen.queryByText('Loading your protection…')).toBeNull();
});

test('Guard renders authoritative state and sends the rendered action with its generation', async () => {
  render(<App />);
  await screen.findByText('Protection is off');
  fireEvent.press(screen.getByTestId('guard.primary'));
  await waitFor(() => expect(sendProductCommand).toHaveBeenCalledWith({type: 'protection.toggle', action: 'turnOn'}, '7'));
  expect(screen.getByText('Protection is off')).toBeTruthy(); // no optimistic success
  act(() => push({...productFixture, revision: 3, protection: {...productFixture.protection, lifecycle: 'protected', action: 'turnOff'}}));
  expect(screen.getByText('Protection is on')).toBeTruthy();
});

test('native flow uses completion contract and hardware back returns to Guard', async () => {
  render(<App />);
  await screen.findByTestId('guard.primary');
  fireEvent.press(screen.getByTestId('nav.filters'));
  await screen.findByTestId('flow.filters');
  expect(screen.getByText('My filter')).toBeTruthy();
  fireEvent.press(screen.getByTestId('flow.filters'));
  await waitFor(() => expect(sendProductCommand).toHaveBeenCalledWith({type: 'native.flow', flow: 'filters'}, '7'));
  await act(async () => { expect(back()).toBe(true); });
  expect(screen.getByTestId('guard.screen')).toBeTruthy();
  expect(back()).toBe(false);
});

test('late initial read cannot overwrite a newer push', async () => {
  let resolve!: (snapshot: ProductSnapshot) => void;
  jest.mocked(getProductSnapshot).mockReturnValueOnce(new Promise(done => { resolve = done; }));
  render(<App />);
  act(() => push({...productFixture, revision: 9, protection: {...productFixture.protection, lifecycle: 'protected'}}));
  await act(async () => { resolve(productFixture); });
  expect(screen.getByText('Protection is on')).toBeTruthy();
});

test('background clears evidence and ignores pushes until foreground refresh', async () => {
  render(<App />);
  await screen.findByText('My filter');
  act(() => appState('background'));
  expect(screen.queryByText('My filter')).toBeNull();
  act(() => push({...productFixture, revision: 3}));
  expect(screen.queryByText('My filter')).toBeNull();
  act(() => appState('active'));
  await screen.findByText('My filter');
});

test('onboarding, locked evidence, and missing activity counts remain honest', async () => {
  jest.mocked(getProductSnapshot).mockResolvedValueOnce({...productFixture, available: false, onboardingRequired: true});
  render(<App />);
  await screen.findByText('Set up your protection');
  expect(screen.queryByTestId('guard.primary')).toBeNull();
  fireEvent.press(screen.getByTestId('flow.onboarding'));
  await waitFor(() => expect(sendProductCommand).toHaveBeenCalledWith({type: 'native.flow', flow: 'onboarding'}, '7'));
  act(() => push({...productFixture, revision: 10, filters: {locked: true, available: false, rows: []}, activity: {...productFixture.activity, allowed: null, blocked: null}}));
  expect(screen.queryByText('My filter')).toBeNull();
  jest.mocked(getProductSnapshot).mockResolvedValue({...productFixture, revision: 11, activity: {...productFixture.activity, allowed: null, blocked: null}});
  fireEvent.press(screen.getByTestId('nav.activity'));
  await screen.findByText('Counts are not being retained');
  expect(screen.queryByText('0 blocked')).toBeNull();
});

test('chained settings remain reachable and back returns to Settings', async () => {
  render(<App />);
  await screen.findByTestId('guard.primary');
  fireEvent.press(screen.getByTestId('nav.settings'));
  await screen.findByTestId('nav.chainedUpstream');
  fireEvent.press(screen.getByTestId('nav.chainedUpstream'));
  await screen.findByTestId('chainedUpstream.screen');
  await act(async () => { expect(back()).toBe(true); });
  expect(screen.getByTestId('settings.screen')).toBeTruthy();
});

test('applies Android top inset and unsubscribes on unmount', async () => {
  jest.replaceProperty(StatusBar, 'currentHeight', 24);
  const view = render(<App />);
  await screen.findByTestId('guard.primary');
  const style = StyleSheet.flatten(screen.getByTestId('app.container').props.style) as ViewStyle;
  expect(style.paddingTop).toBe(24);
  view.unmount();
  expect(unsubscribe).toHaveBeenCalled();
});

test('a rejected command remains visible across ordinary snapshot events', async () => {
  jest.mocked(sendProductCommand).mockResolvedValueOnce({schema: 1, status: 'rejected', reason: 'stale-generation'});
  render(<App />);
  await screen.findByTestId('guard.primary');
  fireEvent.press(screen.getByTestId('guard.primary'));
  await screen.findByText('Lava changed while this screen was open. Try again.');
  act(() => push({...productFixture, revision: 22}));
  expect(screen.getByTestId('product.error')).toBeTruthy();
});

test('native cancellation does not fabricate failure or protection success', async () => {
  jest.mocked(sendProductCommand).mockResolvedValueOnce({schema: 1, status: 'cancelled', reason: 'vpn-consent'});
  render(<App />);
  await screen.findByTestId('guard.primary');
  fireEvent.press(screen.getByTestId('guard.primary'));
  await waitFor(() => expect(sendProductCommand).toHaveBeenCalledTimes(1));
  expect(screen.queryByTestId('product.error')).toBeNull();
  expect(screen.getByText('Protection is off')).toBeTruthy();
});


test('tab change hides evidence until native revocation and ignores intervening old pushes', async () => {
  render(<App />);
  await screen.findByText('My filter');
  let revoked!: (value: {schema: 1; status: 'completed'; reason: null}) => void;
  jest.mocked(sendProductCommand).mockReturnValueOnce(new Promise(done => { revoked = done; }));
  fireEvent.press(screen.getByTestId('nav.filters'));
  expect(sendProductCommand).toHaveBeenCalledWith({type: 'navigation.endTurn'}, '');
  expect(screen.queryByText('My filter')).toBeNull();
  act(() => push({...productFixture, revision: 2}));
  expect(screen.queryByText('My filter')).toBeNull();
  jest.mocked(getProductSnapshot).mockResolvedValue({...productFixture, revision: 3, filters: {locked: true, available: false, rows: []}});
  await act(async () => revoked({schema: 1, status: 'completed', reason: null}));
  await screen.findByText('Unlock Filters');
  expect(screen.queryByText('My filter')).toBeNull();
});

test('locked RN surface requests native authorization without transmitting a passcode', async () => {
  jest.mocked(getProductSnapshot).mockResolvedValue({...productFixture, filters: {locked: true, available: false, rows: []}});
  render(<App />);
  await screen.findByTestId('guard.primary');
  fireEvent.press(screen.getByTestId('nav.filters'));
  await screen.findByText('Unlock Filters');
  fireEvent.press(screen.getByText('Unlock Filters'));
  await waitFor(() => expect(sendProductCommand).toHaveBeenCalledWith({type: 'navigation.authorize', surface: 'filterEditing'}, '7'));
  expect(screen.queryByText('My filter')).toBeNull();
});

test('failed revocation keeps old evidence hidden and refresh retries revocation', async () => {
  render(<App />);
  await screen.findByText('My filter');
  jest.mocked(sendProductCommand).mockRejectedValueOnce(new Error('transport'));
  fireEvent.press(screen.getByTestId('nav.filters'));
  await screen.findByText('Lava could not read its current state. Try again.');
  act(() => push({...productFixture, revision: 2}));
  expect(screen.queryByText('My filter')).toBeNull();
  jest.mocked(getProductSnapshot).mockResolvedValue({...productFixture, revision: 3, filters: {locked: true, available: false, rows: []}});
  fireEvent.press(screen.getByText('Try again'));
  await screen.findByText('Unlock Filters');
  expect(sendProductCommand).toHaveBeenCalledTimes(2);
});
