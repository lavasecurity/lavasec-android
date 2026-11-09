// Focused Android port of the pinned iOS contextual Lava Plus coverage
// (lavasec-ios-internal @ c1fdb8e5, #851/#855): the intent registry, the
// authorization-gated entry, single-shot resumption after a native-verified
// entitlement, owner retirement, and the account status surface.
import React, {type PropsWithChildren} from 'react';
import {AppState} from 'react-native';
import {act,fireEvent,render,renderHook,screen} from '@testing-library/react-native';
import type {AppSnapshot} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';
import {AppearanceStore} from '../canonical/review/appearance-store';
import {ReviewContext,type ReviewState} from '../canonical/review/ReviewContext';
import {usePlusEntry} from '../canonical/review/plus-entry';
import {PlusIntents,plusPresentationScope,appUnlockAuthorized,capturePlusRetirement} from '../canonical/review/plus-intents';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {initialSession} from '../canonical/review/session';
import {LavaActionButton} from '../canonical/src';
import {localized} from '../canonical/app/presentation';

let mockFocused=true;
const mockNavigate=jest.fn();
const mockDispatch=jest.fn();
const mockGoBack=jest.fn();
const mockGetState=jest.fn();
let mockRouteParams:{reason?:unknown;intent?:string;embedded?:boolean}|undefined;
jest.mock('@react-navigation/native',()=>({
  useIsFocused:()=>mockFocused,
  useNavigation:()=>({navigate:mockNavigate,dispatch:mockDispatch,goBack:mockGoBack,getState:mockGetState}),
  useRoute:()=>({params:mockRouteParams}),
  usePreventRemove:jest.fn(),
  StackActions:{replace:(name:string,params:unknown)=>({type:'REPLACE',payload:{name,params}})},
}));

import {UpgradeScreen,AccountScreen} from '../canonical/review/SettingsScreens';
import {SettingsStatus,SettingsSurface} from '../canonical/review/settings-scaffold';
import {Section,Symbol} from '../canonical/review/primitives';
import {ListRow} from '../canonical/review/scaffold';

beforeEach(()=>{
  mockFocused=true;mockRouteParams=undefined;
  mockNavigate.mockReset();mockDispatch.mockReset();mockGoBack.mockReset();mockGetState.mockReset();
  jest.spyOn(AppState,'addEventListener').mockReturnValue({remove:jest.fn()} as never);
});
afterEach(()=>jest.restoreAllMocks());

function reviewState(live:AppSnapshot,app:AppStore):ReviewState {
  const appearance=new AppearanceStore({getSnapshot:async()=>({preference:'system',revision:0}),setPreference:async()=>({preference:'system',revision:1}),onSnapshot:()=>({remove(){}})});
  return {app,live,appearance,look:'original',setLook:()=>{},draft:initialPreviewDraft(),setDraft:()=>{},savedDraft:initialPreviewDraft(),setSavedDraft:()=>{},session:initialSession(),setSession:()=>{}};
}
function snapshot(plusEnabled:boolean):AppSnapshot {
  return {plus:{enabled:plusEnabled,busy:false,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},look:'original',
    // A production-shaped available owner with no configured method: appUnlockAuthorized is a
    // pass-through (native `!enabled`), so the resume gate only engages for a real opt-in.
    security:{unavailable:false,hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}} as unknown as AppSnapshot;
}
function Provider({live,app,children}:PropsWithChildren<{live:AppSnapshot;app:AppStore}>) {
  return <ReviewContext.Provider value={reviewState(live,app)}>{children}</ReviewContext.Provider>;
}

test('a credential-graph generation change retires the plus presentation boundary',()=>{
  // Same-provider account swap is invisible in status/detail; the native credential-graph
  // generation must still move the retirement key so a pending intent cannot replay across it.
  const base=snapshot(false);
  const rotated={...base,generation:'graph-2'} as unknown as AppSnapshot;
  const sameProviderSwap={...base,account:{signedIn:true,status:'Signed in',detail:''}} as unknown as AppSnapshot;
  expect(plusPresentationScope(base)).not.toBe(plusPresentationScope(rotated));
  expect(plusPresentationScope(base)).not.toBe(plusPresentationScope(sameProviderSwap));
});

test('appUnlockAuthorized gates a resume on the current navigation-turn grant',()=>{
  const base={plus:{enabled:true},look:'original'} as unknown as AppSnapshot;
  const secured=(protectedSurfaces:string[],authenticatedSurfaces:string[])=>({...base,security:{unavailable:false,hasAuthenticationMethod:true,protectedSurfaces,authenticatedSurfaces}} as unknown as AppSnapshot);
  // A missing security family is fail-closed: native isLocked(APP_UNLOCK) treats an absent owner as
  // locked, so a resume must not fire (and hammer the bridge) in that state.
  expect(appUnlockAuthorized(base)).toBe(false);
  // An unavailable/recovery owner is likewise locked, matching native.
  expect(appUnlockAuthorized({...base,security:{unavailable:true,hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}} as unknown as AppSnapshot)).toBe(false);
  expect(appUnlockAuthorized({...base,security:{unavailable:false,recoveryRequired:true,hasAuthenticationMethod:true,protectedSurfaces:['appUnlock'],authenticatedSurfaces:[]}} as unknown as AppSnapshot)).toBe(false);
  // No configured method: not `enabled`, so native isAuthorized(APP_UNLOCK) is true (no gate).
  expect(appUnlockAuthorized({...base,security:{unavailable:false,hasAuthenticationMethod:false,protectedSurfaces:['appUnlock'],authenticatedSurfaces:[]}} as unknown as AppSnapshot)).toBe(true);
  // Method configured but appUnlock not opted in: authorized.
  expect(appUnlockAuthorized(secured(['filterEditing'],[]))).toBe(true);
  // appUnlock opted in without a current-turn grant: the whole-app lock is engaged.
  expect(appUnlockAuthorized(secured(['appUnlock'],[]))).toBe(false);
  // appUnlock authenticated for the current turn: authorized.
  expect(appUnlockAuthorized(secured(['appUnlock'],['appUnlock']))).toBe(true);
});

test('pending intents are consumed once and clear retires every pending action',()=>{
  const intents=new PlusIntents();const resume=jest.fn(async()=>undefined);const id=intents.add(resume);
  expect(intents.take(id)).toBe(resume);expect(intents.take(id)).toBeUndefined();
  const retired=intents.add(resume);intents.clear();expect(intents.take(retired)).toBeUndefined();
});

test.each(['cancel','blur','background','success'])('contextual Plus registers return work only on an authorized push after %s',async event=>{
  const previousState=AppState.currentState;AppState.currentState='active';
  let resolve!:()=>void;let reject!:(error:Error)=>void;
  const command=jest.fn(()=>new Promise<void>((yes,no)=>{resolve=yes;reject=no;}));
  const listeners=new Set<(state:string)=>void>();
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{
    const callback=listener as (state:string)=>void;listeners.add(callback);return {remove:()=>listeners.delete(callback)} as never;
  });
  const add=jest.spyOn(PlusIntents.prototype,'add');const remove=jest.spyOn(PlusIntents.prototype,'remove');
  const app={command} as unknown as AppStore;
  const wrapper=({children}:PropsWithChildren)=><Provider live={snapshot(false)} app={app}>{children}</Provider>;
  try{
    const resume=jest.fn(async()=>undefined);
    const {result,rerender,unmount}=renderHook(()=>usePlusEntry(),{wrapper});
    act(()=>{result.current('customDNS',resume);result.current('customDNS',resume);});
    expect(command).toHaveBeenCalledTimes(1);expect(add).not.toHaveBeenCalled();
    if(event==='blur'){mockFocused=false;rerender(undefined);}
    if(event==='background')act(()=>listeners.forEach(listener=>listener('background')));
    await act(async()=>event==='cancel'?reject(new Error('Authentication cancelled.')):resolve());
    if(event==='success'){
      expect(add).toHaveBeenCalledTimes(1);expect(add).toHaveBeenCalledWith(expect.any(Function));
      const params=mockNavigate.mock.calls[0]![1];
      expect(params).toEqual({reason:'customDNS',intent:expect.any(String)});
      unmount();expect(remove).toHaveBeenCalledWith(params.intent);
    }else{
      expect(add).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();unmount();
    }
    expect(resume).not.toHaveBeenCalled();
  }finally{AppState.currentState=previousState;}
});

test('a paid filter destination captures its original lease once and keeps it after the same filter reopens',()=>{
  const destination={name:'Filter' as const,params:{id:'saved'}};
  const original={...snapshot(true),generation:'graph-1',filterSession:{id:'saved',lease:'original-visit'}} as unknown as AppSnapshot;
  const owned=capturePlusRetirement(destination,original);
  expect(owned).toEqual({name:'Filter',params:{id:'saved',retirementLease:'original-visit',retirementGeneration:'graph-1'}});
  const reopened={...original,generation:'graph-2',filterSession:{id:'saved',lease:'new-visit'}} as unknown as AppSnapshot;
  expect(capturePlusRetirement(owned,reopened)).toBe(owned);
  expect(capturePlusRetirement(destination,reopened,'graph-1')).toBe(destination);
  expect(capturePlusRetirement(destination,{...original,filterSession:{id:'other',lease:'other-visit'}} as unknown as AppSnapshot)).toBe(destination);
});

test('a verified entitlement resumes the intended action exactly once',async()=>{
  const app={command:jest.fn().mockResolvedValue(null)} as unknown as AppStore;
  const resume=jest.fn(async()=>({name:'CustomEntry' as const,params:{id:'native-token',kind:'dns' as const}}));
  function Origin(){const upgrade=usePlusEntry();return <LavaActionButton title="Add custom DNS" onPress={()=>upgrade('customDNS',resume)}/>;}
  const view=render(<Provider live={snapshot(false)} app={app}><Origin/></Provider>);
  fireEvent.press(screen.getByRole('button',{name:'Add custom DNS'}));
  await act(async()=>{});
  expect(mockNavigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'customDNS'}));
  const params=mockNavigate.mock.calls.at(-1)![1] as {intent:string};
  mockRouteParams={reason:'customDNS',intent:params.intent};
  await act(async()=>view.rerender(<Provider live={snapshot(false)} app={app}><Origin/><UpgradeScreen/></Provider>));
  expect(resume).not.toHaveBeenCalled();
  await act(async()=>view.rerender(<Provider live={snapshot(true)} app={app}><Origin/><UpgradeScreen/></Provider>));
  expect(resume).toHaveBeenCalledTimes(1);
  expect(mockDispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'CustomEntry',params:{id:'native-token',kind:'dns'}}});
  await act(async()=>view.rerender(<Provider live={snapshot(true)} app={app}><Origin/><UpgradeScreen/></Provider>));
  expect(resume).toHaveBeenCalledTimes(1);
});

test('a pending intent resumes when the entitlement flips true while backgrounded and the app then foregrounds',async()=>{
  const previousState=AppState.currentState;AppState.currentState='active';
  const listeners=new Set<(state:string)=>void>();
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{
    const callback=listener as (state:string)=>void;listeners.add(callback);return {remove:()=>listeners.delete(callback)} as never;
  });
  const live=snapshot(false);
  // A real store exposes `getSnapshot`; `mayResumePlusIntent` then requires an active foreground.
  const app={command:jest.fn().mockResolvedValue(null),getSnapshot:()=>({snapshot:live,error:null})} as unknown as AppStore;
  const resume=jest.fn(async()=>({name:'CustomEntry' as const,params:{id:'native-token',kind:'dns' as const}}));
  function Origin(){const upgrade=usePlusEntry();return <LavaActionButton title="Add custom DNS" onPress={()=>upgrade('customDNS',resume)}/>;}
  try{
    const view=render(<Provider live={snapshot(false)} app={app}><Origin/></Provider>);
    fireEvent.press(screen.getByRole('button',{name:'Add custom DNS'}));
    await act(async()=>{});
    const params=mockNavigate.mock.calls.at(-1)![1] as {intent:string};
    mockRouteParams={reason:'customDNS',intent:params.intent};
    // Entry began while the source was interactive. Native billing now covers
    // that retained source before its verified entitlement update arrives.
    AppState.currentState='background';
    await act(async()=>{listeners.forEach(listener=>listener('background'));});
    // The entitlement flips true while the billing activity keeps the app inactive.
    await act(async()=>view.rerender(<Provider live={snapshot(true)} app={app}><Origin/><UpgradeScreen/></Provider>));
    expect(resume).not.toHaveBeenCalled();
    // Returning to the foreground re-runs the effect and resumes exactly once.
    AppState.currentState='active';
    await act(async()=>{listeners.forEach(listener=>listener('active'));});
    expect(resume).toHaveBeenCalledTimes(1);
    expect(mockDispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'CustomEntry',params:{id:'native-token',kind:'dns'}}});
    // A later foreground event cannot replay it.
    await act(async()=>{listeners.forEach(listener=>listener('active'));});
    expect(resume).toHaveBeenCalledTimes(1);
  }finally{AppState.currentState=previousState;}
});

test('removing the source screen retires its pending action so it never resumes',async()=>{
  const app={command:jest.fn().mockResolvedValue(null)} as unknown as AppStore;
  const resume=jest.fn(async()=>undefined);
  function Origin(){const upgrade=usePlusEntry();return <LavaActionButton title="Add custom DNS" onPress={()=>upgrade('customDNS',resume)}/>;}
  const view=render(<Provider live={snapshot(false)} app={app}><Origin/></Provider>);
  fireEvent.press(screen.getByRole('button',{name:'Add custom DNS'}));
  await act(async()=>{});
  const params=mockNavigate.mock.calls.at(-1)![1] as {intent:string};
  mockRouteParams={reason:'customDNS',intent:params.intent};
  // The source unmounts before Plus can confirm; its return work is discarded.
  await act(async()=>view.rerender(<Provider live={snapshot(false)} app={app}><UpgradeScreen/></Provider>));
  await act(async()=>view.rerender(<Provider live={snapshot(true)} app={app}><UpgradeScreen/></Provider>));
  expect(resume).not.toHaveBeenCalled();
});

test('a signed-in device can restore an existing backup while local backup is off',async()=>{
  const live={account:{signedIn:true,busy:false},backup:{busy:false,automatic:false,
    enablement:{value:false,state:'off',canEnable:true,canRestore:true,canBackUp:false}}} as unknown as AppSnapshot;
  const app={command:jest.fn().mockResolvedValue(null)} as unknown as AppStore;
  const view=render(<Provider live={live} app={app}><AccountScreen/></Provider>);
  expect(screen.getByRole('button',{name:localized('Restore Backup')})).toBeEnabled();
  expect(screen.queryByText(localized('Back Up Now'))).toBeNull();
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:localized('Restore Backup')})));
  expect(app.command).toHaveBeenCalledWith({type:'native.flow',flow:'backupRestore'});
  view.rerender(<Provider live={{...live,backup:{...live.backup,enablement:{...live.backup.enablement!,canRestore:false}}}} app={app}><AccountScreen/></Provider>);
  expect(screen.queryByText(localized('Restore Backup'))).toBeNull();
  view.rerender(<Provider live={{...live,account:{...live.account,signedIn:false}}} app={app}><AccountScreen/></Provider>);
  expect(screen.queryByText(localized('Restore Backup'))).toBeNull();
});

test('account status is its own surface and follows sign-in without duplicating feedback',()=>{
  const message='Sign-in unavailable';
  const live={account:{signedIn:false,status:'Not signed in',detail:message,message,appleTitle:'Sign in with Apple',googleTitle:'Sign in with Google',appleConnected:false,googleConnected:false,busy:false},backup:{configured:false,busy:false,automatic:false,title:'',summary:''}} as unknown as AppSnapshot;
  const app={command:jest.fn().mockResolvedValue(null)} as unknown as AppStore;
  const view=render(<Provider live={live} app={app}><AccountScreen/></Provider>);
  const surfaces=()=>screen.UNSAFE_getAllByType(Section).find(node=>node.props.title==='Account')!.findAllByType(SettingsSurface);
  expect(surfaces()).toHaveLength(2);
  expect(surfaces()[0]!.findAllByType(SettingsStatus)).toHaveLength(0);
  expect(surfaces()[0]!.findAllByType(ListRow)).toHaveLength(1);
  expect(surfaces()[1]!.findAllByType(ListRow)).toHaveLength(2);
  expect(surfaces()[0]!.props.tone).toBe('neutral');
  expect(surfaces()[0]!.findByType(Symbol).props).toMatchObject({name:'person.crop.circle',tone:'primary'});
  expect(screen.queryByText(message)).toBeNull();
  expect(screen.getByText('Not signed in')).toBeOnTheScreen();

  const title='Signed in with Apple';
  view.rerender(<Provider live={{...live,account:{...live.account,signedIn:true,status:title,detail:'',message:title}} as AppSnapshot} app={app}><AccountScreen/></Provider>);
  expect(surfaces()[0]!.props.tone).toBe('green');
  expect(surfaces()[0]!.findByType(Symbol).props).toMatchObject({name:'checkmark.circle.fill',tone:'white'});
  expect(screen.getAllByText('Signed in')).toHaveLength(1);
  expect(screen.queryByText(message)).toBeNull();

  view.rerender(<Provider live={live} app={app}><AccountScreen/></Provider>);
  expect(surfaces()[0]!.props.tone).toBe('neutral');
  expect(surfaces()[0]!.findByType(Symbol).props).toMatchObject({name:'person.crop.circle',tone:'primary'});
  expect(screen.queryByText(message)).toBeNull();
});


// Final iOS 2.0.2 (#890): real owner changes retire purchase return work;
// same-owner concealment keeps it registered without admitting covered actions.
function finalPlusFixture() {
  const Store=require('../canonical/app/store').AppStore as typeof import('../canonical/app/store').AppStore;
  const project=require('../canonical/android/runtime').projectAndroidSnapshot as typeof import('../canonical/android/runtime').projectAndroidSnapshot;
  const fixture=require('./productFixture').productFixture as typeof import('./productFixture').productFixture;
  const listeners=new Set<(state:import('react-native').AppStateStatus)=>void>();
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{
    listeners.add(listener);return {remove:()=>listeners.delete(listener)};
  });
  const frames:Array<(timestamp:number)=>void>=[];
  jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.push(callback);return frames.length;});
  const full=(revision:number,enabled=false,unlocked=true):AppSnapshot=>{
    const base=project({...fixture,nativeHostID:'purchase-host',revision});
    return {...base,backgroundPrivacyCoverRequired:true,
      plus:{...base.plus,enabled},
      session:{...base.session,passcode:true,protectedActions:{...base.session.protectedActions,'App Unlock':true}},
      security:{...base.security,unavailable:false,hasAuthenticationMethod:true,ownerRevision:'purchase-owner',
        readRevision:revision,protectedSurfaces:['appUnlock'],authenticatedSurfaces:unlocked?['appUnlock']:[]}};
  };
  let current=full(1),emit!:(value:string)=>void;
  const native={onSnapshot:(callback:(value:string)=>void)=>{emit=callback;return {remove:jest.fn()};},
    getSnapshot:jest.fn(async()=>JSON.stringify(current)),
    command:jest.fn(async(raw:string)=>JSON.stringify({snapshot:current,result:JSON.parse(raw).type==='dns.customDraft'?'paid-entry':null}))};
  const app=new Store(native as unknown as import('../canonical/specs/NativeLavaApp').Spec);
  const disconnect=app.connect();
  const lifecycle=(state:import('react-native').AppStateStatus)=>{
    AppState.currentState=state;listeners.forEach(listener=>listener(state));
  };
  const publish=(value:AppSnapshot)=>{current=value;emit(JSON.stringify(value));};
  const hydrate=async()=>{
    // Consume the real shared Screen's layout ticket before its frame ACK.
    screen.UNSAFE_queryAllByType(require('react-native').ScrollView).forEach(node=>{
      fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:320,height:640}}});
    });
    await act(async()=>{app.completePresentationLayout(app.getPresentationHydration().epoch);});
    await act(async()=>{frames.splice(0).forEach(callback=>callback(0));});
  };
  const resume=jest.fn(async()=>{
    const id=await app.command<string>({type:'dns.customDraft'});
    return {name:'CustomEntry' as const,params:{id,kind:'dns' as const}};
  });
  return {app,native,full,disconnect,lifecycle,publish,hydrate,resume,setRead:(value:AppSnapshot)=>{current=value;}};
}

test.each(['inactive','background'] as const)('final 2.0.2 Plus return survives same-owner %s concealment and waits for current layout and unlock',async phase=>{
  const previous=AppState.currentState;AppState.currentState='active';
  const f=finalPlusFixture();let missing=false,upgradeVisible=false;
  const wrapper=({children}:PropsWithChildren)=>{
    const state=React.useSyncExternalStore(f.app.subscribe,f.app.getSnapshot);
    React.useSyncExternalStore(f.app.subscribe,f.app.getPresentationAuthority);
    const value=reviewState(state.snapshot??f.full(1),f.app);
    return <ReviewContext.Provider value={{...value,live:missing?undefined:state.snapshot??undefined}}>{children}{upgradeVisible&&<UpgradeScreen/>}</ReviewContext.Provider>;
  };
  const useIntents=require('../canonical/review/plus-intents').usePlusIntents as typeof import('../canonical/review/plus-intents').usePlusIntents;
  const hook=renderHook(()=>({upgrade:usePlusEntry(),intents:useIntents()}),{wrapper});
  try{
    await act(async()=>{});await f.hydrate();
    await act(async()=>hook.result.current.upgrade('customDNS',f.resume));
    const id=mockNavigate.mock.calls.at(-1)![1].intent as string;
    const pending=hook.result.current.intents.peek(id);expect(pending).toEqual(expect.any(Function));
    mockRouteParams={reason:'customDNS',intent:id};upgradeVisible=true;hook.rerender(undefined);
    await act(async()=>{});
    const epoch=f.app.getReadEpoch();
    // The paid native packet arrives while the same provider is concealed. The
    // Store may retain its earlier painted snapshot; this is not read authority.
    missing=true;act(()=>f.lifecycle(phase));
    const paidWhileCovered=f.full(2,true,true);
    await act(async()=>f.publish(paidWhileCovered));
    expect(f.app.getPresentationAuthority()).toBe(false);
    expect(f.app.getReadEpoch()).toBeGreaterThan(epoch);
    expect(hook.result.current.intents.peek(id)).toBe(pending);
    expect(f.resume).not.toHaveBeenCalled();
    expect(f.native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).not.toContain('dns.customDraft');
    // Make the entitlement and App Unlock grant genuinely live before testing
    // layout: the production Upgrade effect now lacks only settled hydration.
    missing=false;f.setRead(f.full(3,true,true));
    await act(async()=>f.lifecycle('active'));
    expect(f.app.getSnapshot().snapshot?.plus.enabled).toBe(true);
    expect(appUnlockAuthorized(f.app.getSnapshot().snapshot??undefined)).toBe(true);
    expect(f.app.getPresentationAuthority()).toBe(true);
    expect(f.app.getPresentationHydration().required).toBe(true);
    expect(hook.result.current.intents.peek(id)).toBe(pending);
    expect(f.resume).not.toHaveBeenCalled();
    expect(mockDispatch).not.toHaveBeenCalled();
    // Conversely, settled real layout with a live paid owner does not supply a
    // missing unlock grant. It must stay pending until the native grant returns.
    await act(async()=>f.publish(f.full(4,true,false)));
    await f.hydrate();
    expect(f.app.getSnapshot().snapshot?.plus.enabled).toBe(true);
    expect(f.app.getPresentationHydration().required).toBe(false);
    expect(appUnlockAuthorized(f.app.getSnapshot().snapshot??undefined)).toBe(false);
    expect(f.resume).not.toHaveBeenCalled();
    expect(f.native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).not.toContain('dns.customDraft');
    await act(async()=>f.publish(f.full(5,true,true)));
    expect(f.resume).toHaveBeenCalledTimes(1);
    expect(f.native.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='dns.customDraft')).toHaveLength(1);
    expect(mockDispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'CustomEntry',params:{id:'paid-entry',kind:'dns'}}});
    expect(hook.result.current.intents.peek(id)).toBeUndefined();
    await act(async()=>f.lifecycle('active'));
    expect(f.resume).toHaveBeenCalledTimes(1);
  }finally{hook.unmount();f.disconnect();AppState.currentState=previous;}
});

test('final 2.0.2 retains the shipping Android snapshot across a native purchase background and resumes once',async()=>{
  const previous=AppState.currentState;AppState.currentState='active';
  const f=finalPlusFixture();let upgradeVisible=false;
  const wrapper=({children}:PropsWithChildren)=>{
    const state=React.useSyncExternalStore(f.app.subscribe,f.app.getSnapshot);
    return <ReviewContext.Provider value={{...reviewState(state.snapshot??f.full(1),f.app),live:state.snapshot??undefined}}>{children}{upgradeVisible&&<UpgradeScreen/>}</ReviewContext.Provider>;
  };
  const useIntents=require('../canonical/review/plus-intents').usePlusIntents as typeof import('../canonical/review/plus-intents').usePlusIntents;
  const hook=renderHook(()=>({upgrade:usePlusEntry(),intents:useIntents()}),{wrapper});
  try{
    await act(async()=>{});await f.hydrate();
    await act(async()=>hook.result.current.upgrade('customDNS',f.resume));
    const id=mockNavigate.mock.calls.at(-1)![1].intent as string;
    mockRouteParams={reason:'customDNS',intent:id};upgradeVisible=true;hook.rerender(undefined);await act(async()=>{});
    const accepted=f.app.getSnapshot().snapshot;
    act(()=>f.lifecycle('background'));
    await act(async()=>f.publish(f.full(2,true,true)));
    expect(f.app.getSnapshot().snapshot).toBe(accepted);expect(f.app.getPresentationAuthority()).toBe(false);
    f.setRead(f.full(3,true,true));await act(async()=>f.lifecycle('active'));
    expect(f.app.getSnapshot().snapshot?.plus.enabled).toBe(true);
    expect(appUnlockAuthorized(f.app.getSnapshot().snapshot??undefined)).toBe(true);
    expect(f.app.getPresentationHydration().required).toBe(true);
    expect(f.resume).not.toHaveBeenCalled();await f.hydrate();
    expect(f.resume).toHaveBeenCalledTimes(1);expect(hook.result.current.intents.peek(id)).toBeUndefined();
    expect(f.native.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='dns.customDraft')).toHaveLength(1);
    expect(mockDispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'CustomEntry',params:{id:'paid-entry',kind:'dns'}}});
  }finally{hook.unmount();f.disconnect();AppState.currentState=previous;}
});

test.each(['owner','session policy','app holder'] as const)('final 2.0.2 Plus return cannot survive an actual %s replacement',async boundary=>{
  const previous=AppState.currentState;AppState.currentState='active';
  const f=finalPlusFixture(),replacement=boundary==='app holder'?finalPlusFixture():undefined;
  let app=f.app,upgradeVisible=false;
  const wrapper=({children}:PropsWithChildren)=>{
    const state=React.useSyncExternalStore(app.subscribe,app.getSnapshot);
    return <ReviewContext.Provider value={{...reviewState(state.snapshot??f.full(1),app),live:state.snapshot??undefined}}>{children}{upgradeVisible&&<UpgradeScreen/>}</ReviewContext.Provider>;
  };
  const useIntents=require('../canonical/review/plus-intents').usePlusIntents as typeof import('../canonical/review/plus-intents').usePlusIntents;
  const hook=renderHook(()=>({upgrade:usePlusEntry(),intents:useIntents()}),{wrapper});
  const retiredResume=jest.fn(async()=>f.resume());
  try{
    await act(async()=>{});await f.hydrate();if(replacement)await replacement.hydrate();
    await act(async()=>hook.result.current.upgrade('customDNS',retiredResume));
    const id=mockNavigate.mock.calls.at(-1)![1].intent as string;
    const original=hook.result.current.intents;
    expect(original.peek(id)).toEqual(expect.any(Function));
    mockRouteParams={reason:'customDNS',intent:id};upgradeVisible=true;hook.rerender(undefined);await act(async()=>{});
    expect(f.resume).not.toHaveBeenCalled();
    if(boundary==='app holder'){
      app=replacement!.app;
      await act(async()=>{replacement!.publish(replacement!.full(3,true,true));hook.rerender(undefined);});
    }else{
      const next=f.full(3,true,true);
      await act(async()=>f.publish(boundary==='owner'?{...next,security:{...next.security,ownerRevision:'replacement-owner'}}:
        {...next,session:{...next.session,protectedActions:{...next.session.protectedActions,'Update App Settings':true}}}));
    }
    const current=replacement??f;
    await current.hydrate();
    // All ordinary resume prerequisites are now true and Upgrade is mounted:
    // only retirement of the captured intent may refuse the old callback.
    expect(app.getSnapshot().snapshot?.plus.enabled).toBe(true);
    expect(appUnlockAuthorized(app.getSnapshot().snapshot??undefined)).toBe(true);
    expect(app.getPresentationAuthority()).toBe(true);
    expect(app.getPresentationHydration().required).toBe(false);
    expect(require('../canonical/review/plus-intents').mayResumePlusIntent(app)).toBe(true);
    expect(hook.result.current.intents).not.toBe(original);
    expect(original.peek(id)).toBeUndefined();expect(hook.result.current.intents.peek(id)).toBeUndefined();
    expect(retiredResume).not.toHaveBeenCalled();expect(f.resume).not.toHaveBeenCalled();
    expect(f.native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).not.toContain('dns.customDraft');
    expect(current.native.command.mock.calls.map(([raw])=>JSON.parse(raw).type)).not.toContain('dns.customDraft');
    expect(mockDispatch).not.toHaveBeenCalled();
    // Prove this replacement owner can actually resume through the SAME effect.
    // Registry freshness alone is insufficient: a newly authorized contextual
    // intent must execute one native draft command and deliver its destination.
    const freshResume=jest.fn(async()=>current.resume());
    await act(async()=>hook.result.current.upgrade('customDNS',freshResume));
    const freshId=mockNavigate.mock.calls.at(-1)![1].intent as string;
    expect(freshId).not.toBe(id);
    mockRouteParams={reason:'customDNS',intent:freshId};hook.rerender(undefined);await act(async()=>{});
    expect(freshResume).toHaveBeenCalledTimes(1);expect(retiredResume).not.toHaveBeenCalled();
    expect(current.resume).toHaveBeenCalledTimes(1);
    expect(current.native.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='dns.customDraft')).toHaveLength(1);
    if(replacement)expect(f.resume).not.toHaveBeenCalled();
    expect(mockDispatch).toHaveBeenCalledTimes(1);
    expect(mockDispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'CustomEntry',params:{id:'paid-entry',kind:'dns'}}});
    expect(hook.result.current.intents.peek(freshId)).toBeUndefined();
    hook.rerender(undefined);await act(async()=>{});
    expect(freshResume).toHaveBeenCalledTimes(1);expect(retiredResume).not.toHaveBeenCalled();
  }finally{hook.unmount();f.disconnect();replacement?.disconnect();AppState.currentState=previous;}
});
