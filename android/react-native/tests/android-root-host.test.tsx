import React from 'react';
import {AppState,DeviceEventEmitter,NativeModules,View,type AppStateStatus} from 'react-native';
import {act,fireEvent,render,screen,waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';

const mockNavigationMount=jest.fn(),mockBodyMount=jest.fn();
let mockObservedApp:import('../canonical/app/store').AppStore|undefined;
const mockRoute:{key:string;name:string;params?:{id?:string;kind?:string}}={key:'retained-guard',name:'Guard'};
let mockStateChange:()=>void;
const mockNavigation={isReady:()=>true,getCurrentRoute:()=>mockRoute,
  getRootState:()=>({key:'tabs',type:'tab',index:0,routes:[{key:'guard-tab',name:'GuardTab',state:{index:0,routes:[mockRoute]}}]}),
  navigate:jest.fn(),dispatch:jest.fn(),canGoBack:()=>false,setOptions:jest.fn()};
jest.mock('@react-navigation/native',()=>{
  const React=require('react'),View=require('react-native').View;
  return {NavigationContainer:React.forwardRef(({children,onReady,onStateChange}:{children:React.ReactNode;onReady?:()=>void;onStateChange:()=>void},_ref:unknown)=>{
    mockStateChange=onStateChange;React.useLayoutEffect(()=>{mockNavigationMount();onReady?.();},[]);return React.createElement(View,null,children);
  }),createNavigationContainerRef:()=>mockNavigation,
    useIsFocused:()=>true,useRoute:()=>mockRoute,useNavigation:()=>mockNavigation,usePreventRemove:jest.fn(),
    PreventRemoveContext:jest.requireActual('@react-navigation/native').PreventRemoveContext,
    NavigationRouteContext:jest.requireActual('@react-navigation/native').NavigationRouteContext,
    DarkTheme:{colors:{}},DefaultTheme:{colors:{}}};
});
const mockNavigator=()=>{
  const React=require('react');return {Screen:()=>null,Navigator:({children}:{children:React.ReactNode})=>{
    const first=React.Children.toArray(children)[0] as React.ReactElement<{component?:React.ComponentType}>;
    return first.props.component?React.createElement(first.props.component):null;
  }};
};
jest.mock('@react-navigation/native-stack',()=>({createNativeStackNavigator:()=>mockNavigator()}));
jest.mock('@react-navigation/bottom-tabs',()=>({createBottomTabNavigator:()=>mockNavigator()}));
jest.mock('../canonical/review/OnboardingSurface',()=>{
  const React=require('react'),View=require('react-native').View;
  const {AndroidPresentationContext,AndroidPresentationControlContext,useAndroidPresentationReady}=require('../canonical/review/presentation-owner.android');
  return {OnboardingSurface:({children}:{children:React.ReactNode})=>{
    const {live}=require('../canonical/review/ReviewContext').useReview(),setup=live?.onboardingSetup;
    const presentation=React.useContext(AndroidPresentationContext),control=React.useContext(AndroidPresentationControlContext);
    const latest=React.useRef(control);latest.current=control;
    const [measured,setMeasured]=React.useState(false);
    React.useLayoutEffect(()=>{if(setup){latest.current?.beginModal(`onboarding:${setup.id}`);return()=>latest.current?.restoreMain();}},[setup?.id]);
    useAndroidPresentationReady(presentation,!!setup&&presentation?.ownerID===`onboarding:${setup.id}`,measured);
    return React.createElement(View,null,children,setup?React.createElement(View,{testID:'root.modal',onLayout:()=>setMeasured(true)}):null);
  }};
});
jest.mock('../canonical/review/screens',()=>{
  const React=require('react'),Text=require('react-native').Text;
  const Body=()=>{const {app,live}=require('../canonical/review/ReviewContext').useReview();mockObservedApp=app;React.useEffect(()=>{mockBodyMount();},[]);
    const {BufferedInput}=require('../canonical/review/BufferedInput');
    return React.createElement(React.Fragment,null,React.createElement(Text,{testID:'root.filter'},live?.filters[0]?.name),
      React.createElement(BufferedInput,{testID:'root.field',value:'Retained field',accessibilityLabel:'Field',onChangeText:jest.fn()}));};
  return {GuardScreen:Body,SettingsScreen:Body,RuntimeError:({message,retry}:{message:string;retry:()=>void})=>React.createElement(React.Fragment,null,React.createElement(Text,null,message),React.createElement(Text,{accessibilityRole:'button',onPress:retry},'Try again'))};
});
jest.mock('react-native-safe-area-context',()=>{const React=require('react'),View=require('react-native').View;return {
  SafeAreaInsetsContext:React.createContext({top:0,right:0,bottom:0,left:0}),SafeAreaProvider:View,SafeAreaView:View,
  useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0}),useSafeAreaFrame:()=>({x:0,y:0,width:360,height:720})};});

const port={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const CanonicalApp=require('../CanonicalApp').default as typeof import('../CanonicalApp').default;
const {LavaUIReview}=require('../canonical/review/LavaUIReview.android') as typeof import('../canonical/review/LavaUIReview.android');
const {projectAndroidSnapshot}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
let current:ProductSnapshot;
let lifecycle:Set<(state:AppStateStatus)=>void>,frames:Map<number,(time:number)=>void>,nextFrame:number;
beforeEach(()=>{
  AppState.currentState='active';jest.clearAllMocks();mockObservedApp=undefined;mockRoute.key='retained-guard';mockRoute.name='Guard';mockRoute.params=undefined;
  lifecycle=new Set();frames=new Map();nextFrame=0;
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,callback)=>{lifecycle.add(callback);return {remove:()=>lifecycle.delete(callback)};});
  jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.set(++nextFrame,callback);return nextFrame;});
  jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
  current={...productFixture,nativeHostID:'source',revision:10};
  port.getSnapshot.mockResolvedValue(JSON.stringify({...productFixture,nativeHostID:'qa',revision:1000}));
  port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(current));
  port.command.mockResolvedValue(JSON.stringify({status:'completed'}));
});
afterEach(()=>jest.restoreAllMocks());
const commands=()=>port.command.mock.calls.map(([raw])=>JSON.parse(raw) as {type:string;nativeHostID:string;serial:number;generation:string;ownerID?:string;surface?:string});
const deferred=()=>{let resolve!:(value:string)=>void;const promise=new Promise<string>(yes=>{resolve=yes;});return {promise,resolve};};
const completed=JSON.stringify({status:'completed'}),backgroundRejected=JSON.stringify({status:'rejected',reason:'background'});
const phase=async(state:AppStateStatus)=>act(async()=>{AppState.currentState=state;for(const callback of lifecycle)callback(state);await Promise.resolve();});
async function flushFrames(){for(let turn=0;turn<16;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const current=[...frames.values()];frames.clear();await act(async()=>{for(const callback of current)callback(turn*16);await Promise.resolve();});}}
const presentations=()=>commands().filter(command=>command.type==='navigation.presentation');
const readiness=()=>commands().filter(command=>command.type==='navigation.presentationReady');
const measure=()=>act(()=>{for(const node of screen.UNSAFE_getAllByType(View))if(node.props.onLayout)fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});});

test('the actual launch entry binds reads and measured admission to its native host, then re-admits the same retained route on graph replacement',async()=>{
  const view=render(<CanonicalApp nativeHostID="source" initialAppearance="system"/>);
  await waitFor(()=>expect(screen.getByTestId('root.filter',{includeHiddenElements:true})).toHaveTextContent('My filter'));
  await waitFor(()=>expect(commands().filter(command=>command.type==='navigation.presentation')).toHaveLength(1));
  measure();await flushFrames();
  await waitFor(()=>expect(commands().some(command=>command.type==='navigation.presentationReady'&&command.serial===1)).toBe(true));
  expect(commands().find(command=>command.type==='navigation.presentation')).toMatchObject({nativeHostID:'source',generation:'7',serial:1});
  expect(port.getSnapshot).not.toHaveBeenCalled();
  expect(port.getSnapshotForHost.mock.calls.every(([host])=>host==='source')).toBe(true);
  current={...current,generation:'8',revision:11};
  await act(async()=>{DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));await Promise.resolve();});
  await waitFor(()=>expect(commands().filter(command=>command.type==='navigation.presentation')).toHaveLength(2));
  expect(commands().filter(command=>command.type==='navigation.presentation')[1]).toMatchObject({nativeHostID:'source',generation:'8',serial:2});
  measure();await flushFrames();
  await waitFor(()=>expect(commands().some(command=>command.type==='navigation.presentationReady'&&command.serial===2&&command.generation==='8')).toBe(true));
  expect(mockNavigation.navigate).not.toHaveBeenCalled();
  expect(mockNavigation.dispatch).not.toHaveBeenCalled();
  view.unmount();
});

test('a bound root refuses an unproven legacy launch seed and never displays a foreign host event',async()=>{
  const foreign=projectAndroidSnapshot({...current,filters:{...current.filters,rows:[{...current.filters.rows[0]!,name:'Private QA seed'}]}});
  const view=render(<LavaUIReview fullApp nativeHostID="source" initialSnapshot={JSON.stringify(foreign)}/>);
  expect(screen.queryByText('Private QA seed',{includeHiddenElements:true})).toBeNull();
  await waitFor(()=>expect(screen.getByTestId('root.filter',{includeHiddenElements:true})).toHaveTextContent('My filter'));
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify({...current,nativeHostID:'qa',revision:100,
    filters:{...current.filters,rows:[{...current.filters.rows[0]!,name:'Private QA event'}]}})));
  expect(screen.queryByText('Private QA event',{includeHiddenElements:true})).toBeNull();
  expect(screen.getByTestId('root.filter',{includeHiddenElements:true})).toHaveTextContent('My filter');
  view.unmount();
});


test('a native publication receipt precedes readiness and ordinary field editing',async()=>{
  const reply=deferred();port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'?reply.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));
  measure();await flushFrames();
  expect(readiness()).toEqual([]);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  await act(async()=>{reply.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:1,nativeHostID:'source',generation:'7'})]);
  expect(screen.getByTestId('root.field').props.editable).toBe(true);view.unmount();
});

test('a background rejection is replaced by an accepted publication of the same retained main route before readiness resumes',async()=>{
  const old=deferred(),resumed=deferred();let count=0;
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'?(++count===1?old.promise:resumed.promise):completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  await phase('inactive');
  await act(async()=>{old.resolve(backgroundRejected);await Promise.resolve();});await flushFrames();
  expect(presentations()).toHaveLength(1);expect(readiness()).toEqual([]);
  expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  current={...current,revision:11};await phase('active');
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(presentations()[1]).toMatchObject({serial:2,nativeHostID:'source',generation:'7',surface:'none'});
  expect(readiness()).toEqual([]);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  await act(async()=>{resumed.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2,nativeHostID:'source',generation:'7'})]);
  expect(screen.getByTestId('root.field').props.editable).toBe(true);expect(mockNavigation.navigate).not.toHaveBeenCalled();view.unmount();
});

test('a late same-route old publication rejection cannot revoke the accepted foreground successor',async()=>{
  const old=deferred();let count=0;
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'&&++count===1?old.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  await phase('inactive');current={...current,revision:11};await phase('active');
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2})]);expect(screen.getByTestId('root.field').props.editable).toBe(true);
  await act(async()=>{old.resolve(backgroundRejected);await Promise.resolve();});await flushFrames();
  expect(presentations()).toHaveLength(2);expect(readiness()).toEqual([expect.objectContaining({serial:2})]);
  expect(screen.getByTestId('root.field').props.editable).toBe(true);view.unmount();
});

test('a publication acknowledgement for an old route cannot admit its new foreground owner',async()=>{
  const old=deferred(),currentReply=deferred();let count=0;
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'?(++count===1?old.promise:currentReply.promise):completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  await act(async()=>{mockRoute.key='custom-dns';mockRoute.name='CustomEntry';mockRoute.params={id:'dns-visit',kind:'dns'};mockStateChange();});
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(presentations()[1]).toMatchObject({serial:2,ownerID:'custom-entry:dns-visit',surface:'appSettings'});
  await act(async()=>{old.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([]);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  await act(async()=>{currentReply.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2,ownerID:'custom-entry:dns-visit'})]);view.unmount();
});

test('an independently measured modal also waits for its exact native publication receipt',async()=>{
  const modal=deferred();port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).ownerID==='onboarding:setup-1'&&JSON.parse(raw).type==='navigation.presentation'?modal.promise:completed);
  const setup={id:'setup-1',mock:false,page:0,history:[],visited:[0],level:'balanced' as const,fallback:false,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:false,notifications:false,busy:'',error:'',phase:'setup' as const};
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));
  current={...current,revision:11,onboardingSetup:setup};
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(presentations()[1]).toMatchObject({serial:2,ownerID:'onboarding:setup-1',surface:'none'});
  expect(readiness()).toEqual([]);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  await act(async()=>{modal.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2,ownerID:'onboarding:setup-1'})]);
  expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);view.unmount();
});

test('an active refusal retries once and a permanently rejected native publication never opens readiness',async()=>{
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'?backgroundRejected:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(presentations()).toHaveLength(2);expect(readiness()).toEqual([]);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);view.unmount();
});


test('the same independent modal is re-published after pause and remains concealed until the resumed receipt is accepted',async()=>{
  const resumed=deferred();let modalCount=0;
  port.command.mockImplementation(async(raw:string)=>{
    const command=JSON.parse(raw);
    return command.type==='navigation.presentation'&&command.ownerID==='onboarding:setup-1'&&++modalCount===2?resumed.promise:completed;
  });
  const setup={id:'setup-1',mock:false,page:0,history:[],visited:[0],level:'balanced' as const,fallback:false,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:false,notifications:false,busy:'',error:'',phase:'setup' as const};
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));
  current={...current,revision:11,onboardingSetup:setup};
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2,ownerID:'onboarding:setup-1'})]);
  await phase('inactive');current={...current,revision:12};await phase('active');
  await waitFor(()=>expect(presentations()).toHaveLength(3));measure();await flushFrames();
  expect(presentations()[2]).toMatchObject({serial:3,nativeHostID:'source',generation:'7',ownerID:'onboarding:setup-1',surface:'none'});
  expect(readiness()).toHaveLength(1);expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
  await act(async()=>{resumed.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:2,ownerID:'onboarding:setup-1'}),expect.objectContaining({serial:3,ownerID:'onboarding:setup-1'})]);
  expect(mockNavigation.navigate).not.toHaveBeenCalled();view.unmount();
});


test('the actual matching native redirect is consumed only after its exact destination Ready succeeds',async()=>{
  const reply=deferred();current={...current,navigation:{serial:23,tab:'GuardTab',screen:'Guard'}};
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentationReady'?reply.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:1})]);
  expect(commands().filter(command=>command.type==='navigation.accept')).toEqual([]);
  await act(async()=>{reply.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.accept')).toEqual([
    expect.objectContaining({nativeHostID:'source',generation:'7',serial:23,presentationSerial:1}),
  ]);
  measure();await flushFrames();expect(commands().filter(command=>command.type==='navigation.accept')).toHaveLength(1);
  expect(mockNavigation.dispatch).not.toHaveBeenCalled();view.unmount();
});

test('an old destination Ready completion cannot consume a successor native entry',async()=>{
  const old=deferred();let count=0;current={...current,navigation:{serial:23,tab:'GuardTab',screen:'Guard'}};
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentationReady'&&++count===1?old.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.accept')).toEqual([]);
  await phase('inactive');current={...current,revision:11,navigation:{serial:24,tab:'GuardTab',screen:'Guard'}};await phase('active');
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.accept')).toEqual([expect.objectContaining({serial:24,presentationSerial:2})]);
  await act(async()=>{old.resolve(completed);await Promise.resolve();});await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.accept')).toHaveLength(1);view.unmount();
});


test('cold navigation mounts beneath its cover while the unprepared private body waits for native authority',async()=>{
  const first=deferred();port.getSnapshotForHost.mockReturnValue(first.promise);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await act(async()=>{});
  expect(mockNavigationMount).toHaveBeenCalledTimes(1);
  expect(mockBodyMount).not.toHaveBeenCalled();expect(commands()).toEqual([]);
  await act(async()=>first.resolve(JSON.stringify(current)));
  await waitFor(()=>expect(mockBodyMount).toHaveBeenCalledTimes(1));
  expect(screen.getByTestId('root.filter',{includeHiddenElements:true})).toHaveTextContent('My filter');
  view.unmount();
});

test('a failed first read commits only a fixed public Retry frame and retires it before normal private reveal',async()=>{
  port.getSnapshotForHost.mockRejectedValue(new Error('Private backend diagnostic must not be displayed'));
  const normal=deferred();port.command.mockImplementation(async(raw:string)=>{
    const command=JSON.parse(raw);
    if(command.type==='navigation.retryPresentation')return JSON.stringify({status:'completed',value:{generation:'7',serial:command.serial}});
    if(command.type==='navigation.presentation')return normal.promise;
    return completed;
  });
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(screen.getByText('Lava could not load the app. Try again.')).toBeTruthy());
  expect(screen.queryByText('Private backend diagnostic must not be displayed')).toBeNull();
  expect(mockNavigationMount).toHaveBeenCalledTimes(1);expect(mockBodyMount).not.toHaveBeenCalled();
  await waitFor(()=>expect(commands().filter(command=>command.type==='navigation.retryPresentation')).toHaveLength(1));
  expect(commands().filter(command=>command.type==='navigation.retryPresentationReady')).toEqual([]);
  measure();await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.retryPresentationReady')).toEqual([expect.objectContaining({nativeHostID:'source',generation:'7',serial:1})]);
  expect(readiness()).toEqual([]);expect(mockBodyMount).not.toHaveBeenCalled();
  port.getSnapshotForHost.mockResolvedValue(JSON.stringify(current));
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Try again'})));
  await waitFor(()=>expect(mockBodyMount).toHaveBeenCalledTimes(1));
  expect(screen.getByTestId('lava-render-frame',{includeHiddenElements:true}).props.style).toEqual(expect.arrayContaining([expect.objectContaining({opacity:0})]));
  expect(readiness()).toEqual([]);
  await act(async()=>normal.resolve(completed));measure();await flushFrames();
  expect(screen.getByTestId('lava-render-frame',{includeHiddenElements:true}).props.style).toEqual(expect.arrayContaining([expect.objectContaining({opacity:1})]));
  expect(readiness()).toEqual([expect.objectContaining({serial:2,generation:'7'})]);
  expect(commands().filter(command=>command.type==='navigation.retryPresentationReady')).toHaveLength(1);
  view.unmount();
});

test('a cold redacted projection does not construct a protected page before its first authoritative frame',async()=>{
  current={...current,available:false,presentationBlocked:true,filters:{...current.filters,rows:[]}};
  const view=render(<CanonicalApp nativeHostID="source"/>);await act(async()=>{});
  expect(mockNavigationMount).toHaveBeenCalledTimes(1);expect(mockBodyMount).not.toHaveBeenCalled();expect(readiness()).toEqual([]);
  current={...productFixture,nativeHostID:'source',revision:11};
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));
  await waitFor(()=>expect(mockBodyMount).toHaveBeenCalledTimes(1));
  view.unmount();
});


test('a native authentication-turn publication repaints a retained route even when JS missed the pause event',async()=>{
  current={...current,security:{unavailable:false,readRevision:1}};
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
  expect(readiness()).toEqual([expect.objectContaining({serial:1})]);
  const body=screen.getByTestId('root.field'),rev=current.security!.readRevision!;
  current={...current,revision:11,security:{...current.security!,readRevision:rev+1}};
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));
  await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
  expect(presentations()[1]).toMatchObject({generation:'7',serial:2});
  expect(readiness()).toEqual([expect.objectContaining({serial:1}),expect.objectContaining({serial:2})]);
  expect(screen.getByTestId('root.field')).toBe(body);expect(mockBodyMount).toHaveBeenCalledTimes(1);
  view.unmount();
});

test('a retired cold Retry frame cannot acknowledge after an authoritative private frame replaces its pending native prepare',async()=>{
  const retry=deferred();port.getSnapshotForHost.mockRejectedValue(new Error('private initial error'));
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.retryPresentation'?retry.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  await waitFor(()=>expect(commands().filter(command=>command.type==='navigation.retryPresentation')).toHaveLength(1));measure();await flushFrames();
  port.getSnapshotForHost.mockResolvedValue(JSON.stringify(current));
  await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));
  await waitFor(()=>expect(mockBodyMount).toHaveBeenCalledTimes(1));
  await act(async()=>retry.resolve(JSON.stringify({status:'completed',value:{generation:'7',serial:1}})));measure();await flushFrames();
  expect(commands().filter(command=>command.type==='navigation.retryPresentationReady')).toEqual([]);
  expect(readiness()).toEqual([expect.objectContaining({serial:2})]);view.unmount();
});


// The real Android root must tell the native cover which grant protects retained Import state.
test.each([false,true])('Import publication uses Filters when Settings is unprotected and the filter grant is %s',async granted=>{
  mockRoute.key='retained-import';mockRoute.name='Import';
  current={...current,security:{unavailable:false,hasAuthenticationMethod:true,ownerRevision:'security-owner',readRevision:1,
    protectedSurfaces:['filterEditing'],authenticatedSurfaces:granted?['filterEditing']:[]}};
  const view=render(<CanonicalApp nativeHostID="source"/>);
  try{
    await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
    expect(presentations()[0]).toMatchObject({nativeHostID:'source',generation:'7',serial:1,surface:'filterEditing'});
    expect(presentations().some(command=>command.surface==='appSettings')).toBe(false);
    expect(readiness()).toEqual([expect.objectContaining({nativeHostID:'source',generation:'7',serial:1})]);
    expect(mockBodyMount).toHaveBeenCalledTimes(1);expect(mockNavigation.navigate).not.toHaveBeenCalled();
  }finally{view.unmount();}
});

test('a retained Import route resumes with its own filter publication before a fresh measured receipt',async()=>{
  mockRoute.key='retained-import';mockRoute.name='Import';
  current={...current,security:{unavailable:false,hasAuthenticationMethod:true,ownerRevision:'security-owner',readRevision:1,
    protectedSurfaces:['filterEditing'],authenticatedSurfaces:['filterEditing']}};
  const resumed=deferred();let count=0;
  port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'&&++count===2?resumed.promise:completed);
  const view=render(<CanonicalApp nativeHostID="source"/>);
  try{
    await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
    expect(presentations()[0]).toMatchObject({serial:1,surface:'filterEditing'});
    const retained=screen.getByTestId('root.field');expect(retained.props.editable).toBe(true);
    await phase('inactive');expect(screen.queryByTestId('root.field')).toBeNull();expect(screen.getByTestId('root.field',{includeHiddenElements:true}).props.editable).toBe(false);
    current={...current,revision:11,security:{...current.security!,readRevision:2,authenticatedSurfaces:[]}};
    await phase('active');await waitFor(()=>expect(presentations()).toHaveLength(2));measure();await flushFrames();
    expect(presentations()[1]).toMatchObject({nativeHostID:'source',generation:'7',serial:2,surface:'filterEditing'});
    expect(readiness()).toHaveLength(1);expect(screen.getByTestId('root.field',{includeHiddenElements:true})).toBe(retained);
    expect(retained.props.editable).toBe(false);expect(mockBodyMount).toHaveBeenCalledTimes(1);
    await act(async()=>resumed.resolve(completed));await flushFrames();
    expect(readiness()).toEqual([expect.objectContaining({serial:1}),expect.objectContaining({serial:2})]);
    // Successful layout does not grant Filters: the native cover consumes this same surface.
    expect(presentations().every(command=>command.surface==='filterEditing')).toBe(true);
    expect(mockObservedApp?.getSnapshot().snapshot).toMatchObject({generation:'7',nativeHostID:'source',
      security:{readRevision:2,authenticatedSurfaces:[]}});expect(mockNavigation.navigate).not.toHaveBeenCalled();
  }finally{resumed.resolve(completed);view.unmount();}
});
