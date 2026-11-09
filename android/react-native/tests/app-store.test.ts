import {AppState, type AppStateStatus} from 'react-native';
import {AppCommandDetachedError,AppStore} from '../canonical/app/store';
import {appReadCache} from '../canonical/app/read-cache';
import type {Spec} from '../canonical/specs/NativeLavaApp';

// Focused Android port of the pinned iOS #856 `ReactNative/tests/app-store.test.ts`
// domain-review cases. The Android retained presentation revokes read authority
// independently from its display, so staged-review invariants use an explicitly
// active native/JS lifecycle unless the case drives its own interruption.
beforeEach(()=>{Object.defineProperty(AppState,'currentState',{configurable:true,value:'active'});});
afterEach(()=>{Object.defineProperty(AppState,'currentState',{configurable:true,value:'active'});});

const snapshot = (revision: number) => JSON.stringify({schema: 1, fullApp: true, revision});
const response = (revision: number, result: unknown = null) => JSON.stringify({snapshot: JSON.parse(snapshot(revision)), result});
const deferred = <T,>() => {let resolve!: (value: T) => void; let reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => {resolve = yes; reject = no;}); return {promise, resolve, reject};};

function lifecycle() {
  const listeners = new Set<(state: AppStateStatus) => void>();
  const original = AppState.addEventListener;
  AppState.addEventListener = ((_event: string, listener: (state: AppStateStatus) => void) => {
    listeners.add(listener); return {remove: () => {listeners.delete(listener);}};
  }) as typeof AppState.addEventListener;
  return {
    emit(state: AppStateStatus) {
      Object.defineProperty(AppState, 'currentState', {configurable: true, value: state});
      for (const callback of listeners) callback(state);
    },
    restore() {AppState.addEventListener = original; Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});},
  };
}

function setup() {
  let event!: (value: string) => void;
  const native = {
    getSnapshot: jest.fn(async () => snapshot(1)),
    command: jest.fn(async (_request: string) => response(2)),
    onSnapshot: (callback: (value: string) => void) => {event = callback; return {remove: jest.fn()};},
  };
  const store = new AppStore(native as unknown as Spec);
  const disconnect = store.connect();
  return {store, native, disconnect, emit: (value: string) => event(value)};
}

test('account and backup close survives a disconnected renderer without reading a successor',async()=>{
  const {store,native,disconnect}=setup();await store.refresh();disconnect();
  const before=store.getSnapshot();native.getSnapshot.mockClear();
  await store.retireAccountBackup('exact-account-visit');
  expect(native.command).toHaveBeenCalledWith(JSON.stringify({type:'accountBackup.command',id:'exact-account-visit',action:'close'}));
  expect(native.getSnapshot).not.toHaveBeenCalled();expect(store.getSnapshot()).toBe(before);
});
test('account and backup controls bypass a suspended launch',async()=>{
  const {store,native,disconnect}=setup();await store.refresh();
  const pending=deferred<string>();
  native.command.mockImplementation(async raw=>JSON.parse(raw).type==='native.flow'?pending.promise:response(3));
  const begin=store.command({type:'native.flow',flow:'backupSetup'});await Promise.resolve();await Promise.resolve();
  await store.command({type:'accountBackup.command',id:'visit',action:'setup.retry'});
  expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).toContain('accountBackup.command');
  pending.resolve(response(4));await begin;disconnect();
});
test('preparation retirement survives a disconnected renderer without accepting private response fields',async()=>{
  const {store,native,disconnect}=setup();await store.refresh();disconnect();
  const before=store.getSnapshot();
  native.getSnapshot.mockClear();
  await store.retirePreparation('exact-native-attempt');
  expect(native.command).toHaveBeenCalledWith(JSON.stringify({type:'preparation.cancel',id:'exact-native-attempt'}));
  expect(native.getSnapshot).not.toHaveBeenCalled();
  expect(store.getSnapshot()).toBe(before);
});
test('native preparation controls do not wait behind an unrelated suspended JS mutation',async()=>{
  const {store,native,disconnect}=setup();await store.refresh();
  const suspended=deferred<string>();
  native.command.mockImplementation(async raw=>JSON.parse(raw).type==='settings.set'?suspended.promise:response(3));
  const mutation=store.command({type:'settings.set',key:'haptics',value:false});
  await Promise.resolve();
  await store.command({type:'preparation.rendered',id:'exact-native-attempt'});
  expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).toEqual(['settings.set','preparation.rendered']);
  suspended.resolve(response(4));await mutation;disconnect();
});

test('onboarding geometry is presentation-only and does not invalidate reads or replace snapshots', async () => {
  const {store, native, disconnect} = setup();
  await store.refresh();
  const before = store.getSnapshot(), epoch = store.getReadEpoch(), invalidation = store.getInvalidation();
  native.command.mockResolvedValueOnce(JSON.stringify({result: true}));
  const frame = {x: 20, y: 180, width: 96, height: 96};
  await expect(store.command<boolean>({type: 'onboarding.geometry', session: 'rehearsal', phase: 'arriving', layoutRevision: 1, frames: {panel: frame, mascot: frame, action: frame}})).resolves.toBe(true);
  expect(store.getSnapshot()).toBe(before);
  expect(store.getReadEpoch()).toBe(epoch);
  expect(store.getInvalidation()).toBe(invalidation);
  disconnect();
});

test.each(['allowed', 'blocked'] as const)('a %s domain review survives its own snapshot arriving before its token', async decision => {
  const {store, native, emit, disconnect} = setup();
  try {
    await store.refresh();
    const before = {...JSON.parse(snapshot(3)), security: {sourceRevision: '1:0', ownerRevision: 'owner', readRevision: 1}};
    emit(JSON.stringify(before));
    const after = {...before, revision: 4, security: {...before.security, sourceRevision: '2:0'}};
    const result = {id: 'active', standaloneReview: 'owned-review'};
    const epoch = store.getReadEpoch();
    native.command.mockImplementationOnce(async () => {emit(JSON.stringify(after)); return JSON.stringify({snapshot: after, result});});
    await expect(store.command({type: 'domains.stage', domain: 'example.com', decision})).resolves.toEqual(result);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);
    expect(store.getSnapshot().snapshot?.revision).toBe(4);
    expect(native.command).toHaveBeenCalledTimes(1);
  } finally {disconnect();}
});

test('a domain capacity rejection survives its own snapshot so the upsell can open', async () => {
  const {store, native, emit, disconnect} = setup();
  try {
    await store.refresh();
    emit(JSON.stringify({...JSON.parse(snapshot(3)), security: {sourceRevision: '1:0', ownerRevision: 'owner'}}));
    const after = {...JSON.parse(snapshot(4)), security: {sourceRevision: '2:0', ownerRevision: 'owner'}};
    const result = {rejection: {title: 'Blocked domain limit reached', message: 'Upgrade or remove entries', limitReached: true}};
    native.command.mockImplementationOnce(async () => {emit(JSON.stringify(after)); return JSON.stringify({snapshot: after, result});});
    await expect(store.command({type: 'domains.stage', domain: 'example.com', decision: 'blocked'})).resolves.toEqual(result);
  } finally {disconnect();}
});

test('a domain source change without a native owner identity retains the strict read fence', async () => {
  const {store, native, emit, disconnect} = setup();
  try {
    await store.refresh();
    emit(JSON.stringify({...JSON.parse(snapshot(3)), security: {sourceRevision: '1:0'}}));
    const after = {...JSON.parse(snapshot(4)), security: {sourceRevision: '2:0'}};
    native.command.mockImplementationOnce(async () => {
      emit(JSON.stringify(after));
      return JSON.stringify({snapshot: after, result: {id: 'active', standaloneReview: 'unknown-owner'}});
    });
    await expect(store.command({type: 'domains.stage', domain: 'example.com', decision: 'blocked'})).rejects.toThrow('Read access changed.');
    expect(native.command).toHaveBeenLastCalledWith(JSON.stringify({type: 'domains.cancel', token: 'unknown-owner'}));
  } finally {disconnect();}
});

test.each(['ownerRevision', 'readRevision', 'displayClearRevision'] as const)('a %s change discards a domain token even if authority returns before delivery', async key => {
  const {store, native, emit, disconnect} = setup();
  try {
    await store.refresh();
    const initial = {...JSON.parse(snapshot(3)), security: {sourceRevision: '1:0', ownerRevision: 'owner', readRevision: 1, displayClearRevision: 'clear'}};
    emit(JSON.stringify(initial));
    const pending = deferred<string>(); native.command.mockReturnValueOnce(pending.promise);
    const staged = store.command({type: 'domains.stage', domain: 'example.com', decision: 'allowed'});
    const rejected = expect(staged).rejects.toThrow('Read access changed.');
    await Promise.resolve();
    emit(JSON.stringify({...initial, revision: 4, security: {...initial.security, [key]: key === 'readRevision' ? 2 : 'changed'}}));
    emit(JSON.stringify({...initial, revision: 5}));
    pending.resolve(JSON.stringify({snapshot: {...initial, revision: 6}, result: {id: 'active', standaloneReview: 'stale-review'}}));
    await rejected;
    expect(native.command).toHaveBeenLastCalledWith(JSON.stringify({type: 'domains.cancel', token: 'stale-review'}));
    expect(store.getSnapshot().snapshot?.revision).toBe(5);
  } finally {disconnect();}
});

test('a refused review retirement is replayed exactly once after foreground resumes', async () => {
  const events=lifecycle();const {store,native,disconnect}=setup();
  try {
    await store.refresh();events.emit('background');
    native.command.mockRejectedValueOnce(Error('Activity is not foreground'));
    await store.retireDomainReview('owned-review');
    expect(native.command).toHaveBeenCalledTimes(1);
    events.emit('active');
    await Promise.resolve();await Promise.resolve();
    expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([
      {type:'domains.cancel',token:'owned-review'},{type:'domains.cancel',token:'owned-review'},
    ]);
    events.emit('background');events.emit('active');await Promise.resolve();
    expect(native.command).toHaveBeenCalledTimes(2);
  } finally {disconnect();events.restore();}
});

test('a domain review interrupted by inactivity discards its token without publishing the stale draft', async () => {
  const events = lifecycle(); const {store, native, emit, disconnect} = setup();
  try {
    await store.refresh();
    // A production-shaped Security-off frame: the confirmed all-off policy retains the displayed
    // frame across inactivity, so this exercises the domain-review discard without conflating it
    // with the concealment policy (an unknown/opted-in policy correctly clears the frame).
    emit(JSON.stringify({...JSON.parse(snapshot(3)), security: {sourceRevision: '1:0', ownerRevision: 'owner', unavailable: false, hasAuthenticationMethod: false, protectedSurfaces: []}}));
    const pending = deferred<string>(); native.command.mockReturnValueOnce(pending.promise);
    const staged = store.command({type: 'domains.stage', domain: 'example.com', decision: 'blocked'});
    const rejected = expect(staged).rejects.toThrow('Read access changed.');
    await Promise.resolve(); events.emit('inactive');
    pending.resolve(response(4, {id: 'active', standaloneReview: 'inactive-review'}));
    await rejected;
    expect(native.command).toHaveBeenLastCalledWith(JSON.stringify({type: 'domains.cancel', token: 'inactive-review'}));
    // Android retains its displayed confirmed snapshot across inactivity (the native cover owns
    // concealment); the stale response revision must never replace it.
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
  } finally {disconnect(); events.restore();}
});

test('a production background frame is retained for a confirmed Security-off owner', async () => {
  const {store, emit, disconnect} = setup();
  try {
    await store.refresh();
    const securityOff = {unavailable: false, hasAuthenticationMethod: false, protectedSurfaces: []};
    emit(JSON.stringify({...JSON.parse(snapshot(3)), generation: 'conn', security: securityOff}));
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(3);
    const epoch = store.getReadEpoch();
    // The native redaction shape: foreground:false, available:false, empty content.
    emit(JSON.stringify({...JSON.parse(snapshot(4)), generation: 'conn', foreground: false, available: false, security: securityOff}));
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
    expect(store.getReadEpoch()).toBeGreaterThan(epoch);
  } finally {disconnect();}
});

test('a production background frame is retained for an opted-in owner (native cover conceals)', async () => {
  const {store, emit, disconnect} = setup();
  try {
    await store.refresh();
    const optedIn = {unavailable: false, hasAuthenticationMethod: true, protectedSurfaces: ['appUnlock']};
    emit(JSON.stringify({...JSON.parse(snapshot(3)), generation: 'conn', security: optedIn}));
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(3);
    emit(JSON.stringify({...JSON.parse(snapshot(4)), generation: 'conn', foreground: false, available: false, security: optedIn}));
    // Same owner authority: the accepted frame is retained so an open workflow survives; the native
    // app-lock cover conceals protected content over the mounted tree.
    expect(store.getSnapshot().snapshot).toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(3);
  } finally {disconnect();}
});

test('a credential rotation in a background frame invalidates the accepted display', async () => {
  const {store, emit, disconnect} = setup();
  try {
    await store.refresh();
    const securityOff = {unavailable: false, hasAuthenticationMethod: false, protectedSurfaces: []};
    emit(JSON.stringify({...JSON.parse(snapshot(3)), generation: 'conn', security: securityOff}));
    const accepted = store.getSnapshot().snapshot;
    expect(accepted?.revision).toBe(3);
    emit(JSON.stringify({...JSON.parse(snapshot(4)), generation: 'rotated', foreground: false, available: false, security: securityOff}));
    expect(store.getSnapshot().snapshot).not.toBe(accepted);
    expect(store.getSnapshot().snapshot?.revision).toBe(4);
  } finally {disconnect();}
});

test('a domain token delivered after its store disconnects is discarded', async () => {
  const {store, native, disconnect} = setup();
  await store.refresh();
  const pending = deferred<string>(); native.command.mockReturnValueOnce(pending.promise);
  const staged = store.command({type: 'domains.stage', domain: 'example.com', decision: 'allowed'});
  const rejected = expect(staged).rejects.toThrow('The app screen closed while this action completed.');
  await Promise.resolve(); disconnect();
  pending.resolve(response(4, {id: 'active', standaloneReview: 'detached-review'}));
  await rejected;
  expect(native.command).toHaveBeenLastCalledWith(JSON.stringify({type: 'domains.cancel', token: 'detached-review'}));
  // Android retains its last confirmed snapshot on disconnect; the detached response must not
  // be accepted over it.
  expect(store.getSnapshot().snapshot?.revision).toBe(1);
});


async function failedColdStore() {
  let event!:(value:string)=>void;
  const native={getSnapshot:jest.fn(async()=>{throw new Error('private backend detail');}),
    command:jest.fn(async(raw:string)=>{const command=JSON.parse(raw);return JSON.stringify({result:command.type==='navigation.retryPresentation'?{generation:'7',serial:command.serial}:true});}),
    onSnapshot:(callback:(value:string)=>void)=>{event=callback;return {remove(){}};}};
  const store=new AppStore(native as unknown as Spec),disconnect=store.connect();
  await store.refresh();return {store,native,disconnect,emit:(value:string)=>event(value)};
}

test('public Retry paint never grants store authority, hydration, or a private snapshot',async()=>{
  const f=await failedColdStore();try {
    const before=f.store.getSnapshot(),hydration=f.store.getPresentationHydration(),epoch=f.store.getReadEpoch();
    const receipt=await f.store.prepareRuntimeRetry(1);expect(receipt).toEqual({generation:'7',serial:1});
    expect(await f.store.acknowledgeRuntimeRetry(receipt!)).toBe(true);
    expect(f.store.getSnapshot()).toBe(before);expect(f.store.getSnapshot().snapshot).toBeNull();
    expect(f.store.getPresentationAuthority()).toBe(false);expect(f.store.getReadEpoch()).toBe(epoch);
    expect(f.store.getPresentationHydration()).toBe(hydration);
    expect(await f.store.acknowledgeRuntimeRetry({...receipt!})).toBe(false);
    expect(f.native.command).toHaveBeenCalledTimes(2);
  }finally{f.disconnect();}
});

test.each(['disconnect','background','snapshot','refresh','replacement'] as const)('a %s retires a public Retry receipt before a delayed Ready can acknowledge it',async(boundary)=>{
  const life=lifecycle(),f=await failedColdStore();try {
    const receipt=(await f.store.prepareRuntimeRetry(1))!,pending=deferred<string>();
    f.native.command.mockReturnValueOnce(pending.promise);const ready=f.store.acknowledgeRuntimeRetry(receipt);
    if(boundary==='disconnect')f.disconnect();
    else if(boundary==='background')life.emit('inactive');
    else if(boundary==='snapshot')f.emit(snapshot(4));
    else if(boundary==='refresh')await f.store.refresh();
    else await f.store.prepareRuntimeRetry(2);
    pending.resolve(JSON.stringify({result:true}));expect(await ready).toBe(false);
    expect(await f.store.acknowledgeRuntimeRetry(receipt)).toBe(false);
  }finally{f.disconnect();life.restore();}
});


test('ordinary command dispatch cannot bypass the public Retry custody API with or without a private frame',async()=>{
  const f=await failedColdStore();try {
    await expect(f.store.command({type:'navigation.retryPresentation',serial:1})).rejects.toThrow('owned initial-error receipt');
    await expect(f.store.command({type:'navigation.retryPresentationReady',serial:1,generation:'7'})).rejects.toThrow('owned initial-error receipt');
    f.emit(snapshot(4));expect(await f.store.prepareRuntimeRetry(1)).toBeUndefined();
    await expect(f.store.command({type:'navigation.retryPresentation',serial:1})).rejects.toThrow('owned initial-error receipt');
    expect(await f.store.acknowledgeRuntimeRetry({generation:'7',serial:1})).toBe(false);
    f.disconnect();expect(await f.store.prepareRuntimeRetry(1)).toBeUndefined();
    expect(f.native.command).not.toHaveBeenCalled();
  }finally{f.disconnect();}
});


test('a changed cold error retires an old paint acknowledgement before React can replace that error frame',async()=>{
  const f=await failedColdStore();try {
    const receipt=(await f.store.prepareRuntimeRetry(1))!,pending=deferred<string>();
    f.native.command.mockReturnValueOnce(pending.promise);const ready=f.store.acknowledgeRuntimeRetry(receipt);
    f.emit('{not a valid snapshot');
    expect(f.store.getSnapshot().error).not.toBe('private backend detail');
    pending.resolve(JSON.stringify({result:true}));expect(await ready).toBe(false);
    expect(await f.store.acknowledgeRuntimeRetry(receipt)).toBe(false);
    const next=await f.store.prepareRuntimeRetry(2);expect(next).toEqual({generation:'7',serial:2});
    expect(await f.store.acknowledgeRuntimeRetry(next!)).toBe(true);
  }finally{f.disconnect();}
});


test.each(['saved',false] as const)('a current command retains its %s result when publication is unavailable while revoking stale reads until a genuine refresh',async(result)=>{
  const f=setup();try{
    await f.store.refresh();
    f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7',foreground:true,available:true,security:{unavailable:false,readRevision:1,ownerRevision:'owner'}}));
    const accepted=f.store.getSnapshot().snapshot,epoch=f.store.getReadEpoch(),reads=f.native.getSnapshot.mock.calls.length;
    f.native.command.mockResolvedValueOnce(JSON.stringify({result,projectionFailure:'unavailable'}));
    await expect(f.store.command({type:'library.form',form:'create'})).resolves.toBe(result);
    expect(f.store.getSnapshot().snapshot).toBe(accepted);
    expect(f.store.getPresentationAuthority()).toBe(false);expect(f.store.getPresentationHydration().required).toBe(true);
    expect(f.store.getReadEpoch()).toBeGreaterThan(epoch);expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
    expect(f.store.getSnapshot().error).toBe('Lava could not refresh its state. Try refreshing again.');
    f.native.getSnapshot.mockResolvedValueOnce(JSON.stringify({...accepted,revision:4}));
    await f.store.refresh();
    expect(f.store.getPresentationAuthority()).toBe(true);expect(f.store.getSnapshot().error).toBeNull();
    expect(f.native.command).toHaveBeenCalledTimes(1); // Read recovery never replays a mutation.
  }finally{f.disconnect();}
});

test('an original native rejection remains visible while its unavailable publication revokes stale read authority',async()=>{
  const f=setup();try{
    await f.store.refresh();
    const rejection=Object.assign(new Error('Choose an unused filter name.'),{projectionFailure:'unavailable'});
    f.native.command.mockRejectedValueOnce(rejection);
    await expect(f.store.command({type:'library.form',form:'create'})).rejects.toBe(rejection);
    expect(f.store.getPresentationAuthority()).toBe(false);expect(f.store.getPresentationHydration().required).toBe(true);
    expect(f.store.getSnapshot().error).toBe('Lava could not refresh its state. Try refreshing again.');
  }finally{f.disconnect();}
});

test.each(['reconnect','graph'] as const)('an unavailable original projection cannot revoke or deliver into a %s successor',async(boundary)=>{
  const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7'}));
    const old=deferred<string>();f.native.command.mockReturnValueOnce(old.promise);
    const pending=f.store.command({type:'library.form',form:'create'});const refused=expect(pending).rejects.toBeInstanceOf(AppCommandDetachedError);await Promise.resolve();
    let successorStop:(()=>void)|undefined;
    if(boundary==='reconnect'){f.disconnect();successorStop=f.store.connect();await f.store.refresh();}
    f.emit(JSON.stringify({...JSON.parse(snapshot(20)),generation:'8'}));
    const current=f.store.getSnapshot(),epoch=f.store.getReadEpoch();
    old.resolve(JSON.stringify({result:'old-created',projectionFailure:'unavailable'}));await refused;
    expect(f.store.getSnapshot()).toBe(current);expect(f.store.getReadEpoch()).toBe(epoch);expect(f.store.getPresentationAuthority()).toBe(true);
    expect(f.native.command).toHaveBeenCalledTimes(1);successorStop?.();
  }finally{f.disconnect();}
});


test('the Store refuses a contradictory failed-publication snapshot rather than accepting its private frame',async()=>{
  const f=setup();try{
    await f.store.refresh();const before=f.store.getSnapshot();
    f.native.command.mockResolvedValueOnce(JSON.stringify({result:'saved',projectionFailure:'unavailable',snapshot:JSON.parse(snapshot(50))}));
    await expect(f.store.command({type:'library.form',form:'create'})).rejects.toThrow('Invalid command publication receipt.');
    expect(f.store.getSnapshot()).toBe(before);
  }finally{f.disconnect();}
});


test('publication recovery runs separately after the truthful action result and never replays the mutation',async()=>{
  jest.useFakeTimers();const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source'}));
    const accepted=f.store.getSnapshot().snapshot,reads=f.native.getSnapshot.mock.calls.length,order:string[]=[],fresh=deferred<string>();
    f.native.command.mockImplementationOnce(async()=>{order.push('mutation');return JSON.stringify({result:'saved',projectionFailure:'unavailable'});});
    f.native.getSnapshot.mockImplementationOnce(()=>{order.push('fresh read');return fresh.promise;});
    const result=await f.store.command({type:'library.form',form:'create'});order.push(`result:${result}`);
    expect(order).toEqual(['mutation','result:saved']);expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
    jest.runOnlyPendingTimers();await Promise.resolve();
    expect(order).toEqual(['mutation','result:saved','fresh read']);expect(f.store.getPresentationAuthority()).toBe(false);
    fresh.resolve(JSON.stringify({...accepted,revision:4}));await Promise.resolve();await Promise.resolve();
    expect(f.store.getSnapshot().snapshot?.revision).toBe(4);expect(f.store.getPresentationAuthority()).toBe(true);
    expect(f.native.command).toHaveBeenCalledTimes(1);
  }finally{f.disconnect();jest.useRealTimers();}
});

test.each(['reconnect','graph','host'] as const)('scheduled refresh cannot start after its original %s custody changes',async(boundary)=>{
  jest.useFakeTimers();const f=setup();let successorStop:(()=>void)|undefined;try{
    await f.store.refresh();f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source'}));
    f.native.command.mockResolvedValueOnce(JSON.stringify({result:'saved',projectionFailure:'unavailable'}));
    await expect(f.store.command({type:'library.form',form:'create'})).resolves.toBe('saved');
    if(boundary==='reconnect'){f.disconnect();successorStop=f.store.connect();await f.store.refresh();}
    f.emit(JSON.stringify({...JSON.parse(snapshot(10)),generation:boundary==='graph'?'8':'7',nativeHostID:boundary==='host'?'successor':'source'}));
    const current=f.store.getSnapshot(),reads=f.native.getSnapshot.mock.calls.length;
    jest.runOnlyPendingTimers();await Promise.resolve();
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);expect(f.store.getSnapshot()).toBe(current);expect(f.native.command).toHaveBeenCalledTimes(1);
  }finally{successorStop?.();f.disconnect();jest.useRealTimers();}
});

test.each(['graph','host'] as const)('a scheduled current-holder refresh refuses a late %s successor packet',async(boundary)=>{
  jest.useFakeTimers();const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source'}));
    const fresh=deferred<string>(),reads=f.native.getSnapshot.mock.calls.length;f.native.getSnapshot.mockReturnValueOnce(fresh.promise);
    f.native.command.mockResolvedValueOnce(JSON.stringify({result:'saved',projectionFailure:'unavailable'}));
    await expect(f.store.command({type:'library.form',form:'create'})).resolves.toBe('saved');
    const accepted=f.store.getSnapshot().snapshot;
    jest.runOnlyPendingTimers();await Promise.resolve();
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+1);
    fresh.resolve(JSON.stringify({...accepted,revision:30,generation:boundary==='graph'?'8':'7',nativeHostID:boundary==='host'?'successor':'source'}));await Promise.resolve();await Promise.resolve();
    expect(f.store.getSnapshot().snapshot).toBe(accepted);expect(f.store.getPresentationAuthority()).toBe(false);
    expect(f.native.command).toHaveBeenCalledTimes(1);
  }finally{f.disconnect();jest.useRealTimers();}
});


test('a failed separate recovery leaves the action result intact and attempts only one read',async()=>{
  jest.useFakeTimers();const f=setup();try{
    await f.store.refresh();const reads=f.native.getSnapshot.mock.calls.length;
    f.native.getSnapshot.mockRejectedValueOnce(new Error('Fresh state is still unavailable'));
    f.native.command.mockResolvedValueOnce(JSON.stringify({result:'saved',projectionFailure:'unavailable'}));
    await expect(f.store.command({type:'library.form',form:'create'})).resolves.toBe('saved');
    jest.runOnlyPendingTimers();await Promise.resolve();await Promise.resolve();jest.runOnlyPendingTimers();await Promise.resolve();
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+1);expect(f.native.command).toHaveBeenCalledTimes(1);
    expect(f.store.getPresentationAuthority()).toBe(false);expect(f.store.getSnapshot().error).toBe('Fresh state is still unavailable');
  }finally{f.disconnect();jest.useRealTimers();}
});


test.each([
  {type:'domains.stage',domain:'example.invalid',decision:'blocked'},
  {type:'domainCheck.stage',visit:'884db449-818d-452f-9a4c-f745e969f087',proof:'native-proof',decision:'blocked'},
] as const)('$type revokes reads immediately but recovery waits for exact token retirement and the original rejection',async command=>{
  jest.useFakeTimers();const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify({...JSON.parse(snapshot(3)),generation:'7',security:{sourceRevision:'source',ownerRevision:'owner',readRevision:1}}));
    const cancel=deferred<string>(),reads=f.native.getSnapshot.mock.calls.length;let settled=false;
    f.native.command.mockImplementation(async raw=>JSON.parse(raw).type==='domains.cancel'?cancel.promise:JSON.stringify({result:{id:'owned',standaloneReview:'exact-token'},projectionFailure:'unavailable'}));
    const pending=f.store.command(command);void pending.catch(()=>{settled=true;});
    const refused=expect(pending).rejects.toThrow('Read access changed.');
    for(let turn=0;turn<8;turn++)await Promise.resolve();
    expect(f.native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([command,{type:'domains.cancel',token:'exact-token'}]);
    expect(f.store.getPresentationAuthority()).toBe(false);expect(settled).toBe(false);
    jest.runOnlyPendingTimers();await Promise.resolve();expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
    cancel.resolve(JSON.stringify({result:null}));await refused;expect(settled).toBe(true);
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
    jest.runOnlyPendingTimers();await Promise.resolve();expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+1);
    expect(f.native.command).toHaveBeenCalledTimes(2);
  }finally{f.disconnect();jest.useRealTimers();}
});


test.each(['saved','cancelled','rejected'] as const)('a pre-pause %s publication failure cannot revoke the fresh same-graph resumed query owner',async outcome=>{
  jest.useFakeTimers();const events=lifecycle(),f=setup();try{
    const original={...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source',foreground:true,available:true,
      security:{unavailable:false,ownerRevision:'owner',readRevision:1,protectedSurfaces:[]}};
    f.native.getSnapshot.mockResolvedValue(JSON.stringify(original));await f.store.refresh();
    const old=deferred<string>();f.native.command.mockReturnValueOnce(old.promise);
    const pending=f.store.command({type:'library.form',form:'create'});
    const detached=expect(pending).rejects.toBeInstanceOf(AppCommandDetachedError);await Promise.resolve();
    events.emit('inactive');expect(f.store.getPresentationAuthority()).toBe(false);
    const fresh={...original,revision:10,security:{...original.security,readRevision:2}};
    f.native.getSnapshot.mockResolvedValue(JSON.stringify(fresh));events.emit('active');
    for(let turn=0;turn<12;turn++)await Promise.resolve();
    expect(f.store.getSnapshot().snapshot?.security.readRevision).toBe(2);expect(f.store.getPresentationAuthority()).toBe(true);
    const current=f.store.getSnapshot(),epoch=f.store.getReadEpoch(),hydration=f.store.getPresentationHydration(),reads=f.native.getSnapshot.mock.calls.length;
    const cache=appReadCache(f.store),key=['fresh-domain-owner',epoch];
    await cache.read(key,async()=>['fresh-result']);expect(cache.peek(key)).toEqual(['fresh-result']);
    if(outcome==='rejected')old.reject(Object.assign(new Error('Original native rejection'),{projectionFailure:'unavailable'}));
    else old.resolve(JSON.stringify({result:outcome==='cancelled'?false:'saved',projectionFailure:'unavailable'}));
    await detached;jest.advanceTimersByTime(0);for(let turn=0;turn<4;turn++)await Promise.resolve();
    expect(f.store.getSnapshot()).toBe(current);expect(f.store.getReadEpoch()).toBe(epoch);
    expect(f.store.getPresentationHydration()).toBe(hydration);expect(f.store.getPresentationAuthority()).toBe(true);
    expect(cache.peek(key)).toEqual(['fresh-result']);expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
    expect(f.native.command).toHaveBeenCalledTimes(1);
  }finally{f.disconnect();events.restore();jest.useRealTimers();}
});

test('a same-graph host replacement refuses an old unavailable receipt without revoking the new holder',async()=>{
  jest.useFakeTimers();const f=setup();try{
    await f.store.refresh();const original={...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source'};f.emit(JSON.stringify(original));
    const old=deferred<string>();f.native.command.mockReturnValueOnce(old.promise);
    const pending=f.store.command({type:'library.form',form:'create'}),detached=expect(pending).rejects.toBeInstanceOf(AppCommandDetachedError);await Promise.resolve();
    const epoch=f.store.getReadEpoch();f.emit(JSON.stringify({...original,revision:10,nativeHostID:'successor'}));
    // Host is an independent witness; this replacement intentionally retains the same read scope.
    expect(f.store.getReadEpoch()).toBe(epoch);const current=f.store.getSnapshot(),reads=f.native.getSnapshot.mock.calls.length;
    old.resolve(JSON.stringify({result:'old-created',projectionFailure:'unavailable'}));await detached;
    jest.advanceTimersByTime(0);await Promise.resolve();
    expect(f.store.getSnapshot()).toBe(current);expect(f.store.getReadEpoch()).toBe(epoch);
    expect(f.store.getPresentationAuthority()).toBe(true);expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
  }finally{f.disconnect();jest.useRealTimers();}
});

test('an overtaken unavailable domain stage retires only its exact returned token without revoking resumed reads',async()=>{
  jest.useFakeTimers();const events=lifecycle(),f=setup();try{
    const original={...JSON.parse(snapshot(3)),generation:'7',nativeHostID:'source',foreground:true,available:true,
      security:{unavailable:false,ownerRevision:'owner',sourceRevision:'source',readRevision:1,protectedSurfaces:[]}};
    f.native.getSnapshot.mockResolvedValue(JSON.stringify(original));await f.store.refresh();
    const old=deferred<string>();f.native.command.mockImplementation(raw=>JSON.parse(raw).type==='domains.stage'?old.promise:Promise.resolve(JSON.stringify({result:null})));
    const pending=f.store.command({type:'domains.stage',domain:'example.invalid',decision:'blocked'}),detached=expect(pending).rejects.toBeInstanceOf(AppCommandDetachedError);await Promise.resolve();
    events.emit('inactive');const fresh={...original,revision:10,security:{...original.security,readRevision:2}};
    f.native.getSnapshot.mockResolvedValue(JSON.stringify(fresh));events.emit('active');for(let turn=0;turn<12;turn++)await Promise.resolve();
    const current=f.store.getSnapshot(),epoch=f.store.getReadEpoch(),reads=f.native.getSnapshot.mock.calls.length;
    old.resolve(JSON.stringify({result:{id:'old-filter',standaloneReview:'old-owned-token'},projectionFailure:'unavailable'}));await detached;
    expect(f.native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([
      {type:'domains.stage',domain:'example.invalid',decision:'blocked'},{type:'domains.cancel',token:'old-owned-token'}]);
    jest.advanceTimersByTime(0);await Promise.resolve();
    expect(f.store.getSnapshot()).toBe(current);expect(f.store.getReadEpoch()).toBe(epoch);
    expect(f.store.getPresentationAuthority()).toBe(true);expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads);
  }finally{f.disconnect();events.restore();jest.useRealTimers();}
});


// Selected iOS #876: refresh/publication work stays within one Android native read owner.
const ownedRefreshFrame=(revision:number)=>({...JSON.parse(snapshot(revision)),generation:'7',nativeHostID:'host',foreground:true,available:true,backgroundPrivacyCoverRequired:false,security:{unavailable:false,ownerRevision:'owner',readRevision:1,protectedSurfaces:[]}});

test('overlapping refreshes share one native read within the same connection, epoch and host',async()=>{
  const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify(ownedRefreshFrame(3)));
    const read=deferred<string>(),reads=f.native.getSnapshot.mock.calls.length;f.native.getSnapshot.mockReturnValueOnce(read.promise);
    const first=f.store.refresh(),second=f.store.refresh();
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+1);
    read.resolve(JSON.stringify(ownedRefreshFrame(4)));await Promise.all([first,second]);
    expect(f.store.getSnapshot().snapshot?.revision).toBe(4);
    await f.store.refresh();expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+2);
  }finally{f.disconnect();}
});

test.each(['graph','host','reconnect'] as const)('a %s successor starts its own read rather than sharing the predecessor refresh',async(boundary)=>{
  const f=setup();let successorStop:(()=>void)|undefined;try{
    await f.store.refresh();const original=ownedRefreshFrame(3);f.emit(JSON.stringify(original));
    const old=deferred<string>(),fresh=deferred<string>(),reads=f.native.getSnapshot.mock.calls.length;
    f.native.getSnapshot.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const first=f.store.refresh();
    const next={...original,revision:10,...(boundary==='graph'?{generation:'8'}:boundary==='host'?{nativeHostID:'successor'}:{})};
    if(boundary==='reconnect'){f.disconnect();successorStop=f.store.connect();}
    f.emit(JSON.stringify(next));const second=f.store.refresh();
    expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+2);
    const accepted=f.store.getSnapshot();old.resolve(JSON.stringify({...original,revision:12}));await first;
    expect(f.store.getSnapshot()).toBe(accepted);
    const overlapping=f.store.refresh();expect(f.native.getSnapshot).toHaveBeenCalledTimes(reads+2);
    fresh.resolve(JSON.stringify({...next,revision:11}));await Promise.all([second,overlapping]);
    expect(f.store.getSnapshot().snapshot?.revision).toBe(11);
  }finally{successorStop?.();f.disconnect();}
});

test('a failed pre-pause refresh cannot poison an accepted fresh same-connection resume frame',async()=>{
  const events=lifecycle(),f=setup();try{
    await f.store.refresh();const original=ownedRefreshFrame(3);f.emit(JSON.stringify(original));
    const old=deferred<string>(),fresh=deferred<string>();f.native.getSnapshot.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const first=f.store.refresh();events.emit('inactive');events.emit('active');
    for(let turn=0;turn<12;turn++)await Promise.resolve();
    fresh.resolve(JSON.stringify({...original,revision:8,security:{...original.security,readRevision:2}}));
    for(let turn=0;turn<12;turn++)await Promise.resolve();
    const accepted=f.store.getSnapshot(),epoch=f.store.getReadEpoch();
    expect(accepted.snapshot?.revision).toBe(8);expect(accepted.error).toBeNull();
    old.reject(new Error('Obsolete read failed'));await first;
    expect(f.store.getSnapshot()).toBe(accepted);expect(f.store.getSnapshot().error).toBeNull();
    expect(f.store.getReadEpoch()).toBe(epoch);expect(f.store.getPresentationAuthority()).toBe(true);
  }finally{f.disconnect();events.restore();}
});

test('a matching authorized navigation event and reply publish their exact native frame once',async()=>{
  const f=setup();try{
    await f.store.refresh();f.emit(JSON.stringify(ownedRefreshFrame(3)));
    let notifications=0;const stop=f.store.subscribe(()=>notifications++);const frame=ownedRefreshFrame(4);
    let emitted:ReturnType<AppStore['getSnapshot']>|undefined;
    f.native.command.mockImplementationOnce(async()=>{f.emit(JSON.stringify(frame));emitted=f.store.getSnapshot();return JSON.stringify({snapshot:frame,result:true});});
    await expect(f.store.command({type:'navigation.authorize',surface:'appSettings'})).resolves.toBe(true);
    expect(notifications).toBe(1);expect(f.store.getSnapshot()).toBe(emitted);
    expect(f.store.getSnapshot().snapshot?.revision).toBe(4);expect(f.store.getPresentationAuthority()).toBe(true);stop();
  }finally{f.disconnect();}
});

test.each(['blocked','background','host','graph','read','policy','error'] as const)('equal-revision navigation replies still admit the actual %s boundary rather than deduping it',async(boundary)=>{
  const f=setup();try{
    await f.store.refresh();const original=ownedRefreshFrame(3);f.emit(JSON.stringify(original));
    if(boundary==='error')f.emit('{invalid snapshot');
    const before=f.store.getSnapshot();
    const incoming={...original,...(boundary==='blocked'?{presentationBlocked:true}:boundary==='background'?{foreground:false,available:false,backgroundPrivacyCoverRequired:true}:boundary==='host'?{nativeHostID:'successor'}:boundary==='graph'?{generation:'8'}:boundary==='read'?{security:{...original.security,readRevision:2}}:boundary==='policy'?{security:{...original.security,protectedSurfaces:['appUnlock']},backgroundPrivacyCoverRequired:true}:{})};
    f.native.command.mockResolvedValueOnce(JSON.stringify({snapshot:incoming,result:true}));
    await f.store.command({type:'navigation.authorize',surface:'appSettings'});
    expect(f.store.getSnapshot()).not.toBe(before);expect(f.store.getSnapshot().error).toBeNull();
    if(boundary==='blocked'||boundary==='background')expect(f.store.getPresentationAuthority()).toBe(false);
    else expect(f.store.getSnapshot().snapshot).toEqual(incoming);
  }finally{f.disconnect();}
});


test.each(['refresh','mutation'] as const)('matching %s publications still pass normal acceptance instead of the navigation-only dedupe',async(source)=>{
  const f=setup();try{
    await f.store.refresh();const frame=ownedRefreshFrame(3);f.emit(JSON.stringify(frame));const before=f.store.getSnapshot();
    let notifications=0;const stop=f.store.subscribe(()=>notifications++);
    if(source==='refresh'){f.native.getSnapshot.mockResolvedValueOnce(JSON.stringify(frame));await f.store.refresh();}
    else{f.native.command.mockResolvedValueOnce(JSON.stringify({snapshot:frame,result:true}));await f.store.command({type:'settings.set',key:'haptics',value:true});}
    expect(notifications).toBe(1);expect(f.store.getSnapshot()).not.toBe(before);expect(f.store.getPresentationAuthority()).toBe(true);stop();
  }finally{f.disconnect();}
});
