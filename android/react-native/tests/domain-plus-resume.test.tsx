// Android port of the pinned iOS #856 `a full free %s domain list resumes into Review after Plus
// confirmation` case. A limit-reached `domains.stage` opens the contextual Plus flow for both
// blocked and allowed domains; after entitlement the ORIGINAL action is re-staged exactly once and
// the fresh native token enters Review. `filter.apply` is never called as part of the upgrade.
import React, {type PropsWithChildren} from 'react';
import {Alert, AppState} from 'react-native';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import type {ReviewState} from '../canonical/review/ReviewContext';
import {ReviewContext} from '../canonical/review/ReviewContext';
import {initialSession} from '../canonical/review/session';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {AppearanceStore} from '../canonical/review/appearance-store';
import type {AppSnapshot} from '../canonical/app/contract';
import {AppStore} from '../canonical/app/store';
import type {Spec} from '../canonical/specs/NativeLavaApp';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockCommand = jest.fn();
const mockQuery = jest.fn();
let mockQueryResult: {value?: unknown; refreshing: boolean; error?: string} = {refreshing: false};
let mockFocused = true;
const mockPlusCapture: {reason?: string; resume?: () => Promise<unknown>} = {};

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, setOptions: jest.fn(), getState: () => undefined, addListener: () => () => {}}),
  useIsFocused: () => mockFocused,
  useRoute: () => ({key: 'domain-route', params: undefined}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack}),
  previewNotice: jest.fn(),
}));
// Capture the contextual Plus entry so the test can invoke the entitlement resume exactly as the
// Upgrade screen would, without standing up the whole native billing presentation.
jest.mock('../canonical/review/plus-entry', () => ({
  usePlusEntry: () => (reason: string, resume: () => Promise<unknown>) => {mockPlusCapture.reason = reason; mockPlusCapture.resume = resume;},
}));
jest.mock('../canonical/app/queries', () => ({useAppQuery: (...args: unknown[]) => {mockQuery(...args); return mockQueryResult;}}));
jest.mock('../canonical/specs/NativeLavaReview', () => ({
  __esModule: true,
  default: {stopDemo: jest.fn(), speakDemo: jest.fn().mockResolvedValue(false), getBlocklistCatalog: () => '[]', getLegalNotices: () => JSON.stringify({disclaimer: '', sections: []}), getGuardAccents: () => JSON.stringify({original: {light: '#BF4000', dark: '#FF8855'}})},
}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),SafeAreaProvider: require('react-native').View, SafeAreaView: require('react-native').View, useSafeAreaInsets: () => ({top: 59, bottom: 34, left: 0, right: 0}), useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844})}));

import {DomainListScreen} from '../canonical/review/DiagnosticScreens';

function providerValue(overrides: Partial<ReviewState> = {}): ReviewState {
  const appearance = new AppearanceStore({
    getSnapshot: async () => ({preference: 'system', revision: 0}),
    setPreference: async () => ({preference: 'system', revision: 1}),
    onSnapshot: () => ({remove() {}}),
  });
  return {
    appearance, look: 'original', setLook: jest.fn(),
    draft: initialPreviewDraft(), setDraft: jest.fn(),
    session: {...initialSession(), logs: {...initialSession().logs, 'Domain logs': true}}, setSession: jest.fn(),
    savedDraft: initialPreviewDraft(), setSavedDraft: jest.fn(),
    ...overrides,
  };
}
function Provider({children, value}: PropsWithChildren<{value?: Partial<ReviewState>}>) {
  return <ReviewContext.Provider value={providerValue(value)}>{children}</ReviewContext.Provider>;
}

const originalAppState = AppState.currentState;
beforeEach(() => {
  jest.clearAllMocks();
  mockFocused = true;
  mockCommand.mockResolvedValue(null);
  mockQueryResult = {refreshing: false, value: [{id: 'event', domain: 'tracker.example', metadata: 'Now'}]};
  mockPlusCapture.reason = undefined; mockPlusCapture.resume = undefined;
  Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
});
afterAll(() => Object.defineProperty(AppState, 'currentState', {configurable: true, value: originalAppState}));

test.each(['blocked', 'allowed'] as const)('a full free %s domain list resumes into Review after Plus confirmation', async decision => {
  let attempts = 0;
  mockCommand.mockImplementation(async (input: {type: string}) => {
    if (input.type === 'domains.stage') return ++attempts === 1
      ? {rejection: {title: 'Limit reached', message: 'Upgrade or remove entries', limitReached: true}}
      : {id: 'active', standaloneReview: 'upgraded-review'};
    return null;
  });
  const app = {command: mockCommand} as unknown as AppStore;
  const live = {session: {...initialSession(), filterID: 'filter-a'}, domainHistoryCount: 1} as unknown as AppSnapshot;
  render(<Provider value={{app, live}}><DomainListScreen history /></Provider>);

  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  try {
    await waitFor(() => expect(screen.getByTestId('domain-menu.event')).toBeOnTheScreen());
    // Android pages a >2-action menu; open the second page that carries Block/Allow.
    fireEvent(screen.getByTestId('domain-menu.event'), 'longPress');
    alert.mock.calls.at(-1)![2]!.find(button => button.text === 'More options')!.onPress!();
    alert.mock.calls.at(-1)![2]!.find(button => button.text === (decision === 'blocked' ? 'Block' : 'Allow'))!.onPress!();
    await waitFor(() => expect(mockPlusCapture.reason).toBe(decision === 'blocked' ? 'blockedDomains' : 'allowedDomains'));

    let destination: unknown;
    await act(async () => { destination = await mockPlusCapture.resume!(); });

    expect(mockCommand.mock.calls.filter(([input]) => (input as {type: string}).type === 'domains.stage')).toEqual([
      [{type: 'domains.stage', domain: 'tracker.example', decision}],
      [{type: 'domains.stage', domain: 'tracker.example', decision}],
    ]);
    expect(destination).toEqual({name: 'Review', params: {id: 'active', standaloneReview: 'upgraded-review'}});
    expect(mockCommand.mock.calls.some(([input]) => (input as {type: string}).type === 'filter.apply')).toBe(false);
  } finally { alert.mockRestore(); }
});


test('an abandoned domain result retries exact-token teardown after native refuses the first cancellation', async () => {
  const lifecycleListeners = new Set<(state: 'active') => void>();
  const lifecycle = jest.spyOn(AppState, 'addEventListener').mockImplementation((event, listener) => {
    const onChange = listener as (state: 'active') => void;
    if (event === 'change') lifecycleListeners.add(onChange);
    return {remove: () => lifecycleListeners.delete(onChange)};
  });
  const snapshot = {
    schema: 1, fullApp: true, revision: 1, generation: '7', foreground: true,
    available: true, backgroundPrivacyCoverRequired: false,
    security: {unavailable: false, recoveryRequired: false, protectedSurfaces: []},
    session: {...initialSession(), filterID: 'filter-a'}, domainHistoryCount: 1,
  } as unknown as AppSnapshot;
  let finishStage!: (reply: string) => void;
  const pendingStage = new Promise<string>(resolve => {finishStage = resolve;});
  const ownedReviews = new Set(['abandoned-review', 'successor-review']);
  let cancelAttempts = 0;
  const nativeCommand = jest.fn(async (request: string) => {
    const command = JSON.parse(request) as {type: string; token?: string};
    if (command.type === 'domains.stage') return pendingStage;
    if (command.type === 'domains.cancel') {
      if (++cancelAttempts === 1) throw new Error('The native Activity is not foreground.');
      ownedReviews.delete(command.token!);
      // Teardown snapshots must never adopt a stale page or a successor draft.
      return JSON.stringify({snapshot: {...snapshot, revision: 0}, result: null});
    }
    throw new Error(`Unexpected command ${command.type}`);
  });
  const app = new AppStore({
    command: nativeCommand,
    getSnapshot: async () => JSON.stringify(snapshot),
    onSnapshot: () => ({remove() {}}),
  } as unknown as Spec, snapshot);
  const disconnect = app.connect();
  const view = render(<Provider value={{app, live: snapshot}}><DomainListScreen history /></Provider>);
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  try {
    fireEvent(screen.getByTestId('screen.scroll', {includeHiddenElements: true}), 'layout', {nativeEvent: {layout: {x: 0, y: 0, width: 390, height: 844}}});
    await act(async () => app.completePresentationLayout(app.getPresentationHydration().epoch));
    await waitFor(() => expect(screen.getByTestId('domain-menu.event')).toBeOnTheScreen());
    fireEvent(screen.getByTestId('domain-menu.event'), 'longPress');
    alert.mock.calls.at(-1)![2]!.find(button => button.text === 'More options')!.onPress!();
    alert.mock.calls.at(-1)![2]!.find(button => button.text === 'Block')!.onPress!();
    await waitFor(() => expect(nativeCommand).toHaveBeenCalledTimes(1));
    mockFocused = false;
    view.rerender(<Provider value={{app, live: snapshot}}><DomainListScreen history /></Provider>);
    await act(async () => finishStage(JSON.stringify({snapshot, result: {id: 'filter-a', standaloneReview: 'abandoned-review'}})));
    await waitFor(() => expect(cancelAttempts).toBe(1));
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(ownedReviews.has('abandoned-review')).toBe(true);
    await act(async () => {for (const listener of [...lifecycleListeners]) listener('active');});
    await waitFor(() => expect(cancelAttempts).toBe(2));
    expect(ownedReviews).toEqual(new Set(['successor-review']));
    expect(nativeCommand.mock.calls.map(([request]) => JSON.parse(request))).toEqual([
      {type: 'domains.stage', domain: 'tracker.example', decision: 'blocked'},
      {type: 'domains.cancel', token: 'abandoned-review'},
      {type: 'domains.cancel', token: 'abandoned-review'},
    ]);
    expect(app.getSnapshot().snapshot?.revision).toBe(1);
  } finally {
    view.unmount(); disconnect(); alert.mockRestore(); lifecycle.mockRestore();
  }
});
