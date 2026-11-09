import {AppState} from 'react-native';
import {AppStore, backgroundAuthorityScope, backgroundPrivacyCoverRequired} from '../canonical/app/store';
import type {Spec} from '../canonical/specs/NativeLavaApp';

// A complete, populated frame: the same shape the Guard/Activity/Settings surfaces
// render, so "content dropped" is observable rather than inferred.
const populated = (revision: number, security: object, generation: string | null = 'g1') => JSON.stringify({
  schema: 1, fullApp: true, revision, foreground: true, available: true, generation,
  presentation: {locale: 'en', textScales: null},
  security,
  filters: [{id: 'active', name: 'Everyday protection', frozen: false, count: '42', lists: [], shareable: true, shareSummary: ''}],
});

// The ACTUAL native shape for an `application.started == false` frame: the redaction preserves
// only `graphGeneration` and `security`, every content family is empty, and `available` is false.
const background = (revision: number, security: object, generation: string | null = 'g1') => JSON.stringify({
  schema: 1, fullApp: true, revision, foreground: false, available: false, generation,
  security,
  filters: [],
});

const allOff = {appUnlockCoverChoice:false,unavailable: false, hasAuthenticationMethod: false, protectedSurfaces: [], showBiometrics: false, canEnableBiometrics: false, biometricTitle: '', status: ''};
const optedIn = {appUnlockCoverChoice:true,unavailable: false, hasAuthenticationMethod: true, protectedSurfaces: ['appUnlock'], showBiometrics: false, canEnableBiometrics: false, biometricTitle: '', status: ''};
const unavailable = {unavailable: true, showBiometrics: false, canEnableBiometrics: false, biometricTitle: '', status: ''};

function setup(initial: string) {
  AppState.currentState = 'active';
  const listeners: ((state: string) => void)[] = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    listeners.push(listener as (state: string) => void);
    return {remove: jest.fn()};
  });
  let snapshotEvent: (value: string) => void = () => {};
  const native = {
    getSnapshot: jest.fn(async () => initial),
    command: jest.fn(async () => JSON.stringify({snapshot: JSON.parse(initial), result: null})),
    onSnapshot: (callback: (value: string) => void) => {snapshotEvent = callback; return {remove: jest.fn()};},
  };
  const store = new AppStore(native as unknown as Spec);
  const disconnect = store.connect();
  const appState = (state: string) => { AppState.currentState = state as typeof AppState.currentState; listeners.forEach(l => l(state)); };
  const emit = (value: string) => snapshotEvent(value);
  return {store, native, disconnect, appState, emit};
}

afterEach(() => { jest.restoreAllMocks(); });

test('the JS policy mirrors the native opt-in: uncertain/credential-only/opted-in', () => {
  expect(backgroundPrivacyCoverRequired({security: allOff} as never)).toBe(false);
  expect(backgroundPrivacyCoverRequired({security: optedIn} as never)).toBe(true);
  expect(backgroundPrivacyCoverRequired({security: unavailable} as never)).toBe(true);
  expect(backgroundPrivacyCoverRequired({security: {appUnlockCoverChoice:false,unavailable: false, hasAuthenticationMethod: true} as never} as never)).toBe(false);
  expect(backgroundPrivacyCoverRequired({} as never)).toBe(true);
});

test('the authority scope is the owner generation plus availability/recovery, not the opt-in set', () => {
  const left = backgroundAuthorityScope({generation: 'g1', security: {...allOff, protectedSurfaces: ['b', 'a']}} as never);
  const right = backgroundAuthorityScope({generation: 'g1', security: {...allOff, protectedSurfaces: ['a', 'b']}} as never);
  expect(left).toBe(right);
  expect(backgroundAuthorityScope({generation: 'g2', security: allOff} as never)).not.toBe(left);
  expect(backgroundAuthorityScope({generation: null, security: allOff} as never)).not.toBe(left);
  // A credential/protected-surface commit is concealment metadata, not an authority boundary: the
  // same owner generation still holds the display and the native cover conceals it.
  expect(backgroundAuthorityScope({generation: 'g1', security: optedIn} as never)).toBe(
    backgroundAuthorityScope({generation: 'g1', security: allOff} as never));
  // A genuine owner change (secure-storage unavailability / recovery) still invalidates.
  expect(backgroundAuthorityScope({generation: 'g1', security: unavailable} as never)).not.toBe(left);
  expect(backgroundAuthorityScope({generation: 'g1', security: {...allOff, recoveryRequired: true}} as never)).not.toBe(left);
});

test('a protected surface committed mid-session retains through a routine background', async () => {
  // The production shape: the accepted frame predates the commit (all-off), then the concealed
  // background frame carries the just-committed credential + protected surface. The owner generation
  // is unchanged, so this is a routine background: the accepted display must survive so the open
  // editor/import is not unmounted. Regression for the on-device concealment failure.
  const {store, disconnect, emit} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);
    expect(accepted?.filters?.[0]?.name).toBe('Everyday protection');

    emit(background(2, optedIn));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(1);
  } finally {disconnect();}
});

test('Security off: a production background frame from the native EVENT is retained, reads revoked', async () => {
  const {store, disconnect, emit} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.filters?.[0]?.name).toBe('Everyday protection');
    const epoch = store.getReadEpoch();

    // The production background frame: foreground:false, available:false, empty content. It must
    // NOT replace the accepted populated display: exactly one outcome, the retained frame.
    emit(background(2, allOff));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(1);
    expect(store.getSnapshot().snapshot?.filters?.[0]?.name).toBe('Everyday protection');
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);
  } finally {disconnect();}
});

test('Security off: a production background frame from refresh() is retained, reads revoked', async () => {
  const {store, native, disconnect} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    const epoch = store.getReadEpoch();

    native.getSnapshot.mockResolvedValueOnce(background(2, allOff));
    await store.refresh();
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(1);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);
  } finally {disconnect();}
});

test('delayed ordering: a late background frame cannot clobber an accepted populated frame; a later populated frame adopts', async () => {
  const {store, native, disconnect, emit} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);

    // The background frame is delivered after the populated one (native event and refresh).
    native.getSnapshot.mockResolvedValueOnce(background(6, allOff));
    emit(background(5, allOff));
    await store.refresh();
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(1);
    expect(store.getSnapshot().snapshot?.filters?.[0]?.name).toBe('Everyday protection');

    // A later populated frame is a genuine foreground projection and adopts without a gap.
    native.getSnapshot.mockResolvedValueOnce(populated(7, allOff));
    await store.refresh();
    expect(store.getSnapshot().snapshot?.revision).toBe(7);
    expect(store.getSnapshot().snapshot?.filters?.[0]?.name).toBe('Everyday protection');
  } finally {disconnect();}
});

test('Security on: a production background frame is retained for the native cover, reads revoked', async () => {
  const {store, disconnect, emit, appState} = setup(populated(1, optedIn));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);
    const epoch = store.getReadEpoch();

    // The JS keeps the accepted frame so an open editor/import survives; the native app-lock cover
    // conceals protected content over the mounted tree, and every private read is revoked.
    emit(background(2, optedIn));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);

    appState('background');
    expect(store.getSnapshot().snapshot).toBe(accepted);
  } finally {disconnect();}
});

test('an authority boundary: a routine background retains, a credential rotation invalidates the display', async () => {
  const {store, disconnect, emit} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);
    const epoch = store.getReadEpoch();

    // Same owner authority (same generation + security): ordinary backgrounding retains.
    emit(background(2, allOff, 'g1'));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);

    // A credential rotation changes the graph generation: the old display is gone and the
    // authoritative redacted frame is adopted (not the stale populated one). Exactly one outcome.
    emit(background(3, allOff, 'g2'));
    expect(store.getSnapshot().snapshot).not.toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
    expect(store.getSnapshot().snapshot?.filters ?? []).toHaveLength(0);
    expect(store.getSnapshot().snapshot?.available).toBe(false);
  } finally {disconnect();}
});

test('an authority boundary: owner retirement (cleared generation) invalidates the display', async () => {
  const {store, disconnect, emit} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);
    const epoch = store.getReadEpoch();

    emit(background(2, allOff, 'g1'));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);

    // The owner retired: the graph generation is gone, so this is not a routine background.
    emit(background(3, allOff, null));
    expect(store.getSnapshot().snapshot).not.toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
    expect(store.getSnapshot().snapshot?.filters ?? []).toHaveLength(0);
  } finally {disconnect();}
});

test('an authority boundary with Security on adopts the authoritative redacted frame', async () => {
  const {store, disconnect, emit} = setup(populated(1, optedIn));
  try {
    await store.refresh();
    expect(store.getSnapshot().snapshot?.revision).toBe(1);
    const epoch = store.getReadEpoch();

    // A privacy change at the boundary (generation same, security differs): the old display is gone
    // and the authoritative redacted frame is adopted; reads are revoked and the native cover conceals.
    emit(background(2, unavailable, 'g1'));
    expect(store.getSnapshot().snapshot?.revision).toBe(2);
    expect(store.getSnapshot().snapshot?.available).toBe(false);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);
  } finally {disconnect();}
});

test('an unavailable owner retains the accepted frame for the native cover', async () => {
  const {store, disconnect, appState} = setup(populated(1, unavailable));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    appState('background');
    expect(store.getSnapshot().snapshot).toBe(accepted);
  } finally {disconnect();}
});

test('Security off: populated content survives an AppState background and resume without a content gap', async () => {
  const {store, native, disconnect, appState} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.filters?.[0]?.name).toBe('Everyday protection');
    const epoch = store.getReadEpoch();

    appState('background');
    // The frame is retained for display; every private read/epoch is revoked.
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);

    // Resume re-reads and adopts the fresh authoritative frame without a content gap.
    appState('active');
    native.getSnapshot.mockResolvedValueOnce(populated(3, allOff));
    await store.refresh();
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
    expect(store.getSnapshot().snapshot?.filters?.[0]?.name).toBe('Everyday protection');
  } finally {disconnect();}
});

test('a delayed background frame cannot replace the accepted private frame', async () => {
  const {store, native, disconnect, appState} = setup(populated(1, optedIn));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    appState('background');
    expect(store.getSnapshot().snapshot).toBe(accepted);
    // A routine background frame arriving while backgrounded is retained, never adopted over the
    // accepted populated frame.
    native.getSnapshot.mockResolvedValueOnce(background(9, optedIn));
    await store.refresh();
    expect(store.getSnapshot().snapshot).toBe(accepted);
  } finally {disconnect();}
});

test('a retired store keeps its last frame but refuses late refreshes and commands', async () => {
  const {store, native, disconnect} = setup(populated(1, allOff));
  try {
    await store.refresh();
    const retired = store.getSnapshot().snapshot;
    expect(retired?.filters?.[0]?.name).toBe('Everyday protection');
    disconnect();
    // Retirement revokes this handle's future reads; it does not rewrite the frame.
    expect(store.getSnapshot().snapshot).toBe(retired);
    native.getSnapshot.mockResolvedValueOnce(populated(5, allOff));
    await store.refresh();
    expect(store.getSnapshot().snapshot).toBe(retired);
    await expect(store.command({type: 'stats.query'})).rejects.toThrow('The app screen closed before this action started.');
  } finally {disconnect();}
});

test('a delayed refresh cannot replace the accepted frame after a background', async () => {
  const {store, native, disconnect, appState} = setup(populated(1, optedIn));
  try {
    await store.refresh();
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(1);
    // A refresh is in flight when the app backgrounds (reads revoked, frame retained).
    let resolve!: (value: string) => void;
    native.getSnapshot.mockReturnValueOnce(new Promise<string>(done => { resolve = done; }));
    const pending = store.refresh();
    appState('background');
    expect(store.getSnapshot().snapshot).toBe(accepted);
    // The late response resolves a read that predates the background: the read-epoch fence rejects
    // it, so the accepted frame is untouched (a newer private frame cannot be injected from it).
    resolve(populated(2, optedIn));
    await pending;
    expect(store.getSnapshot().snapshot).toBe(accepted);
  } finally {disconnect();}
});

test('Security off: an in-progress filter-edit draft survives background and resume', async () => {
  const frame = (revision: number, foreground: boolean) => JSON.stringify({
    schema: 1, fullApp: true, revision, foreground, available: foreground, generation: 'g1', security: allOff,
    filterSession: {editing: true, id: 'filter-a', blocked: ['draft.example'], savedBlocked: []},
  });
  const {store, native, disconnect, appState} = setup(frame(1, true));
  try {
    await store.refresh();
    const draft = () => (store.getSnapshot().snapshot as unknown as
      {filterSession?: {editing?: boolean; blocked?: string[]}} | null)?.filterSession;
    appState('background');
    expect(draft()?.editing).toBe(true);
    expect(draft()?.blocked).toContain('draft.example');
    // A native redacted background frame (foreground:false, available:false, empty content) must not
    // drop the in-progress draft while Security is off.
    native.getSnapshot.mockResolvedValueOnce(frame(2, false));
    await store.refresh();
    expect(draft()?.editing).toBe(true);
    expect(draft()?.blocked).toContain('draft.example');
  } finally {disconnect();}
});


const explicitAllOff=(payload:string)=>JSON.stringify({...JSON.parse(payload),backgroundPrivacyCoverRequired:false,
  presentationBlocked:JSON.parse(payload).foreground===false});

test('a redacted revision advances native ordering even while its older populated display is retained',async()=>{
  const {store,native,disconnect,emit}=setup(explicitAllOff(populated(1,allOff)));
  try{
    await store.refresh();const accepted=store.getSnapshot().snapshot;
    // Native emitted a later concealed projection; the display still has revision1.
    native.getSnapshot.mockResolvedValue(explicitAllOff(background(10,allOff)));
    emit(explicitAllOff(background(10,allOff)));
    emit(explicitAllOff(populated(5,allOff)));
    await Promise.resolve();await Promise.resolve();
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getPresentationAuthority()).toBe(false);
    native.getSnapshot.mockResolvedValueOnce(explicitAllOff(populated(11,allOff)));
    await store.refresh();
    expect(store.getSnapshot().snapshot?.revision).toBe(11);
    expect(store.getPresentationAuthority()).toBe(true);
  }finally{disconnect();}
});

test('a same-revision all-off inactive marker cannot revoke an already-restored active owner',async()=>{
  const {store,disconnect,emit}=setup(explicitAllOff(populated(12,allOff)));
  try{
    await store.refresh();const accepted=store.getSnapshot().snapshot,readEpoch=store.getReadEpoch();
    emit(explicitAllOff(background(12,allOff)));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getPresentationAuthority()).toBe(true);
    expect(store.getReadEpoch()).toBe(readEpoch);
  }finally{disconnect();}
});

test('a burst of late inactive markers cannot strand a resumed owner behind an overtaken refresh',async()=>{
  const {store,native,disconnect,emit}=setup(explicitAllOff(populated(12,allOff)));
  try{
    await store.refresh();const accepted=store.getSnapshot().snapshot;
    let resolve!:(snapshot:string)=>void;
    native.getSnapshot.mockImplementationOnce(()=>new Promise<string>(yes=>{resolve=yes;}));
    native.getSnapshot.mockResolvedValue(explicitAllOff(populated(16,allOff)));
    // The second marker retires the first refresh's read epoch while that
    // refresh is in flight. The coalesced recovery must re-read the current owner.
    emit(explicitAllOff(background(14,allOff)));
    emit(explicitAllOff(background(15,allOff)));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getPresentationAuthority()).toBe(false);
    resolve(explicitAllOff(populated(16,allOff)));
    for(let turn=0;turn<20;turn++)await Promise.resolve();
    expect(store.getPresentationAuthority()).toBe(true);
    expect(store.getSnapshot().snapshot?.revision).toBe(16);
  }finally{disconnect();}
});

test('saved OFF remains OFF during credential failure and scoped protection never selects the global cover',()=>{
  expect(backgroundPrivacyCoverRequired({security:{...optedIn,appUnlockCoverChoice:false,unavailable:true}} as never)).toBe(false);
  expect(backgroundPrivacyCoverRequired({security:{...allOff,hasAuthenticationMethod:true,protectedSurfaces:['activityViewing']}} as never)).toBe(false);
  expect(backgroundPrivacyCoverRequired({security:{...allOff,appUnlockCoverChoice:null}} as never)).toBe(true);
});
