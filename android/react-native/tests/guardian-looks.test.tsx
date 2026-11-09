import React from 'react';
import {AppState,type AppStateStatus} from 'react-native';
import {act,fireEvent,render,screen} from '@testing-library/react-native';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: mockNavigate, goBack: jest.fn(), getState: () => undefined}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));

let mockReviewState: {app?: unknown; live?: unknown; session?: unknown; setSession?: jest.Mock; appearance?: unknown; look?: string; setLook?: jest.Mock};
jest.mock('../canonical/review/ReviewContext', () => ({...jest.requireActual('../canonical/review/ReviewContext'),useReview: () => mockReviewState}));

import {SettingsGuardRow} from '../canonical/review/settings-scaffold';
import {GuardianScreen,CustomizationScreen} from '../canonical/review/SettingsScreens';
import {projectGuards} from '../canonical/android/guardCatalog';
import {PlusIntentProvider,PlusIntents} from '../canonical/review/plus-intents';
import {AppCommandDetachedError} from '../canonical/app/store';
import {Alert,configurePresentation,localized} from '../canonical/app/presentation';
import {initialSession} from '../canonical/review/session';
import {AppearanceStore} from '../canonical/review/appearance-store';

afterEach(() => { mockNavigate.mockReset(); });


test('a selectable Guard row fires its press while a locked look stays tappable into contextual Plus',()=>{
  const select=jest.fn();
  const available=render(<SettingsGuardRow testID="guard.original" look="original" title="Original" selected onPress={select}/>);
  expect(available.getByTestId('guard.original').props.accessibilityState.disabled).toBe(false);
  fireEvent.press(available.getByTestId('guard.original'));
  expect(select).toHaveBeenCalledTimes(1);

  // A locked paid look is not a dead row: it stays enabled and opens the contextual Plus flow.
  const lockedPress=jest.fn();
  const locked=render(<SettingsGuardRow testID="guard.obsidian" look="obsidian" title="Obsidian" locked onPress={lockedPress}/>);
  expect(locked.getByTestId('guard.obsidian').props.accessibilityState.disabled).toBe(false);
  fireEvent.press(locked.getByTestId('guard.obsidian'));
  expect(lockedPress).toHaveBeenCalledTimes(1);
});

test('verified Plus reveals all looks while free availability follows usage and current-look courtesy',()=>{
  const guards=projectGuards({hasPlus:true});
  expect(guards.map(guard=>guard.id)).toEqual(['original','emberObsidian','purpleObsidian','obsidian','strawberryObsidian','emerald','kiwiCreme','aquamarine']);
  expect(guards.every(guard=>guard.selectable)).toBe(true);
  expect(guards.every(guard=>guard.subtitle==='')).toBe(true);
});

test('earned availability and first locked progress follow the frozen usage thresholds',()=>{
  const guards=projectGuards({unlockedIDs:['emberObsidian'],currentLook:'emerald',qualifiedUsageDays:4,progressEnabled:true});
  expect(guards.find(guard=>guard.id==='emberObsidian')).toMatchObject({selectable:true,title:'Fire Opal'});
  expect(guards.find(guard=>guard.id==='emerald')).toMatchObject({selectable:true,title:'Emerald'});
  expect(guards.find(guard=>guard.id==='purpleObsidian')).toMatchObject({selectable:false,title:'Use Lava for 7 days',subtitle:'4 days so far'});
  expect(guards.find(guard=>guard.id==='obsidian')).toMatchObject({selectable:false,title:'Use Lava for 14 days',subtitle:''});
  expect(projectGuards()[1]).toMatchObject({selectable:false,subtitle:'Progress is off in Privacy & data'});
});

test('the production GuardianScreen routes a locked paid Guard row to the contextual guards Upgrade',()=>{
  const guards=projectGuards();
  mockReviewState={app:undefined,live:{guards,plus:{enabled:false}},session:{matchIcon:false},setSession:jest.fn(),look:'original',setLook:jest.fn()};
  render(<PlusIntentProvider><GuardianScreen/></PlusIntentProvider>);
  // A locked paid look must not be a dead row: pressing it opens Upgrade with the guards reason,
  // which is exactly the routing `upgrade('guards')` performs in SettingsScreens.tsx. A no-op
  // replacement for that call leaves mockNavigate uncalled and fails this assertion.
  expect(guards.find(guard=>guard.id==='emberObsidian')!.selectable).toBe(false);
  // A locked paid look registers a resume (so the selection applies after entitlement) and routes
  // to Upgrade with the guards reason. A bare no-op leaves mockNavigate/add uncalled and fails.
  const add=jest.spyOn(PlusIntents.prototype,'add');
  try {
    fireEvent.press(screen.getByTestId('guardian.option.emberObsidian'));
    expect(mockNavigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'guards'}));
    expect(add).toHaveBeenCalledTimes(1);
  } finally { add.mockRestore(); }
});


test.each(['detached','unmounted','background resumed','current failure'] as const)('Guardian selection reports only a current owner failure: %s',async outcome=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  const previousState=AppState.currentState;AppState.currentState='active';
  const listeners=new Set<(state:AppStateStatus)=>void>();
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockImplementation((_type,listener)=>{listeners.add(listener);return {remove:()=>{listeners.delete(listener);}};});
  let reject:(error:Error)=>void=()=>{throw new Error('Selection was not started');};
  const pending=new Promise<void>((_resolve,fail)=>{reject=fail;});
  const command=jest.fn(()=>pending);
  mockReviewState={app:{command},live:{guards:projectGuards(),plus:{enabled:false}},session:{matchIcon:false},setSession:jest.fn(),look:'original',setLook:jest.fn()};
  const tree=render(<PlusIntentProvider><GuardianScreen/></PlusIntentProvider>);
  try{
    fireEvent.press(screen.getByTestId('guardian.option.original'));
    expect(command).toHaveBeenCalledWith({type:'settings.set',key:'look',value:'original'});
    if(outcome==='unmounted')tree.unmount();
    if(outcome==='background resumed')act(()=>{for(const state of ['background','active'] as const){AppState.currentState=state;listeners.forEach(listener=>listener(state));}});
    await act(async()=>{reject(outcome==='detached'?new AppCommandDetachedError('afterStart'):new Error('Storage write failed.'));await Promise.resolve();});
    if(outcome==='current failure')expect(alert).toHaveBeenCalledWith('Lava','Storage write failed.');
    else expect(alert).not.toHaveBeenCalled();
  }finally{tree.unmount();alert.mockRestore();lifecycle.mockRestore();AppState.currentState=previousState;}
});


test('Customization localizes its isolated Guard preview fallback before the verbatim row',()=>{
  configurePresentation({locale:'ja',textScales:null});
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockImplementation(()=>({remove(){}}));
  const appearance=new AppearanceStore({getSnapshot:async()=>({preference:'system',revision:0}),setPreference:async()=>({preference:'system',revision:1}),onSnapshot:()=>({remove(){}})});
  mockReviewState={appearance,session:initialSession(),setSession:jest.fn(),look:'original',setLook:jest.fn()};
  const view=render(<PlusIntentProvider><CustomizationScreen/></PlusIntentProvider>);
  try {
    expect(localized('Original')).not.toBe('Original');
    expect(view.getByTestId('Choose Lava Guard').props.accessibilityLabel).toBe(localized('Original'));
    expect(view.getByText(localized('Original'))).toBeTruthy();
    expect(view.queryByText('Original')===null).toBe(true);
  }finally{view.unmount();lifecycle.mockRestore();configurePresentation();}
});

test('Guardian localizes isolated fallback copy before its verbatim spotlight',()=>{
  configurePresentation({locale:'ja',textScales:null});
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockImplementation(()=>({remove(){}}));
  mockReviewState={session:initialSession(),setSession:jest.fn(),look:'original',setLook:jest.fn()};
  const view=render(<PlusIntentProvider><GuardianScreen/></PlusIntentProvider>);
  try {
    expect(view.queryByText('Original')===null).toBe(true);
    expect(view.getAllByText(localized('Original')).length).toBeGreaterThan(0);
    expect(view.getByText(localized('A Lava a day keeps bad domains away.'))).toBeTruthy();
    expect(view.getAllByText(localized('Keep Lava protecting you to unlock more Guards, or upgrade to unlock them all.')).length).toBe(2);
    expect(view.queryByText('A Lava a day keeps bad domains away.')===null).toBe(true);
  }finally{view.unmount();lifecycle.mockRestore();configurePresentation();}
});

test('Customization and Guardian preserve an actual native-projected Guard identity and description verbatim',()=>{
  configurePresentation({locale:'ja',textScales:null});
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockImplementation(()=>({remove(){}}));
  const appearance=new AppearanceStore({getSnapshot:async()=>({preference:'system',revision:0}),setPreference:async()=>({preference:'system',revision:1}),onSnapshot:()=>({remove(){}})});
  const guard={id:'original',title:'Cancel',description:'A native-projected description',tip:'A native-projected tip',subtitle:'',selectable:true};
  mockReviewState={appearance,live:{guards:[guard],plus:{enabled:true},liveActivityPause:{available:false}},session:initialSession(),setSession:jest.fn(),look:'original',setLook:jest.fn()};
  const preview=render(<PlusIntentProvider><CustomizationScreen/></PlusIntentProvider>);
  const spotlight=render(<PlusIntentProvider><GuardianScreen/></PlusIntentProvider>);
  try {
    expect(localized(guard.title)).not.toBe(guard.title);
    expect(preview.getByTestId('Choose Lava Guard').props.accessibilityLabel).toBe(guard.title);
    expect(spotlight.getAllByText(guard.title)).toHaveLength(2);
    expect(spotlight.getByText(guard.description)).toBeTruthy();
    expect(preview.queryByText(localized(guard.title))===null).toBe(true);
    expect(spotlight.queryByText(localized(guard.title))===null).toBe(true);
  }finally{preview.unmount();spotlight.unmount();lifecycle.mockRestore();configurePresentation();}
});


import {GuardianDrawing} from '../canonical/src/GuardianDrawing';
import * as guardianMotion from '../canonical/src/guardian-motion';

function firstCommittedGuardianPaint(props:React.ComponentProps<typeof GuardianDrawing>){
  const previousState=AppState.currentState;AppState.currentState='active';
  const eye=jest.spyOn(guardianMotion,'guardianEye');
  const request=jest.spyOn(globalThis,'requestAnimationFrame').mockReturnValue(1);
  const cancel=jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(()=>{});
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockReturnValue({remove:jest.fn()});
  const committed:guardianMotion.GuardianFrame[]=[];
  let tree:ReturnType<typeof render>|undefined;
  try{
    tree=render(<React.Profiler id="guardian-first-paint" onRender={()=>{
      const painted=eye.mock.calls.at(-2)?.[0];
      if(painted)committed.push({...painted});
    }}><GuardianDrawing {...props}/></React.Profiler>);
    return {firstRender:eye.mock.calls[0]?.[0],firstCommit:committed[0]};
  }finally{tree?.unmount();eye.mockRestore();request.mockRestore();cancel.mockRestore();lifecycle.mockRestore();AppState.currentState=previousState;}
}

test('a waking Guardian first commits its sleeping animation origin before passive animation effects',()=>{
  const first=firstCommittedGuardianPaint({size:128,mood:'waking',look:'original'});
  expect(first.firstCommit).toEqual(guardianMotion.stableGuardianFrame('sleeping'));
  expect(first.firstRender).toEqual(guardianMotion.stableGuardianFrame('sleeping'));
});

test.each(['sleeping','awake','paused','retrying','concerned','grateful'] as const)('a newly mounted %s Guardian first commits its own stable frame',mood=>{
  const first=firstCommittedGuardianPaint({size:128,mood,look:'emerald',colorScheme:'dark'});
  expect(first.firstCommit).toEqual(guardianMotion.stableGuardianFrame(mood));
  expect(first.firstRender).toEqual(guardianMotion.stableGuardianFrame(mood));
});

test.each(['waking','awake'])('an explicit frame remains the first committed paint for a %s Guardian',mood=>{
  const fixed={...guardianMotion.stableGuardianFrame('concerned'),leftEyeOpenAmount:.123,shieldWakeAmount:.42};
  const first=firstCommittedGuardianPaint({size:128,mood,look:'kiwiCreme',frame:fixed,colorScheme:'light',keepsColorWhenSleeping:true});
  expect(first.firstCommit).toEqual(fixed);
  expect(first.firstRender).toEqual(fixed);
});

// Real Guardian/usePlusEntry/Upgrade continuation, through the Android host adapter
// and AppStore's native command queue. Only the native command port is controlled.
const guardSelectionPort={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
require('react-native').NativeModules.NativeLavaApp=guardSelectionPort;
const {androidRuntimeForHost:guardRuntime}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
const {AppStore:GuardStore}=require('../canonical/app/store') as typeof import('../canonical/app/store');
const {ReviewContext:GuardContext}=require('../canonical/review/ReviewContext') as typeof import('../canonical/review/ReviewContext');
const {UpgradeScreen:GuardUpgrade}=require('../canonical/review/SettingsScreens') as typeof import('../canonical/review/SettingsScreens');
const {usePlusIntents:guardIntents}=require('../canonical/review/plus-intents') as typeof import('../canonical/review/plus-intents');
const guardDeferred=()=>{let resolve!:()=>void,reject!:(error:Error)=>void;const promise=new Promise<void>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
async function pendingGuardPurchase(){
  const previous=AppState.currentState;AppState.currentState='active';
  const listeners=new Set<(phase:AppStateStatus)=>void>();
  const lifecycle=jest.spyOn(AppState,'addEventListener').mockImplementation((_type,listener)=>{listeners.add(listener);return {remove:()=>listeners.delete(listener)};});
  const frames:Array<(time:number)=>void>=[];const frame=jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.push(callback);return frames.length;});
  const navigation=require('@react-navigation/native') as typeof import('@react-navigation/native');
  let params:{reason:'guards';intent:string}|undefined;
  const back=jest.fn(),navigate=jest.fn((_name:string,value:unknown)=>{params=value as typeof params;});
  const Focus=React.createContext(true);const focus=jest.spyOn(navigation,'useIsFocused').mockImplementation(()=>React.useContext(Focus));
  const route=jest.spyOn(navigation,'useRoute').mockImplementation(()=>({key:'guard-purchase',name:'Upgrade',params}) as never);
  const nav=jest.spyOn(navigation,'useNavigation').mockImplementation(()=>({navigate,goBack:back,setOptions:jest.fn(),getState:()=>undefined}) as never);
  const original=guardDeferred(),requested=guardDeferred(),purchaseRefresh=guardDeferred();
  let requestedFailure=false,purchaseRefreshFailure=false,holdPurchaseRefresh=false;
  const fixture=require('./productFixture').productFixture as typeof import('./productFixture').productFixture;
  let wire={...fixture,nativeHostID:'guard-selection-host',backgroundPrivacyCoverRequired:false,
    preferences:{appearance:'system',look:'original',matchTextSize:true,textSize:1,haptics:false},
    plus:{enabled:false,busy:false,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},
    security:{unavailable:false,ownerRevision:'guard-selection-owner',readRevision:1,hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}};
  const actions:import('../canonical/app/contract').AppCommand[]=[];
  guardSelectionPort.getSnapshot.mockImplementation(async()=>JSON.stringify(wire));
  guardSelectionPort.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(wire));
  guardSelectionPort.command.mockImplementation(async(raw:string)=>{
    const action=JSON.parse(raw) as import('../canonical/app/contract').AppCommand;actions.push(action);
    if(action.type==='purchase.refresh'&&purchaseRefreshFailure)throw new Error('Current purchase refresh failed.');
    if(action.type==='purchase.refresh'&&holdPurchaseRefresh)await purchaseRefresh.promise;
    if(action.type==='settings.set'&&action.key==='look'){
      if(action.value==='original')await original.promise;
      else {await requested.promise;if(requestedFailure)throw new Error('Requested Guard write failed.');}
      wire={...wire,revision:wire.revision+1,preferences:{...wire.preferences,look:String(action.value)}};
    }
    return JSON.stringify({status:'completed',value:null,snapshot:wire});
  });
  const source=guardRuntime('guard-selection-host');const app=new GuardStore(source,JSON.parse(await source.getSnapshot()));
  let disconnect=app.connect();await act(async()=>{});
  const commandCalls=jest.spyOn(app,'command');
  let holder=app,showGuardian=true,showUpgrade=false,registry:PlusIntents|undefined;
  const observe=()=>{registry=guardIntents();return null;};
  const Observe=observe;
  function Root(){
    const state=React.useSyncExternalStore(holder.subscribe,holder.getSnapshot);const live=state.snapshot??undefined;
    mockReviewState={app:holder,live,session:initialSession(),setSession:jest.fn(),look:live?.look??'original',setLook:jest.fn()};
    return <GuardContext.Provider value={mockReviewState as unknown as import('../canonical/review/ReviewContext').ReviewState}>
      <Observe/>{showGuardian&&<Focus.Provider value={!showUpgrade}><GuardianScreen/></Focus.Provider>}{showUpgrade&&<Focus.Provider value={true}><GuardUpgrade/></Focus.Provider>}
    </GuardContext.Provider>;
  }
  const tree=render(<Root/>);await act(async()=>{});
  const hydrate=async()=>{screen.UNSAFE_queryAllByType(require('react-native').ScrollView).forEach(node=>fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:320,height:640}}}));await act(async()=>{holder.completePresentationLayout(holder.getPresentationHydration().epoch);});await act(async()=>{frames.splice(0).forEach(callback=>callback(0));});};
  await hydrate();
  const looks=()=>actions.filter(action=>action.type==='settings.set'&&action.key==='look');
  const rerender=async()=>{await act(async()=>{tree.rerender(<Root/>);});};
  const begin=async(purchaseInterrupted=false)=>{
    fireEvent.press(screen.getByTestId('guardian.option.original'));await act(async()=>{});
    expect(looks()).toHaveLength(1);
    fireEvent.press(screen.getByTestId('guardian.option.original'));await act(async()=>{});
    expect(looks()).toHaveLength(1); // ordinary repeated row remains bounded
    fireEvent.press(screen.getByTestId('guardian.option.emberObsidian'));await act(async()=>{});
    expect(navigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'guards',intent:expect.any(String)}));
    expect(registry!.peek(params!.intent)).toEqual(expect.any(Function));
    if(purchaseInterrupted){for(const phase of ['inactive','active'] as const){act(()=>{AppState.currentState=phase;listeners.forEach(listener=>listener(phase));});}}
    wire={...wire,revision:wire.revision+1,plus:{...wire.plus,enabled:true}};
    await act(async()=>{await app.refresh();});showUpgrade=true;await rerender();await hydrate();
    return params!.intent;
  };
  const close=()=>{tree.unmount();disconnect();commandCalls.mockRestore();nav.mockRestore();route.mockRestore();focus.mockRestore();frame.mockRestore();lifecycle.mockRestore();AppState.currentState=previous;};
  return {app,original,requested,purchaseRefresh,begin,looks,back,getRegistry:()=>registry!,rerender,close,
    holdPurchaseRefresh:()=>{holdPurchaseRefresh=true;},
    purchaseRefreshStarted:()=>actions.some(action=>action.type==='purchase.refresh'),
    queuedLooks:()=>commandCalls.mock.calls.filter(([action])=>action.type==='settings.set'&&action.key==='look'),
    failRequested:()=>{requestedFailure=true;},allowRequested:()=>{requestedFailure=false;},failPurchaseRefresh:()=>{purchaseRefreshFailure=true;},
    freshGrant:async()=>{wire={...wire,revision:wire.revision+1,security:{...wire.security,readRevision:wire.security.readRevision+1}};await act(async()=>{await app.refresh();});await hydrate();},
    retire:async(kind:'source unmount'|'generation'|'security owner'|'app holder'|'Upgrade removal'|'same holder reconnect'|'background return')=>{
      if(kind==='source unmount')showGuardian=false;
      if(kind==='Upgrade removal')showUpgrade=false;
      if(kind==='same holder reconnect'){const priorRead=app.getReadEpoch();disconnect();disconnect=app.connect();await act(async()=>{});expect(app.getReadEpoch()).toBe(priorRead);}
      if(kind==='background return'){for(const phase of ['background','active'] as const){act(()=>{AppState.currentState=phase;listeners.forEach(listener=>listener(phase));});}await act(async()=>{await app.refresh();});await hydrate();}
      if(kind==='generation')wire={...wire,generation:'successor-generation',revision:wire.revision+1};
      if(kind==='security owner')wire={...wire,revision:wire.revision+1,security:{...wire.security,ownerRevision:'successor-owner'}};
      if(kind==='app holder')holder=new GuardStore(source,app.getSnapshot().snapshot!);
      if(kind==='generation'||kind==='security owner')await act(async()=>{await app.refresh();});
      await rerender();
    }};
}

test('a purchased Guard waits for the earlier selection and its own real native commit before Upgrade consumes it',async()=>{
  const f=await pendingGuardPurchase();try{
    const intent=await f.begin(true);expect(f.back).not.toHaveBeenCalled();expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));
    await act(async()=>{f.original.resolve();});
    expect(f.looks().map(action=>'value' in action?action.value:undefined)).toEqual(['original','emberObsidian']);
    expect(f.back).not.toHaveBeenCalled();expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));
    await act(async()=>{f.requested.resolve();});
    expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
    await f.rerender();expect(f.looks()).toHaveLength(2);expect(f.back).toHaveBeenCalledTimes(1);
  }finally{f.close();}
});

test('an earlier Guard failure wakes the existing bounded Upgrade retry and applies the paid request once',async()=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
    const intent=await f.begin();expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.original.reject(new Error('Earlier Guard write failed.'));});
    expect(f.looks().map(action=>'value' in action?action.value:undefined)).toEqual(['original','emberObsidian']);
    expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.requested.resolve();});
    expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
  }finally{f.close();alert.mockRestore();}
});

test('a failed requested Guard remains registered after one bounded retry and a fresh grant may retry it',async()=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
    f.failRequested();const intent=await f.begin();expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.original.resolve();f.requested.resolve();});
    expect(f.looks()).toHaveLength(3);expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    expect(alert).toHaveBeenCalledWith('Lava','Requested Guard write failed.');expect(alert).toHaveBeenCalledTimes(1);
    await f.rerender();expect(f.looks()).toHaveLength(3); // permanent failure cannot spin
    f.allowRequested();await f.freshGrant();
    expect(f.looks()).toHaveLength(4);expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');
    expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
  }finally{f.close();alert.mockRestore();}
});

test.each(['source unmount','generation','security owner','app holder','Upgrade removal','same holder reconnect','background return'] as const)('a purchased Guard cannot dispatch its queued selection after %s retirement',async owner=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
    const intent=await f.begin();expect(f.back).not.toHaveBeenCalled();await f.retire(owner);
    await act(async()=>{f.original.resolve();});
    expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
    if(['generation','same holder reconnect','background return'].includes(owner))expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));
    else expect(f.getRegistry().peek(intent)).toBeUndefined();
    await f.rerender();expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
    if(['generation','same holder reconnect','background return'].includes(owner))expect(alert).not.toHaveBeenCalled();
  }finally{f.close();alert.mockRestore();}
});


test('a current ordinary purchase refresh failure still reports while the paid Guard continuation completes',async()=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
    f.failPurchaseRefresh();const intent=await f.begin();
    await act(async()=>{f.original.resolve();});
    expect(alert).toHaveBeenCalledWith('Lava','Current purchase refresh failed.');expect(alert).toHaveBeenCalledTimes(1);
    expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.requested.resolve();});
    expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
  }finally{f.close();alert.mockRestore();}
});


test.each(['source unmount','generation','security owner','app holder','Upgrade removal','same holder reconnect','background return'] as const)('a purchased Guard remains fenced at actual Store dispatch after second-tail %s retirement',async owner=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
    f.holdPurchaseRefresh();const intent=await f.begin();
    await act(async()=>{f.original.resolve();});
    expect(f.purchaseRefreshStarted()).toBe(true);
    expect(f.queuedLooks()).toHaveLength(2); // paid command is accepted into the real Store queue
    expect(f.looks()).toHaveLength(1); // refresh still holds its actual native dispatch
    expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    await f.retire(owner);
    await act(async()=>{f.purchaseRefresh.resolve();f.requested.resolve();});
    expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
    await f.rerender();expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
    expect(alert).not.toHaveBeenCalled();
  }finally{f.close();alert.mockRestore();}
});

test('a current paid Guard survives a held purchase refresh and dispatches once when that Store queue settles',async()=>{
  const f=await pendingGuardPurchase();try{
    f.holdPurchaseRefresh();const intent=await f.begin(true);
    await act(async()=>{f.original.resolve();});
    expect(f.purchaseRefreshStarted()).toBe(true);expect(f.queuedLooks()).toHaveLength(2);
    expect(f.looks()).toHaveLength(1);expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.purchaseRefresh.resolve();});
    expect(f.looks().map(action=>'value' in action?action.value:undefined)).toEqual(['original','emberObsidian']);
    expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
    await act(async()=>{f.requested.resolve();});
    expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
    await f.rerender();expect(f.looks()).toHaveLength(2);expect(f.back).toHaveBeenCalledTimes(1);
  }finally{f.close();}
});


describe('paid Guard rejected predecessor sequencing',()=>{
  test.each(['Authentication cancelled.','Earlier Guard write failed.'])('a paid request waits through predecessor %s without borrowing its failure',async message=>{
    const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
      const intent=await f.begin();
      const first=(f.app.command as jest.Mock).mock.results[0];expect(first?.type).toBe('return');
      const originalOutcome=(first!.value as Promise<unknown>).then(()=>undefined,error=>error);
      const failure=new Error(message);
      await act(async()=>{f.original.reject(failure);});
      expect(await originalOutcome).toBe(failure); // the original caller retains its own exact rejection
      expect(f.looks().map(action=>'value' in action?action.value:undefined)).toEqual(['original','emberObsidian']);
      expect(f.app.getSnapshot().snapshot?.look).toBe('original');
      expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
      expect(alert).not.toHaveBeenCalled(); // the unfocused original caller's failure is not the paid request's error
      await act(async()=>{f.requested.resolve();});
      expect(f.app.getSnapshot().snapshot?.look).toBe('emberObsidian');
      expect(f.getRegistry().peek(intent)).toBeUndefined();expect(f.back).toHaveBeenCalledTimes(1);
      await f.rerender();expect(f.looks()).toHaveLength(2);expect(f.back).toHaveBeenCalledTimes(1);
    }finally{f.close();alert.mockRestore();}
  });

  test('a paid request own authentication cancellation stays latched without consuming or replaying its intent',async()=>{
    const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
      const intent=await f.begin();await act(async()=>{f.original.resolve();});
      expect(f.looks().map(action=>'value' in action?action.value:undefined)).toEqual(['original','emberObsidian']);
      await act(async()=>{f.requested.reject(new Error('Authentication cancelled.'));});
      expect(f.app.getSnapshot().snapshot?.look).toBe('original');
      expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));expect(f.back).not.toHaveBeenCalled();
      expect(alert).not.toHaveBeenCalled();
      await f.rerender();await f.freshGrant();
      expect(f.looks()).toHaveLength(2);expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));
      expect(f.back).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();
    }finally{f.close();alert.mockRestore();}
  });

  test.each(['source unmount','generation','security owner','app holder','Upgrade removal','same holder reconnect','background return'] as const)('a rejected predecessor cannot authorize paid dispatch after %s retirement',async owner=>{
    const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});const f=await pendingGuardPurchase();try{
      const intent=await f.begin();await f.retire(owner);
      await act(async()=>{f.original.reject(new Error('Authentication cancelled.'));});
      expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
      if(['generation','same holder reconnect','background return'].includes(owner))expect(f.getRegistry().peek(intent)).toEqual(expect.any(Function));
      else expect(f.getRegistry().peek(intent)).toBeUndefined();
      await f.rerender();expect(f.looks()).toHaveLength(1);expect(f.back).not.toHaveBeenCalled();
      expect(alert).not.toHaveBeenCalled();
    }finally{f.close();alert.mockRestore();}
  });
});
