// Android port of the #856 P2R standalone Review Plus-resume fix. A capacity-blocked review shows
// the contextual Upgrade footer; after the entitlement is confirmed the owner clears
// `upgradeReason` but the CAPTURED review still holds the refusal, so `reviewCanConfirm` stays
// false and the replacement Confirm would be permanently disabled. The Upgrade resume must
// recapture `filter.review` for the same draft so the fresh review clears the refusal and
// re-enables Confirm. The resume must not apply the filter.
import React, {type PropsWithChildren} from 'react';
import {Alert, AppState} from 'react-native';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import type {ReviewState} from '../canonical/review/ReviewContext';
import {ReviewContext} from '../canonical/review/ReviewContext';
import {initialSession} from '../canonical/review/session';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {AppearanceStore} from '../canonical/review/appearance-store';
import type {AppSnapshot} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockDispatch = jest.fn();
const mockSetOptions=jest.fn();
const mockCommand = jest.fn();
const mockRetireDomainReview=jest.fn().mockResolvedValue(undefined);
const mockRetirePlusDestination=jest.fn().mockResolvedValue(undefined);
const mockPlusCapture: {reason?: string; resume?: () => Promise<unknown>} = {};
const defaultReviewParams = {key: 'review-route', params: {id: 'filter-a', standaloneReview: 'standalone-token'}};
let mockRouteParams: {key: string; params: Record<string, unknown>} = defaultReviewParams;

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, dispatch: mockDispatch, setOptions: mockSetOptions, getState: () => undefined, addListener: () => () => {}}),
  useIsFocused: () => true,
  useRoute: () => mockRouteParams,
  usePreventRemove: jest.fn(),
  StackActions: {replace: (name: string, params: unknown) => ({type: 'REPLACE', payload: {name, params}})},
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, dispatch: mockDispatch}),
  previewNotice: jest.fn(),
}));
jest.mock('../canonical/review/filter-route', () => ({useFilterRoute: () => ({id: 'filter-a', ready: true})}));
// Capture the contextual Plus entry so the test can invoke the entitlement resume exactly as the
// Upgrade screen would, without standing up the whole native billing presentation.
jest.mock('../canonical/review/plus-entry', () => ({
  usePlusEntry: () => (reason: string, resume?: () => Promise<unknown>) => {
    mockPlusCapture.reason = reason;
    mockPlusCapture.resume = resume;
  },
}));
jest.mock('../canonical/app/queries', () => ({useAppQuery: () => ({refreshing: false})}));
jest.mock('../canonical/specs/NativeLavaReview', () => ({
  __esModule: true,
  default: {stopDemo: jest.fn(), speakDemo: jest.fn().mockResolvedValue(false), getBlocklistCatalog: () => '[]', getLegalNotices: () => JSON.stringify({disclaimer: '', sections: []}), getGuardAccents: () => JSON.stringify({original: {light: '#BF4000', dark: '#FF8855'}})},
}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),SafeAreaProvider: require('react-native').View, SafeAreaView: require('react-native').View,  useSafeAreaInsets: () => ({top: 59, bottom: 34, left: 0, right: 0}), useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844})}));

import {ReviewScreen,FilterScreen} from '../canonical/review/FilterScreens';
import {UpgradeScreen} from '../canonical/review/SettingsScreens';
import {usePlusIntents,PlusResumePending} from '../canonical/review/plus-intents';

function providerValue(overrides: Partial<ReviewState> = {}): ReviewState {
  const appearance = new AppearanceStore({
    getSnapshot: async () => ({preference: 'system', revision: 0}),
    setPreference: async () => ({preference: 'system', revision: 1}),
    onSnapshot: () => ({remove() {}}),
  });
  return {
    appearance, look: 'original', setLook: jest.fn(),
    draft: initialPreviewDraft(), setDraft: jest.fn(),
    session: initialSession(), setSession: jest.fn(),
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
  mockCommand.mockResolvedValue(null);
  mockPlusCapture.reason = undefined; mockPlusCapture.resume = undefined;
  mockRouteParams = defaultReviewParams;
  Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
});
afterAll(() => Object.defineProperty(AppState, 'currentState', {configurable: true, value: originalAppState}));

function blockedReview(): AppSnapshot {
  return {
    session: {...initialSession(), editing: true, filterID: 'filter-a', activeFilterID: 'filter-a'},
    filterEditing: {canSave: true, reviewCanConfirm: false, validation: 'blockedDomains', upgradeReason: 'blockedDomains', refreshing: false, lists: [], blocked: [], allowed: []},
    filterPreparationPresented: false,
    plus: {enabled: false},
  } as unknown as AppSnapshot;
}
function entitledReview(): AppSnapshot {
  return {
    session: {...initialSession(), editing: true, filterID: 'filter-a', activeFilterID: 'filter-a'},
    filterEditing: {canSave: true, reviewCanConfirm: true, validation: '', upgradeReason: '', refreshing: false, lists: [], blocked: [], allowed: []},
    filterPreparationPresented: false,
    plus: {enabled: true},
  } as unknown as AppSnapshot;
}

test('a capacity-blocked review recaptures filter.review after Plus and enables Confirm without applying', async () => {
  let reviewCalls = 0;
  mockCommand.mockImplementation(async (input: {type: string}) => {
    if (input.type === 'filter.review') { reviewCalls += 1; return reviewCalls === 1 ? 'blocked-token' : 'fresh-token'; }
    return null;
  });
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const view = render(<Provider value={{app, live: blockedReview()}}><ReviewScreen /></Provider>);

  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'filter.review', id: 'filter-a'}));
  // The blocked review offers Upgrade instead of a dead Confirm.
  expect(screen.queryByRole('button', {name: 'Confirm changes'})).toBeNull();
  fireEvent.press(screen.getByRole('button', {name: 'Upgrade'}));
  expect(mockPlusCapture.reason).toBe('blockedDomains');
  expect(typeof mockPlusCapture.resume).toBe('function');

  // The entitlement is confirmed; the owner clears upgradeReason and accepts a fresh review.
  let destination: unknown = 'unset';
  await act(async () => { destination = await mockPlusCapture.resume!(); });
  expect(destination).toBeUndefined();
  expect(mockCommand.mock.calls.filter(([input]) => (input as {type: string}).type === 'filter.review').length)
    .toBeGreaterThanOrEqual(2);

  view.rerender(<Provider value={{app, live: entitledReview()}}><ReviewScreen /></Provider>);
  const confirm = await screen.findByRole('button', {name: 'Confirm changes'});
  await waitFor(() => expect(confirm.props.accessibilityState.disabled).toBe(false));
  expect(mockCommand.mock.calls.some(([input]) => (input as {type: string}).type === 'filter.apply')).toBe(false);
});

test('a standalone Review retires its token through the queued owner teardown without an unconditional filter close',async()=>{
  const app={command:mockCommand,retireDomainReview:mockRetireDomainReview} as unknown as AppStore;
  const view=render(<Provider value={{app,live:entitledReview()}}><ReviewScreen/></Provider>);
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'filter.review',id:'filter-a'}));
  await act(async()=>{view.unmount();});
  expect(mockRetireDomainReview.mock.calls).toEqual([['standalone-token']]);
  expect(mockCommand.mock.calls.some(([input])=>input.type==='filter.close')).toBe(false);
});

test('a replacement editor survives delayed standalone Review teardown',async()=>{
  let finish!:()=>void;
  mockRetireDomainReview.mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve;}));
  const app={command:mockCommand,retireDomainReview:mockRetireDomainReview} as unknown as AppStore;
  const view=render(<Provider value={{app,live:entitledReview()}}><ReviewScreen/></Provider>);
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'filter.review',id:'filter-a'}));
  await act(async()=>{view.unmount();});
  // A newer native owner can replace the draft before the queued token cancellation settles.
  // Only native's matching token/draft fence may retire it; RN must never issue a broad close.
  await act(async()=>{finish();});
  expect(mockRetireDomainReview).toHaveBeenCalledWith('standalone-token');
  expect(mockCommand.mock.calls.some(([input])=>input.type==='filter.close'||input.type==='filter.cancel')).toBe(false);
});

test('a native review-fact change does not re-issue filter.review (no capture loop)', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const view = render(<Provider value={{app, live: blockedReview()}}><ReviewScreen /></Provider>);
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'filter.review', id: 'filter-a'}));
  const count = () => mockCommand.mock.calls.filter(([input]) => (input as {type: string}).type === 'filter.review').length;
  const before = count();
  // `filter.review`'s own response mutates reviewCanConfirm/validation; a live change to those facts
  // must NOT re-run the capture effect, or the screen loops under snapshot churn.
  view.rerender(<Provider value={{app, live: entitledReview()}}><ReviewScreen /></Provider>);
  await act(async () => { await Promise.resolve(); });
  expect(count()).toBe(before);
});

// --- Unlock-aware Plus resume ---------------------------------------------------------------
// A resume issued while App Unlock is engaged is refused by native; the Upgrade effect must wait
// for the native grant instead of stranding the registered intent. These drive the real
// `UpgradeScreen` effect through the shared PlusIntent registry (no billing presentation).
function securedReview(protectedSurfaces: string[], authenticatedSurfaces: string[]): AppSnapshot {
  return {
    ...entitledReview(),
    security: {unavailable: false, hasAuthenticationMethod: true, protectedSurfaces, authenticatedSurfaces, showBiometrics: false, canEnableBiometrics: false, biometricTitle: 'Biometrics', status: ''},
  } as unknown as AppSnapshot;
}
// No configured method: fail-open, so a legacy/fixture snapshot is authorized.
const authorizedReview = () => securedReview([], []);
// App Unlock opted in without a current-turn grant: the whole-app lock is engaged.
const lockedReview = () => securedReview(['appUnlock'], []);
// App Unlock authenticated for the current navigation turn.
const grantedReview = () => securedReview(['appUnlock'], ['appUnlock']);

function Registrar({resume, onId}: {resume: () => Promise<unknown>; onId: (id: string) => void}) {
  const intents = usePlusIntents();
  const registered = React.useRef(false);
  if (!registered.current) { registered.current = true; onId(intents.add(resume as Parameters<typeof intents.add>[0])); }
  return null;
}
function upgradeTree(app: AppStore, live: AppSnapshot, resume: () => Promise<unknown>, onId: (id: string) => void) {
  return <Provider value={{app, live}}><Registrar resume={resume} onId={onId} /><UpgradeScreen /></Provider>;
}

test('a resume refused while App Unlock is engaged retries once the native grant lands', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  let rejectFirst!: (error: Error) => void;
  const first = new Promise<never>((_, reject) => { rejectFirst = reject; });
  const resume = jest.fn()
    .mockImplementationOnce(() => first)
    .mockResolvedValueOnce({name: 'CustomEntry' as const, params: {id: 'native-token', kind: 'dns' as const}});
  const onId = jest.fn();
  const view = render(upgradeTree(app, authorizedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // The native lock snapshot lands while the resume is pending: the effect re-runs but stays latched.
  await act(async () => { view.rerender(upgradeTree(app, lockedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // The owner refuses the pending resume because App Unlock is still engaged.
  await act(async () => { rejectFirst(new Error('app-locked')); });
  expect(resume).toHaveBeenCalledTimes(1); // gated by the lock; the retry re-run cannot bypass it
  // The current turn is authenticated: the effect re-runs and completes exactly once.
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(2);
  expect(mockDispatch).toHaveBeenCalledWith({type: 'REPLACE', payload: {name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}}});
  // A later render never replays a completed resume.
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(2);
});

test('authorization granted for the current turn re-runs the effect without an AppState transition', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const resume = jest.fn().mockResolvedValue({name: 'CustomEntry' as const, params: {id: 'native-token', kind: 'dns' as const}});
  const onId = jest.fn();
  const view = render(upgradeTree(app, lockedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, lockedReview(), resume, onId)); });
  expect(resume).not.toHaveBeenCalled();
  // Only the native grant changes; no AppState transition is emitted.
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  expect(mockDispatch).toHaveBeenCalledWith({type: 'REPLACE', payload: {name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}}});
});

test('a grant that lands before the rejected promise settles still retries (no strand)', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  let rejectFirst!: (error: Error) => void;
  const first = new Promise<never>((_, reject) => { rejectFirst = reject; });
  const resume = jest.fn()
    .mockImplementationOnce(() => first)
    .mockResolvedValueOnce({name: 'CustomEntry' as const, params: {id: 'native-token', kind: 'dns' as const}});
  const onId = jest.fn();
  const view = render(upgradeTree(app, authorizedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // The grant lands while the resume promise is still pending; the derived dep is already true, so
  // only the retry counter can force the effect to re-run after the refusal.
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  await act(async () => { rejectFirst(new Error('app-locked')); });
  expect(resume).toHaveBeenCalledTimes(2);
  expect(mockDispatch).toHaveBeenCalledWith({type: 'REPLACE', payload: {name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}}});
});

test('a persistently refused resume retries at most once for the same unlock grant', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  // A refusal that keeps naming the lock must not schedule a render per rejection: the retry is
  // bounded to one per concrete grant, so this settles at the initial attempt plus one retry.
  const resume = jest.fn().mockRejectedValue(new Error('app-locked'));
  const onId = jest.fn();
  const view = render(upgradeTree(app, authorizedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  expect(resume).toHaveBeenCalledTimes(2);
});

test('a user-cancelled authentication never auto-retries or replays', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const resume = jest.fn().mockRejectedValueOnce(new Error('Authentication cancelled.'));
  const onId = jest.fn();
  const view = render(upgradeTree(app, grantedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // A later authorization/entitlement render cannot replay a cancelled prompt.
  await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  expect(mockDispatch).not.toHaveBeenCalled();
});

test('source retirement while a resume is pending never dispatches its destination', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  let resolveFirst!: (destination: unknown) => void;
  const first = new Promise(resolve => { resolveFirst = resolve; });
  const resume = jest.fn().mockImplementationOnce(() => first);
  const onId = jest.fn();
  const view = render(upgradeTree(app, grantedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // The owner retires the source screen (credential-graph rotation / sign-out) while pending.
  await act(async () => { view.unmount(); });
  await act(async () => { resolveFirst({name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}}); });
  expect(mockDispatch).not.toHaveBeenCalled();
  // The retired intent is gone; a fresh mount cannot replay it.
  const remount = render(upgradeTree(app, grantedReview(), resume, onId));
  await act(async () => { remount.rerender(upgradeTree(app, grantedReview(), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
});

// --- Non-lock and transient prerequisite failures -------------------------------------------
// A refusal that is neither a user cancellation nor an App-Unlock lock must still release the
// once-guard and schedule a bounded retry, so the registered intent is never stranded. The retry
// is bounded by a (grant, VPN-prerequisite) identity, so a persistent failure settles.
function withVpn(needsPlus: boolean): AppSnapshot {
  return {...authorizedReview(), vpn: {needsPlus}} as unknown as AppSnapshot;
}

test('a non-lock resume failure schedules a bounded retry instead of stranding the intent', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  const resume = jest.fn().mockRejectedValue(new Error('temporarily refused'));
  const onId = jest.fn();
  const view = render(upgradeTree(app, authorizedReview(), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  // Exactly one bounded retry for the same (grant, prerequisite) identity, then settle.
  expect(resume).toHaveBeenCalledTimes(2);
  expect(alertSpy.mock.calls.at(-1)?.slice(0, 2)).toEqual(['Lava', 'temporarily refused']);
  alertSpy.mockRestore();
});

test('a transient VPN prerequisite refusal stays registered and retries when the family refreshes', async () => {
  const app = {command: mockCommand,retireDomainReview:mockRetireDomainReview,retirePlusDestination:mockRetirePlusDestination} as unknown as AppStore;
  const resume = jest.fn()
    .mockRejectedValueOnce(new PlusResumePending())
    .mockResolvedValue({name: 'CustomEntry' as const, params: {id: 'native-token', kind: 'dns' as const}});
  const onId = jest.fn();
  const view = render(upgradeTree(app, withVpn(true), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, withVpn(true), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  // Entitlement flips but the VPN family is still stale: a pending refusal is not a user cancel, so
  // the intent stays registered and no destination is dispatched.
  await act(async () => { view.rerender(upgradeTree(app, withVpn(true), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  expect(mockDispatch).not.toHaveBeenCalled();
  // The refreshed family lands (needsPlus false): the effect re-runs and resumes exactly once.
  await act(async () => { view.rerender(upgradeTree(app, withVpn(false), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(2);
  expect(mockDispatch).toHaveBeenCalledWith({type: 'REPLACE', payload: {name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}}});
});

test('independent review: VPN prerequisite refresh before pending rejection still resumes', async () => {
  const app = {command: mockCommand} as unknown as AppStore;
  let rejectPending!: (error: Error) => void;
  const pending = new Promise<never>((_, reject) => { rejectPending = reject; });
  const resume = jest.fn().mockImplementationOnce(() => pending)
    .mockResolvedValue({name: 'CustomEntry', params: {id: 'native-token', kind: 'dns'}});
  const onId = jest.fn();
  const view = render(upgradeTree(app, withVpn(true), resume, onId));
  mockRouteParams = {key: 'upgrade-route', params: {reason: 'customDNS', intent: onId.mock.calls.at(-1)![0] as string}};
  await act(async () => { view.rerender(upgradeTree(app, withVpn(true), resume, onId)); });
  expect(resume).toHaveBeenCalledTimes(1);
  await act(async () => { view.rerender(upgradeTree(app, withVpn(false), resume, onId)); });
  await act(async () => { rejectPending(new PlusResumePending()); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  expect(resume).toHaveBeenCalledTimes(2);
  expect(mockDispatch).toHaveBeenCalled();
});

test('independent review: abandoned Review cleanup replays after foreground rejection', async () => {
  const {AppStore: ActualAppStore} = require('../canonical/app/store');
  const listeners = new Set<(state: string) => void>();
  const listenerSpy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    listeners.add(listener as (state: string) => void);
    return {remove: () => listeners.delete(listener as (state: string) => void)} as never;
  });
  let revision = 1, nativeForeground = true, stagedDraftExists = true;
  const frame = () => ({...authorizedReview(), schema: 1, fullApp: true, revision,
    generation: 'same-owner', foreground: true, available: true});
  const nativeCommand = jest.fn(async (encoded: string) => {
    const input = JSON.parse(encoded);
    if (input.type === 'domains.cancel') {
      if (!nativeForeground) throw new Error('not-foreground');
      stagedDraftExists = false;
    }
    return JSON.stringify({snapshot: {...frame(), revision: ++revision}, result: null});
  });
  const app = new ActualAppStore({getSnapshot: async () => JSON.stringify(frame()),
    command: nativeCommand, onSnapshot: () => ({remove() {}})}) as AppStore;
  const disconnect = app.connect();
  await app.refresh();
  // This fixture mounts only the Upgrade subtree. Commit the real store's initial
  // native layout boundary, which the full application normally acknowledges.
  await act(async()=>{
    app.completePresentationLayout(app.getPresentationHydration().epoch);
  });
  await waitFor(()=>expect(app.getPresentationHydration().required).toBe(false));
  let resolveResume!: (destination: unknown) => void;
  const resume = jest.fn(() => new Promise(resolve => { resolveResume = resolve; }));
  const onId = jest.fn();
  const view = render(upgradeTree(app, authorizedReview(), resume, onId));
  try {
    mockRouteParams = {key: 'upgrade-route', params: {reason: 'blockedDomains', intent: onId.mock.calls.at(-1)![0] as string}};
    await act(async () => { view.rerender(upgradeTree(app, authorizedReview(), resume, onId)); });
    expect(resume).toHaveBeenCalledTimes(1);
    nativeForeground = false;
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'background'});
    await act(async () => { for (const listener of listeners) listener('background'); });
    await act(async () => { resolveResume({name: 'Review', params: {id: 'filter-a', standaloneReview: 'abandoned-token'}}); });
    expect(nativeCommand.mock.calls.some(([encoded]) => JSON.parse(encoded).type === 'domains.cancel')).toBe(true);
    expect(mockDispatch).not.toHaveBeenCalled();
    nativeForeground = true;
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
    await act(async () => { for (const listener of listeners) listener('active'); });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(stagedDraftExists).toBe(false);
  } finally {
    view.unmount(); disconnect(); listenerSpy.mockRestore();
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
  }
});


function capacityFilter():AppSnapshot {
  return {...blockedReview(),generation:'7',nativeHostID:'filter-host',filterSession:{id:'filter-a',lease:'original-draft'},
    session:{...blockedReview().session,editing:true},
    filters:[{id:'filter-a',name:'My filter',count:26,lists:[],frozen:false,shareable:false}],
    filterStatus:{title:'Filter up to date',icon:'checkmark.circle.fill',warning:false},blocklistNames:{},blocklistMetadata:{},
    filterEditing:{canSave:true,reviewCanConfirm:false,validation:'',upgradeReason:'',refreshing:false,lists:[],blocked:[],allowed:[]},
  } as unknown as AppSnapshot;
}
function filterSaveButton():()=>void {
  for(const [options] of [...mockSetOptions.mock.calls].reverse()){
    if(options.unstable_headerRightItems){
      const item=options.unstable_headerRightItems({}).find((item:{label:string})=>item.label==='Save');
      if(item)return item.onPress;
    }
    if(options.headerRight){
      const find=(content:React.ReactNode):(()=>void)|undefined=>{
        let found:(()=>void)|undefined;
        React.Children.forEach(content,child=>{
          if(!React.isValidElement<{accessibilityLabel?:string;onPress?:()=>void;children?:React.ReactNode}>(child))return;
          if(child.props.accessibilityLabel==='Save')found=child.props.onPress;
          else found??=find(child.props.children);
        });return found;
      };
      const found=find(options.headerRight({}));if(found)return found;
    }
  }
  throw new Error('Shipping Filter Save toolbar was not installed.');
}
function filterApp(read:()=>AppSnapshot,authorized:()=>boolean=()=>true):AppStore {
  jest.spyOn(AppState,'addEventListener').mockImplementation(()=>({remove(){}}));
  return {command:mockCommand,getSnapshot:()=>({snapshot:read()}),subscribe:()=>()=>{},
    getPresentationAuthority:authorized,getPresentationHydration:()=>({required:false}),
  } as unknown as AppStore;
}

test.each(['blockedDomains','allowedDomains'] as const)('a freshly discovered native %s Save refusal opens Plus without a generic error or early apply',async reason=>{
  const live=capacityFilter(),app=filterApp(()=>live),alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  mockCommand.mockResolvedValueOnce({upgradeReason:reason}).mockResolvedValueOnce('review');
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  expect(mockPlusCapture.reason).toBe(reason);expect(mockCommand.mock.calls).toEqual([[{type:'filter.save',id:'filter-a'}]]);
  expect(alert).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();
  let destination:unknown;await act(async()=>{destination=await mockPlusCapture.resume!();});
  expect(destination).toEqual({name:'Review',params:{id:'filter-a',standaloneReview:''}});
  expect(mockCommand.mock.calls.every(([command])=>command.type==='filter.save')).toBe(true);
  alert.mockRestore();
});

test.each(['generation','host','lease','authority','unmount'] as const)('a delayed capacity result cannot open Plus after %s retirement',async boundary=>{
  let live=capacityFilter(),authorized=true,resolve!:(value:unknown)=>void;
  const app=filterApp(()=>live,()=>authorized),alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  mockCommand.mockImplementationOnce(()=>new Promise(yes=>{resolve=yes;}));
  const view=render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  if(boundary==='generation')live={...live,generation:'8'};
  if(boundary==='host')live={...live,nativeHostID:'replacement-host'};
  if(boundary==='lease')live={...live,filterSession:{id:'filter-a',lease:'replacement-draft'}};
  if(boundary==='authority')authorized=false;
  if(boundary==='unmount')view.unmount();
  await act(async()=>resolve({upgradeReason:'allowedDomains'}));
  expect(mockPlusCapture.reason).toBeUndefined();expect(mockNavigate).not.toHaveBeenCalled();
  expect(mockCommand).toHaveBeenCalledTimes(1);expect(alert).not.toHaveBeenCalled();alert.mockRestore();
});

test.each(['Authentication cancelled.','This action could not be completed. Try again.'])('a Save failure %s never becomes a capacity intent from later metadata',async message=>{
  let live=capacityFilter();const app=filterApp(()=>live),alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  mockCommand.mockImplementationOnce(async()=>{
    live={...live,filterEditing:{...live.filterEditing!,validation:'allowed_domain_limit',upgradeReason:'allowedDomains'}};
    throw new Error(message);
  });
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  expect(mockPlusCapture.reason).toBeUndefined();expect(mockCommand).toHaveBeenCalledTimes(1);
  if(message==='Authentication cancelled.')expect(alert).not.toHaveBeenCalled();
  else expect(alert).toHaveBeenCalledWith('Couldn’t save',message,undefined,undefined);
  alert.mockRestore();
});


test('a direct saved result retires its lease but releases only its own Save flight for the next edit',async()=>{
  let live=capacityFilter();const app=filterApp(()=>live);
  mockCommand.mockImplementationOnce(async()=>{
    live={...live,filterSession:undefined,session:{...live.session,editing:false}};return 'saved';
  }).mockResolvedValueOnce({upgradeReason:'allowedDomains'});
  const view=render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  live={...capacityFilter(),filterSession:{id:'filter-a',lease:'next-edit'}};
  view.rerender(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  expect(mockCommand).toHaveBeenCalledTimes(2);expect(mockPlusCapture.reason).toBe('allowedDomains');
});

test('a capacity intent cannot resume a successor draft after the original filter visit retires',async()=>{
  let live=capacityFilter();const app=filterApp(()=>live);
  mockCommand.mockResolvedValueOnce({upgradeReason:'blockedDomains'});
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());expect(mockPlusCapture.reason).toBe('blockedDomains');
  live={...live,filterSession:{id:'filter-a',lease:'successor-draft'}};
  let destination:unknown='unset';await act(async()=>{destination=await mockPlusCapture.resume!();});
  expect(destination).toBeUndefined();expect(mockCommand).toHaveBeenCalledTimes(1);
});

test('a held capacity resume cannot return Review for a same-host same-generation successor draft',async()=>{
  let live=capacityFilter(),resolveResume!:(value:unknown)=>void;const app=filterApp(()=>live);
  mockCommand.mockResolvedValueOnce({upgradeReason:'allowedDomains'})
    .mockImplementationOnce(()=>new Promise(yes=>{resolveResume=yes;}));
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());expect(mockPlusCapture.reason).toBe('allowedDomains');
  let destination:unknown='unset',pending!:Promise<void>;
  await act(async()=>{pending=mockPlusCapture.resume!().then(value=>{destination=value;});});
  expect(mockCommand).toHaveBeenCalledTimes(2);
  live={...live,filterSession:{id:'filter-a',lease:'successor-draft'}};
  await act(async()=>{resolveResume('review');await pending;});
  expect(destination).toBeUndefined();expect(mockNavigate).not.toHaveBeenCalled();
});

test('a saved capacity resume releases its own flight after native retires the original lease',async()=>{
  let live=capacityFilter();const app=filterApp(()=>live);
  mockCommand.mockResolvedValueOnce({upgradeReason:'blockedDomains'})
    .mockImplementationOnce(async()=>{
      live={...live,filterSession:undefined,session:{...live.session,editing:false}};return 'saved';
    }).mockResolvedValueOnce({upgradeReason:'allowedDomains'});
  const view=render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  let destination:unknown='unset';await act(async()=>{destination=await mockPlusCapture.resume!();});
  expect(destination).toBeUndefined();
  live={...capacityFilter(),filterSession:{id:'filter-a',lease:'next-edit'}};
  view.rerender(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());
  expect(mockCommand).toHaveBeenCalledTimes(3);expect(mockPlusCapture.reason).toBe('allowedDomains');
});

test('a held capacity resume error cannot alert or retry a successor same-host same-generation draft',async()=>{
  let live=capacityFilter(),rejectResume!:(error:Error)=>void;const app=filterApp(()=>live);
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  let saves=0;mockCommand.mockImplementation(async(command:{type:string})=>{
    if(command.type!=='filter.save')return null;
    if(++saves===1)return {upgradeReason:'allowedDomains'};
    return new Promise((_yes,no)=>{rejectResume=no;});
  });
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());const ownedResume=mockPlusCapture.resume!;
  const resume=jest.fn(()=>ownedResume()),onId=jest.fn();
  const upgrade=render(upgradeTree(app,authorizedReview(),resume,onId));
  mockRouteParams={key:'capacity-upgrade',params:{reason:'allowedDomains',intent:onId.mock.calls.at(-1)![0]}};
  await act(async()=>{upgrade.rerender(upgradeTree(app,authorizedReview(),resume,onId));});
  expect(resume).toHaveBeenCalledTimes(1);
  live={...live,filterSession:{id:'filter-a',lease:'successor-draft'}};
  await act(async()=>{rejectResume(new Error('This action could not be completed. Try again.'));});
  await act(async()=>{await Promise.resolve();await Promise.resolve();});
  expect(alert).not.toHaveBeenCalled();expect(resume).toHaveBeenCalledTimes(1);
  expect(mockCommand.mock.calls.filter(([command])=>command.type==='filter.save')).toHaveLength(2);
  expect(mockDispatch).not.toHaveBeenCalled();alert.mockRestore();
});

test.each(['This action could not be completed. Try again.','Authentication cancelled.'])('a current capacity resume keeps the original %s error semantics',async message=>{
  const live=capacityFilter(),app=filterApp(()=>live),alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  let saves=0;mockCommand.mockImplementation(async(command:{type:string})=>{
    if(command.type!=='filter.save')return null;
    if(++saves===1)return {upgradeReason:'blockedDomains'};
    throw new Error(message);
  });
  render(<Provider value={{app,live,session:{...initialSession(),...live.session}}}><FilterScreen/></Provider>);
  await act(async()=>filterSaveButton()());const ownedResume=mockPlusCapture.resume!;
  const resume=jest.fn(()=>ownedResume()),onId=jest.fn();
  const upgrade=render(upgradeTree(app,authorizedReview(),resume,onId));
  mockRouteParams={key:'capacity-upgrade',params:{reason:'blockedDomains',intent:onId.mock.calls.at(-1)![0]}};
  await act(async()=>{upgrade.rerender(upgradeTree(app,authorizedReview(),resume,onId));});
  await act(async()=>{await Promise.resolve();await Promise.resolve();});
  if(message==='Authentication cancelled.'){
    expect(resume).toHaveBeenCalledTimes(1);expect(alert).not.toHaveBeenCalled();
  }else{
    expect(resume).toHaveBeenCalledTimes(2);expect(alert.mock.calls.at(-1)?.slice(0,2)).toEqual(['Lava',message]);
  }
  expect(mockDispatch).not.toHaveBeenCalled();expect(mockGoBack).not.toHaveBeenCalled();alert.mockRestore();
});

afterAll(()=>jest.restoreAllMocks());
