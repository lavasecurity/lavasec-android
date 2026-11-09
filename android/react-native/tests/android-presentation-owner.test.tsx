import React from 'react';
import {act,fireEvent,renderHook,render,screen} from '@testing-library/react-native';
import {AppState,Text,View,type AppStateStatus} from 'react-native';
import type {AppSnapshot,AppCommand} from '../canonical/app/contract';
import type {Spec} from '../canonical/specs/NativeLavaApp';
import {AppStore} from '../canonical/app/store';

let mockApp:AppStore;
let mockShippingRoot=false;
jest.mock('../canonical/review/ReviewContext',()=>{
  const actual=jest.requireActual('../canonical/review/ReviewContext');
  return {...actual,useReview:()=>mockShippingRoot?actual.useReview():({app:mockApp,live:mockApp.getSnapshot().snapshot}),
    useOptionalReview:()=>mockShippingRoot?actual.useOptionalReview():({app:mockApp})};
});
const mockRoute={key:'native-modal',name:'AddDomain',params:{id:'original'}};
let mockFocused=true;
const mockRouteListeners=new Map<string,Set<(event:{target?:string;data?:{closing?:boolean}})=>void>>();
const mockRouteNavigation={isFocused:()=>mockFocused,addListener:(type:string,listener:(event:{target?:string;data?:{closing?:boolean}})=>void)=>{
  let listeners=mockRouteListeners.get(type);if(!listeners){listeners=new Set();mockRouteListeners.set(type,listeners);}listeners.add(listener);return()=>listeners!.delete(listener);
}};
let mockShippingStateChange:()=>void;
const mockShippingNavigation={...mockRouteNavigation,isReady:()=>true,getCurrentRoute:()=>mockRoute,
  getRootState:()=>({key:'sharing-tabs',type:'tab',index:0,routes:[{key:'sharing-tab',name:'GuardTab',state:{index:0,routes:[mockRoute]}}]}),
  navigate:jest.fn(),dispatch:jest.fn(),canGoBack:()=>false,setOptions:jest.fn()};
jest.mock('@react-navigation/native',()=>{
  const React=require('react'),View=require('react-native').View,actual=jest.requireActual('@react-navigation/native');
  return {useIsFocused:()=>mockFocused,useRoute:()=>mockRoute,useNavigation:()=>mockShippingRoot?mockShippingNavigation:mockRouteNavigation,
    usePreventRemove:jest.fn(),NavigationContainer:React.forwardRef(({children,onReady,onStateChange}:{children:React.ReactNode;onReady?:()=>void;onStateChange:()=>void},_ref:unknown)=>{
      mockShippingStateChange=onStateChange;React.useLayoutEffect(()=>{onReady?.();},[]);return React.createElement(View,null,children);
    }),createNavigationContainerRef:()=>mockShippingNavigation,PreventRemoveContext:actual.PreventRemoveContext,NavigationRouteContext:actual.NavigationRouteContext,
    DarkTheme:{colors:{}},DefaultTheme:{colors:{}}};
});
const mockShippingNavigator=()=>{
  const React=require('react');return {Screen:()=>null,Navigator:({children}:{children:React.ReactNode})=>{
    const candidates=React.Children.toArray(children) as React.ReactElement<{name?:string;component?:React.ComponentType}>[];
    const selected=candidates.find(child=>child.props.name===mockRoute.name)??candidates[0];
    return selected?.props.component?React.createElement(selected.props.component):null;
  }};
};
jest.mock('@react-navigation/native-stack',()=>({createNativeStackNavigator:()=>mockShippingNavigator()}));
jest.mock('@react-navigation/bottom-tabs',()=>({createBottomTabNavigator:()=>mockShippingNavigator()}));
jest.mock('../canonical/review/OnboardingSurface',()=>({OnboardingSurface:({children}:{children:React.ReactNode})=>children}));
jest.mock('../canonical/review/screens',()=>{
  const actual=jest.requireActual('../canonical/review/FilterScreens'),React=require('react'),Text=require('react-native').Text;
  return {ShareScreen:actual.ShareScreen,ShareDetailScreen:actual.ShareDetailScreen,GuardScreen:()=>null,SettingsScreen:()=>null,
    RuntimeError:({message}:{message:string})=>React.createElement(Text,null,message)};
});
jest.mock('react-native-safe-area-context',()=>{const React=require('react'),View=require('react-native').View;return {
  SafeAreaInsetsContext:React.createContext({top:0,right:0,bottom:0,left:0}),SafeAreaProvider:View,SafeAreaView:View,
  useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0}),useSafeAreaFrame:()=>({x:0,y:0,width:360,height:720})};});
import {AndroidPresentationInteractionContext,AndroidFieldAutoFocusContext,withAndroidPresentation,androidProtectedSurface,androidPresentationOwnerID,AndroidPresentationControlContext,AndroidPresentationContext,useAndroidPresentationReady,type AndroidPresentation} from '../canonical/review/presentation-owner.android';

let lifecycle:Set<(state:AppStateStatus)=>void>,frames:Map<number,(time:number)=>void>,nextFrame:number;
const snapshot=(revision:number):AppSnapshot=>({schema:1,fullApp:true,revision,generation:'owner',foreground:true,available:true,
  security:{unavailable:false,recoveryRequired:false,protectedSurfaces:['appSettings'],authenticatedSurfaces:['appSettings'],ownerRevision:'owner',readRevision:revision,sourceRevision:'source',displayClearRevision:'clear'},
} as AppSnapshot);
const presentation:AndroidPresentation={serial:7,routeKey:'retained-vpn',ownerID:'foreground:editor-1',surface:'appSettings'};
test.each([
  ['CustomEntry','blocklist','filterEditing'],['CustomEntry','dns','appSettings'],
  ['Foreground','createFilter','filterEditing'],['Foreground','renameFilter','filterEditing'],['Foreground','deleteFilters','filterEditing'],
  ['Foreground','feedback','appSettings'],['Foreground','vpnConfiguration','appSettings'],['Foreground','licenses','none'],['Foreground','automation','none'],
  ['History',undefined,'activityViewing'],['Passcode',undefined,'credentials'],['Guard',undefined,'none'],
])('native cover for %s / %s attests its actual protected surface', (route,kind,surface)=>{
  expect(androidProtectedSurface(route,kind)).toBe(surface);
});
const deferred=<T,>()=>{let resolve!:(value:T)=>void,reject!:(error:Error)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
beforeEach(()=>{
  AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,callback)=>{lifecycle.add(callback);return {remove:()=>lifecycle.delete(callback)};});
  jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.set(++nextFrame,callback);return nextFrame;});
  jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
});
afterEach(()=>jest.restoreAllMocks());
const phase=async(state:AppStateStatus)=>act(async()=>{AppState.currentState=state;for(const callback of lifecycle)callback(state);});
async function flushFrames(){for(let turn=0;turn<12;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const current=[...frames.values()];frames.clear();await act(async()=>{for(const callback of current)callback(turn*16);await Promise.resolve();});}}
async function fixture(){
  let current=snapshot(1);const native={getSnapshot:jest.fn(async()=>JSON.stringify(current)),onSnapshot:()=>({remove(){}}),command:jest.fn(async(_raw:string)=>JSON.stringify({snapshot:current,result:null}))};
  mockApp=new AppStore(native as unknown as Spec);const disconnect=mockApp.connect();await act(async()=>{await mockApp.refresh();});
  return {native,disconnect,replace:(revision:number,fields:Partial<AppSnapshot>={})=>{current={...snapshot(revision),...fields};},readyCalls:()=>native.command.mock.calls.map(([raw])=>JSON.parse(raw) as AppCommand).filter(command=>command.type==='navigation.presentationReady')};
}

test('the same retained route reattests native ownership after pause, only after its new measured layout and scoped reads settle',async()=>{
  const f=await fixture();let measured=false;
  const view=renderHook(()=>useAndroidPresentationReady(presentation,true,measured));
  await flushFrames();expect(f.readyCalls()).toEqual([]);
  measured=true;view.rerender(undefined);await flushFrames();
  expect(f.readyCalls()).toEqual([{type:'navigation.presentationReady',serial:7,ownerID:'foreground:editor-1',readRevision:1}]);
  const accepted=mockApp.getSnapshot().snapshot;
  await phase('inactive');await flushFrames();expect(mockApp.getSnapshot().snapshot).toBe(accepted);
  expect(mockApp.getPresentationAuthority()).toBe(false);
  f.replace(2);await phase('active');
  let pending:ReturnType<AppStore['registerPresentationRead']>;
  await act(async()=>{await mockApp.refresh();pending=mockApp.registerPresentationRead();});
  await flushFrames();expect(mockApp.getPresentationHydration().required).toBe(true);
  expect(f.readyCalls()).toHaveLength(1);
  await act(async()=>mockApp.settlePresentationRead(pending));await flushFrames();
  expect(f.readyCalls()).toEqual([
    {type:'navigation.presentationReady',serial:7,ownerID:'foreground:editor-1',readRevision:1},
    {type:'navigation.presentationReady',serial:7,ownerID:'foreground:editor-1',readRevision:2},
  ]);
  expect(mockApp.getPresentationHydration().required).toBe(false);
  view.unmount();f.disconnect();
});

test('a late rejected attestation from the old grant cannot clear the new same-route acknowledgement',async()=>{
  const f=await fixture(),old=deferred<string>();f.native.command.mockReturnValueOnce(old.promise);
  const view=renderHook(()=>useAndroidPresentationReady(presentation,true,true));await flushFrames();expect(f.readyCalls()).toHaveLength(1);
  await phase('inactive');f.replace(2);await phase('active');await act(async()=>{await mockApp.refresh();});await flushFrames();
  expect(f.readyCalls()).toHaveLength(2);
  await act(async()=>old.reject(new Error('Read access changed.')));await flushFrames();
  expect(f.readyCalls()).toHaveLength(2);
  view.unmount();f.disconnect();
});

test.each([['onboarding','onboarding:setup-1'],['CustomEntry','custom-entry:dns-visit']] as const)('the underlying retained route cannot attest an independently measured %s owner',async(kind,expectedOwnerID)=>{
  const ownerID=kind==='CustomEntry'?androidPresentationOwnerID('CustomEntry','dns-visit'):expectedOwnerID;
  // The native owner namespace is an independent contract, not an expectation
  // derived from the routing helper being exercised.
  expect(ownerID).toBe(expectedOwnerID);
  const f=await fixture();let current=false;
  const modal={...presentation,serial:8,routeKey:expectedOwnerID,ownerID,surface:'none' as const};
  const view=renderHook(()=>useAndroidPresentationReady(modal,current,true));await flushFrames();expect(f.readyCalls()).toEqual([]);
  current=true;view.rerender(undefined);await flushFrames();
  expect(f.readyCalls()).toEqual([{type:'navigation.presentationReady',serial:8,ownerID:expectedOwnerID,readRevision:1}]);
  view.unmount();f.disconnect();
});


test.each(['unmounted','replaced','backgrounded'] as const)('a completed native Ready cannot acknowledge an %s publication',async(disposition)=>{
  const f=await fixture(),old=deferred<string>(),ready=jest.fn();
  f.native.command.mockReturnValueOnce(old.promise);
  const control={beginModal:()=>presentation,restoreMain:jest.fn(),ready};
  const wrapper=({children}:{children:React.ReactNode})=><AndroidPresentationControlContext.Provider value={control}>{children}</AndroidPresentationControlContext.Provider>;
  let owner=presentation;
  const view=renderHook(()=>useAndroidPresentationReady(owner,true,true),{wrapper});
  await flushFrames();expect(f.readyCalls()).toHaveLength(1);expect(ready).not.toHaveBeenCalled();
  if(disposition==='unmounted')view.unmount();
  else if(disposition==='replaced'){owner={...presentation,serial:8,ownerID:'foreground:successor'};view.rerender(undefined);await flushFrames();expect(ready).toHaveBeenCalledWith(8,mockApp.getPresentationHydration().epoch);ready.mockClear();}
  else await phase('inactive');
  await act(async()=>{old.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));await Promise.resolve();});await flushFrames();
  expect(ready).not.toHaveBeenCalled();if(disposition!=='unmounted')view.unmount();f.disconnect();
});

test('the exact live native Ready success supplies one receipt with the current hydration epoch',async()=>{
  const f=await fixture(),reply=deferred<string>(),ready=jest.fn();f.native.command.mockReturnValueOnce(reply.promise);
  const control={beginModal:()=>presentation,restoreMain:jest.fn(),ready};
  const wrapper=({children}:{children:React.ReactNode})=><AndroidPresentationControlContext.Provider value={control}>{children}</AndroidPresentationControlContext.Provider>;
  const view=renderHook(()=>useAndroidPresentationReady(presentation,true,true),{wrapper});await flushFrames();expect(ready).not.toHaveBeenCalled();
  await act(async()=>{reply.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));await Promise.resolve();});await flushFrames();
  expect(ready).toHaveBeenCalledTimes(1);expect(ready).toHaveBeenCalledWith(7,mockApp.getPresentationHydration().epoch);
  view.rerender(undefined);await flushFrames();expect(ready).toHaveBeenCalledTimes(1);view.unmount();f.disconnect();
});


test('native route autofocus waits for the real current opening end independently from measured Ready and refuses obsolete transition callbacks',async()=>{
  mockFocused=true;mockRoute.params.id='original';mockRouteListeners.clear();
  const f=await fixture();
  const Probe=()=>{const ready=React.useContext(AndroidFieldAutoFocusContext);return <Text testID="focus-receipt">{String(ready)}</Text>;};
  const Route=withAndroidPresentation(Probe);
  const view=render(<Route/>);
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  await flushFrames();expect(f.readyCalls()).toEqual([]); // No layout, hence no native write receipt.
  const oldEnd=[...mockRouteListeners.get('transitionEnd')!][0]!;
  await act(async()=>oldEnd({target:'foreign-route',data:{closing:false}}));
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  await act(async()=>oldEnd({target:mockRoute.key,data:{closing:false}}));
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('true');
  expect(f.readyCalls()).toEqual([]); // Animation completion cannot grant hydration/native readiness.
  await act(async()=>{for(const callback of mockRouteListeners.get('transitionStart')!)callback({target:mockRoute.key,data:{closing:true}});});
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  await act(async()=>oldEnd({target:mockRoute.key,data:{closing:true}}));
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  mockRoute.params.id='successor';view.rerender(<Route/>);
  await act(async()=>oldEnd({target:mockRoute.key,data:{closing:false}}));
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  const fresh=[...mockRouteListeners.get('transitionEnd')!][0]!;
  await act(async()=>fresh({target:mockRoute.key,data:{closing:false}}));
  expect(screen.getByTestId('focus-receipt')).toHaveTextContent('true');
  mockFocused=false;view.rerender(<Route/>);expect(screen.getByTestId('focus-receipt')).toHaveTextContent('false');
  view.unmount();await act(async()=>fresh({target:mockRoute.key,data:{closing:false}}));
  expect(mockRouteListeners.get('transitionEnd')).toHaveProperty('size',0);f.disconnect();mockFocused=true;
});


test('the exact completed native opening survives first snapshot hydration before its delayed field body mounts',async()=>{
  mockFocused=true;mockRoute.params.id='cold-original';mockRouteListeners.clear();
  const read=deferred<string>(),native={getSnapshot:jest.fn(()=>read.promise),onSnapshot:()=>({remove(){}}),command:jest.fn()};
  mockApp=new AppStore(native as unknown as Spec);const disconnect=mockApp.connect();
  const Probe=()=>{const ready=React.useContext(AndroidFieldAutoFocusContext);return mockApp.getSnapshot().snapshot?<Text testID="cold-focus">{String(ready)}</Text>:null;};
  const Route=withAndroidPresentation(Probe),view=render(<Route/>);
  expect(screen.queryByTestId('cold-focus')).toBeNull();
  await act(async()=>{for(const callback of mockRouteListeners.get('transitionEnd')!)callback({target:mockRoute.key,data:{closing:false}});});
  await act(async()=>read.resolve(JSON.stringify({...snapshot(1),nativeHostID:'original-host'})));
  expect(screen.getByTestId('cold-focus')).toHaveTextContent('true');
  expect(native.command).not.toHaveBeenCalled(); // Focus never completes or borrows native Ready.
  view.unmount();disconnect();
});


test('a retained field cannot borrow an independent modal receipt even after its own opening completed',async()=>{
  mockFocused=true;mockRoute.params.id='retained-private';mockRouteListeners.clear();
  const f=await fixture();
  const Probe=()=> <Text testID="modal-focus">{String(React.useContext(AndroidFieldAutoFocusContext))}</Text>;
  const Route=withAndroidPresentation(Probe);
  const own:AndroidPresentation={serial:1,routeKey:mockRoute.key,ownerID:'foreground:retained-private',surface:'appSettings',accepted:true};
  const view=render(<AndroidPresentationContext.Provider value={own}><Route/></AndroidPresentationContext.Provider>);
  await act(async()=>{for(const callback of mockRouteListeners.get('transitionEnd')!)callback({target:mockRoute.key,data:{closing:false}});});
  expect(screen.getByTestId('modal-focus')).toHaveTextContent('true');
  const modal:AndroidPresentation={serial:2,routeKey:'independent-public-modal',ownerID:'onboarding:setup',surface:'none',accepted:true};
  view.rerender(<AndroidPresentationContext.Provider value={modal}><Route/></AndroidPresentationContext.Provider>);
  expect(mockRouteNavigation.isFocused()).toBe(true); // The underlying nested navigator is retained and still focused.
  expect(screen.getByTestId('modal-focus')).toHaveTextContent('false');
  view.rerender(<AndroidPresentationContext.Provider value={{...own,serial:3,accepted:false}}><Route/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('modal-focus')).toHaveTextContent('false');
  view.rerender(<AndroidPresentationContext.Provider value={{...own,serial:3,accepted:true}}><Route/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('modal-focus')).toHaveTextContent('true'); // The same opening receipt survives cover/hydration.
  expect(f.readyCalls()).toEqual([]); // Transition focus never supplies measured native Ready.
  view.unmount();f.disconnect();
});


test('interaction waits for the exact native Ready acknowledgement while measured hydration finishes independently',async()=>{
  const f=await fixture(),ack=deferred<string>();f.native.command.mockReturnValueOnce(ack.promise);
  const view=renderHook(()=>useAndroidPresentationReady({...presentation,accepted:true},true,true));
  try{
    await flushFrames();expect(f.readyCalls()).toHaveLength(1);
    expect(mockApp.getPresentationAuthority()).toBe(true);
    expect(mockApp.getPresentationHydration().required).toBe(false);
    expect(view.result.current).toBe(false);
    await act(async()=>ack.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(view.result.current).toBeTruthy();expect(f.readyCalls()).toHaveLength(1);
  }finally{view.unmount();ack.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));f.disconnect();}
});


test('a same-key native Ready result from a replaced AppStore cannot grant the successor route',async()=>{
  const first=await fixture(),old=deferred<string>();first.native.command.mockReturnValueOnce(old.promise);
  const view=renderHook(()=>useAndroidPresentationReady(presentation,true,true));await flushFrames();
  const successor=await fixture(),fresh=deferred<string>();successor.native.command.mockReturnValueOnce(fresh.promise);
  view.rerender(undefined);await flushFrames();
  expect(first.readyCalls()).toHaveLength(1);expect(successor.readyCalls()).toHaveLength(1);
  expect(view.result.current).toBe(false);
  try{
    await act(async()=>old.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(view.result.current).toBe(false);
    await act(async()=>fresh.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(view.result.current).toBeTruthy();
  }finally{view.unmount();old.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));fresh.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));first.disconnect();successor.disconnect();}
});

test.each(['owner','graph','revision','modal','pause'] as const)('an old native Ready cannot grant interaction after its %s boundary',async boundary=>{
  const f=await fixture(),old=deferred<string>(),fresh=deferred<string>();
  f.native.command.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
  let owner=presentation,current=true;
  const view=renderHook(()=>useAndroidPresentationReady(owner,current,true));await flushFrames();
  try{
    if(boundary==='owner')owner={...presentation,serial:8,ownerID:'foreground:replacement'};
    else if(boundary==='graph'||boundary==='revision'){
      f.replace(2,boundary==='graph'?{generation:'successor-graph'}:{});await act(async()=>mockApp.refresh());
    }else if(boundary==='modal'){current=false;owner={...presentation,serial:8,routeKey:'onboarding:replacement',ownerID:'onboarding:replacement'};}
    else await phase('inactive');
    view.rerender(undefined);await flushFrames();expect(view.result.current).toBe(false);
    await act(async()=>old.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(view.result.current).toBe(false);
    if(boundary==='modal'){current=true;owner={...presentation,serial:9};view.rerender(undefined);}
    if(boundary==='pause'){f.replace(2);await phase('active');await act(async()=>mockApp.refresh());view.rerender(undefined);}
    await flushFrames();expect(f.readyCalls()).toHaveLength(2);
    await act(async()=>fresh.resolve(JSON.stringify({snapshot:mockApp.getSnapshot().snapshot,result:null})));await flushFrames();
    expect(view.result.current).toBeTruthy();
  }finally{view.unmount();old.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));fresh.resolve(JSON.stringify({snapshot:snapshot(2),result:null}));f.disconnect();}
});

test('a resolved native Ready refusal never grants controls and the retry stays bounded',async()=>{
  const f=await fixture();f.native.command.mockResolvedValue(JSON.stringify({snapshot:snapshot(1),result:{status:'rejected'}}));
  const view=renderHook(()=>useAndroidPresentationReady(presentation,true,true));
  try{await flushFrames();expect(view.result.current).toBe(false);expect(f.readyCalls()).toHaveLength(2);}
  finally{view.unmount();f.disconnect();}
});


test('the production route keeps controls closed without a publication and admits only its exact measured native Ready',async()=>{
  mockFocused=true;mockRoute.params.id='ready-owner';mockRouteListeners.clear();
  const f=await fixture(),ack=deferred<string>();f.native.command.mockReturnValueOnce(ack.promise);
  const Probe=()=> <Text testID="route-interaction">{String(!!React.useContext(AndroidPresentationInteractionContext))}</Text>;
  const Route=withAndroidPresentation(Probe);
  const own:AndroidPresentation={serial:20,routeKey:mockRoute.key,ownerID:'foreground:ready-owner',surface:'appSettings',accepted:false};
  const view=render(<AndroidPresentationContext.Provider value={null}><Route/></AndroidPresentationContext.Provider>);
  try{
    fireEvent(view.UNSAFE_getByType(View),'layout',{nativeEvent:{layout:{width:320,height:640}}});await flushFrames();
    expect(screen.getByTestId('route-interaction')).toHaveTextContent('false');expect(f.readyCalls()).toHaveLength(0);
    view.rerender(<AndroidPresentationContext.Provider value={own}><Route/></AndroidPresentationContext.Provider>);
    fireEvent(view.UNSAFE_getByType(View),'layout',{nativeEvent:{layout:{width:320,height:640}}});await flushFrames();
    expect(screen.getByTestId('route-interaction')).toHaveTextContent('false');expect(f.readyCalls()).toHaveLength(0);
    view.rerender(<AndroidPresentationContext.Provider value={{...own,accepted:true}}><Route/></AndroidPresentationContext.Provider>);
    await flushFrames();expect(f.readyCalls()).toHaveLength(1);
    expect(mockApp.getPresentationHydration().required).toBe(false);
    expect(screen.getByTestId('route-interaction')).toHaveTextContent('false');
    await act(async()=>ack.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(screen.getByTestId('route-interaction')).toHaveTextContent('true');
    view.rerender(<AndroidPresentationContext.Provider value={{...own,serial:21,accepted:false}}><Route/></AndroidPresentationContext.Provider>);
    expect(screen.getByTestId('route-interaction')).toHaveTextContent('false');expect(f.readyCalls()).toHaveLength(1);
  }finally{view.unmount();ack.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));f.disconnect();}
});


test('a lost viewport measurement retires Ready and remeasurement requires a new acknowledgement',async()=>{
  const f=await fixture(),fresh=deferred<string>();
  let measured=true;const view=renderHook(()=>useAndroidPresentationReady(presentation,true,measured));
  try{
    await flushFrames();expect(view.result.current).toBeTruthy();expect(f.readyCalls()).toHaveLength(1);
    f.native.command.mockReturnValueOnce(fresh.promise);
    measured=false;view.rerender(undefined);expect(view.result.current).toBe(false);await flushFrames();
    expect(f.readyCalls()).toHaveLength(1);
    measured=true;view.rerender(undefined);await flushFrames();
    expect(mockApp.getPresentationHydration().required).toBe(false);
    expect(f.readyCalls()).toHaveLength(2);expect(view.result.current).toBe(false);
    await act(async()=>fresh.resolve(JSON.stringify({snapshot:snapshot(1),result:null})));await flushFrames();
    expect(view.result.current).toBeTruthy();
  }finally{view.unmount();fresh.resolve(JSON.stringify({snapshot:snapshot(1),result:null}));f.disconnect();}
});

// Exercise the shipping Root publisher and real retained sharing bodies, rather than a fixture-owned surface list.
import {NativeModules,Platform,ScrollView} from 'react-native';
import {waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
const sharingPort={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=sharingPort;
const SharingCanonicalApp=require('../CanonicalApp').default as typeof import('../CanonicalApp').default;
const sharingCompleted=JSON.stringify({status:'completed'});
const sharingCommands=()=>sharingPort.command.mock.calls.map(([raw])=>JSON.parse(raw) as {type:string;surface?:string;serial?:number;nativeHostID?:string;generation?:string});
const sharingPublications=()=>sharingCommands().filter(command=>command.type==='navigation.presentation');
const sharingMeasure=()=>act(()=>{for(const node of [...screen.UNSAFE_getAllByType(View),...screen.UNSAFE_getAllByType(ScrollView)])if(node.props.onLayout)fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});});
describe('shipping sharing protected presentation ownership',()=>{
  const os=Platform.OS;
  afterEach(()=>{mockShippingRoot=false;mockRoute.name='AddDomain';mockRoute.key='native-modal';mockRoute.params.id='original';Platform.OS=os;});
  test.each(['Share','ShareDetail'] as const)('retained %s is published as Filters and waits for resumed native admission without a grant',async name=>{
    Platform.OS='android';mockShippingRoot=true;mockFocused=true;sharingPort.getSnapshot.mockReset();sharingPort.getSnapshotForHost.mockReset();sharingPort.command.mockReset();mockShippingNavigation.navigate.mockClear();mockRoute.name=name;mockRoute.key=`retained-${name}`;mockRoute.params.id='filter1';
    let wire:ProductSnapshot={...productFixture,nativeHostID:'share-cover-host',revision:10,backgroundPrivacyCoverRequired:true,appPrivacyCoverRequired:false,
      security:{unavailable:false,ownerRevision:'sharing-owner',readRevision:1,sourceRevision:'sharing-source',hasAuthenticationMethod:true,appUnlockCoverChoice:true,protectedSurfaces:['appUnlock','filterEditing'],authenticatedSurfaces:['appUnlock','filterEditing']},
      filters:{...productFixture.filters,rows:[{...productFixture.filters.rows[0]!,name:'Retained private filter'}]}};
    const resumed=deferred<string>();let published=0;
    sharingPort.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(wire));sharingPort.getSnapshot.mockImplementation(async()=>JSON.stringify(wire));
    sharingPort.command.mockImplementation(async(raw:string)=>{
      const command=JSON.parse(raw);
      if(command.type==='navigation.presentation'&&++published>=2)return resumed.promise;
      if(command.type==='share.query')return JSON.stringify({status:'completed',value:{code:'RETAINED-SETUP-CODE',url:'https://example.invalid/share',image:null}});
      return sharingCompleted;
    });
    const view=render(<SharingCanonicalApp nativeHostID="share-cover-host"/>);
    try{
      await waitFor(()=>expect(sharingPublications()).toHaveLength(1));sharingMeasure();await flushFrames();
      expect(sharingPublications()[0]).toMatchObject({surface:'filterEditing',nativeHostID:'share-cover-host',generation:'7',serial:1});
      await waitFor(()=>expect(sharingCommands().some(command=>command.type==='navigation.presentationReady'&&command.serial===1)).toBe(true));
      if(name==='Share')expect(screen.getByText('Retained private filter')).toBeTruthy();
      else await waitFor(()=>expect(screen.getByText('RETAINED-SETUP-CODE')).toBeTruthy());
      await phase('background');
      expect(screen.getByTestId('lava-route-privacy-cover',{includeHiddenElements:true})).toBeTruthy();
      expect(screen.UNSAFE_getAllByType(ScrollView).every(node=>node.props.pointerEvents==='none'&&node.props.importantForAccessibility==='no-hide-descendants')).toBe(true);
      wire={...wire,revision:11,appPrivacyCoverRequired:true,security:{...wire.security!,readRevision:2,authenticatedSurfaces:[]}};
      await phase('active');await waitFor(()=>expect(sharingPublications()).toHaveLength(2));sharingMeasure();await flushFrames();
      expect(sharingPublications()[1]).toMatchObject({surface:'filterEditing',nativeHostID:'share-cover-host',generation:'7',serial:2});
      expect(sharingCommands().filter(command=>command.type==='navigation.authorize')).toEqual([]);
      expect(sharingCommands().filter(command=>command.type==='navigation.presentationReady')).toHaveLength(1);
      expect(mockShippingNavigation.navigate).not.toHaveBeenCalled();
      await act(async()=>resumed.resolve(JSON.stringify({status:'rejected',reason:'authentication-required'})));await flushFrames();
      expect(sharingCommands().filter(command=>command.type==='navigation.presentationReady')).toHaveLength(1);
    }finally{view.unmount();resumed.resolve(sharingCompleted);await act(async()=>{});}
  });
});
