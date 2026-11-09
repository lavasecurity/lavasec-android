import React from 'react';
import {AppState,DeviceEventEmitter,NativeModules,View,ScrollView,Text,type AppStateStatus} from 'react-native';
import {act,fireEvent,renderAsync,screen,waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
import type {AppSnapshot} from '../canonical/app/contract';
type NativeFixture=Omit<ProductSnapshot,'filterEditing'>&{plus?:AppSnapshot['plus'];filterEditing?:NonNullable<ProductSnapshot['filterEditing']>&{upgradeReason?:string}};

// The production tree has an independent setup stack around a tab navigator
// and its route stacks. Keep every real navigator and removal registry here.
// Only page content/native leaves are reduced, so lifecycle admission remains real.
jest.mock('@react-navigation/elements',()=>jest.requireActual('../node_modules/@react-navigation/elements/lib/module/index.js'));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{stopDemo:jest.fn(),getGuardAccents:()=>JSON.stringify({original:{light:'#BF4000',dark:'#FF8855'}}),getLegalNotices:()=>'{"sections":[]}',getBlocklistCatalog:()=> '[]'}}));
let mockPageNavigation:any,mockCurrentApp:any;
function mockRoutePage(){
 const React=require('react'),{View,Text}=require('react-native');
 const {useNavigation,useRoute}=require('@react-navigation/native');
 const navigation=useNavigation(),route=useRoute(),{app,live}=require('../canonical/review/ReviewContext').useReview();
 const {useToolbar}=require('../canonical/review/scaffold');
 mockPageNavigation=navigation;mockCurrentApp=app;
 useToolbar({title:route.name},[live?.session]);
 return React.createElement(View,{testID:`lifecycle.${route.name}`},React.createElement(Text,null,route.name));
}
jest.mock('../canonical/review/screens',()=>new Proxy({__esModule:true},{get:(target:any,key:string)=>key in target?target[key]:['ReviewScreen','AddDomainScreen','AddBlocklistScreen'].includes(key)?jest.requireActual('../canonical/review/FilterScreens')[key]:key==='UpgradeScreen'?jest.requireActual('../canonical/review/SettingsScreens').UpgradeScreen:mockRoutePage}));
// Keep shipping FilterRoute/FilterScreen/ReviewScreen and picker pages: real removal
// registration, read readiness, exact owner identity and filter.close lifecycle.
jest.mock('../canonical/review/OnboardingFlow',()=>({OnboardingFlow:require('react-native').View}));

jest.mock('../node_modules/@react-navigation/native-stack/lib/module/views/NativeStackView',()=>jest.requireActual('../node_modules/@react-navigation/native-stack/lib/module/views/NativeStackView.native'));

const mockOwnedRoutes=new Map<string,{route:any;navigation:any;callback:(event:{data:{action:any}})=>void}>();
jest.mock('@react-navigation/native',()=>{
 const actual=jest.requireActual('@react-navigation/native');
 return {...actual,usePreventRemove:(prevent:boolean,callback:(event:{data:{action:any}})=>void)=>{
  const route=actual.useRoute(),navigation=actual.useNavigation();
  if(prevent)mockOwnedRoutes.set(route.name,{route,navigation,callback});
  return actual.usePreventRemove(prevent,callback);
 }};
});
const mockTabNotifications:{changed:boolean}[]=[];
let mockTabPrevented:Record<string,unknown>={},mockTabCorePrevented:Record<string,unknown>={},mockParentTabKey='';
jest.mock('@react-navigation/bottom-tabs',()=>{
 const actual=jest.requireActual('@react-navigation/bottom-tabs');
 const React=require('react'),native=require('@react-navigation/native');
 function ObserveParent({children}:{children:React.ReactNode}){
  const parent=React.useContext(native.PreventRemoveContext);
  const navigation=React.useContext(native.NavigationHelpersContext);mockParentTabKey=navigation.getState().routes[0].key;
  mockTabPrevented=parent.preventedRoutes;
  const methods=React.useMemo(()=>{
   const entries=new Map<string,string>();let dirty=false;
   return {
    setPreventRemove:(id:string,key:string,prevent:boolean)=>{
     parent.setPreventRemove(id,key,prevent);
     const changed=prevent?entries.get(id)!==key:entries.has(id);
     if(prevent)entries.set(id,key);else entries.delete(id);
     if(changed)dirty=true;
    },
    notifyPreventRemove:()=>{mockTabNotifications.push({changed:dirty});dirty=false;parent.notifyPreventRemove();},
   };
  },[parent.setPreventRemove,parent.notifyPreventRemove]);
  const value=React.useMemo(()=>({...methods,preventedRoutes:parent.preventedRoutes}),[methods,parent.preventedRoutes]);
  return React.createElement(native.PreventRemoveContext.Provider,{value},children);
 }
 function ObserveCore({children}:{children:React.ReactNode}){mockTabCorePrevented=React.useContext(native.PreventRemoveContext).preventedRoutes;return children;}
 return {...actual,createBottomTabNavigator:()=>{
  const stack=actual.createBottomTabNavigator(),Navigator=stack.Navigator;
  return {...stack,Navigator:(props:any)=>React.createElement(Navigator,{...props,layout:(layoutProps:any)=>React.createElement(ObserveCore,null,props.layout?props.layout({...layoutProps,children:React.createElement(ObserveParent,null,layoutProps.children)}):React.createElement(ObserveParent,null,layoutProps.children))})};
 }};
});


const port={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const CanonicalApp=require('../CanonicalApp').default as typeof import('../CanonicalApp').default;
let current:NativeFixture,lifecycle:Set<(state:AppStateStatus)=>void>,frames:Map<number,(time:number)=>void>,nextFrame:number;
const completed=JSON.stringify({status:'completed'});
const commands=()=>port.command.mock.calls.map(([raw])=>JSON.parse(raw) as {type:string;serial:number;ownerID?:string;nativeHostID?:string;id?:string});
const presentations=()=>commands().filter(command=>command.type==='navigation.presentation');
async function phase(state:AppStateStatus){await act(async()=>{AppState.currentState=state;for(const listener of lifecycle)listener(state);await Promise.resolve();});}
async function emit(patch:Partial<NativeFixture>){current={...current,...patch,revision:current.revision+1};await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));}
async function flushFrames(){for(let turn=0;turn<20;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const queued=[...frames.values()];frames.clear();await act(async()=>{for(const frame of queued)frame(turn*16);await Promise.resolve();});}}
function measure(){act(()=>{for(const node of [...screen.UNSAFE_queryAllByType(View),...screen.UNSAFE_queryAllByType(ScrollView)])if(node.props.onLayout)fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});});}
beforeEach(()=>{
 jest.clearAllMocks();mockOwnedRoutes.clear();mockTabNotifications.length=0;mockTabPrevented={};mockTabCorePrevented={};AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
 jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(frame=>{frames.set(++nextFrame,frame);return nextFrame;});
 jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
 current={...productFixture,nativeHostID:'lifecycle-host',revision:10,backgroundPrivacyCoverRequired:false};
 port.getSnapshot.mockImplementation(async()=>JSON.stringify(current));port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(current));port.command.mockResolvedValue(completed);
});
afterEach(()=>jest.restoreAllMocks());



const rules=Array.from({length:26},(_,i)=>`capacity-${i}.invalid`);
const session=(editing:boolean):NonNullable<ProductSnapshot['filterSession']>=>({id:'filter1',lease:'capacity-draft-1',name:'My filter',editing,lists:['source1'],savedLists:['source1'],blocked:rules,allowed:['staged.invalid'],savedBlocked:rules,savedAllowed:[],names:{source1:'Block List Basic'}});
const edit=(plus:boolean):NonNullable<NativeFixture['filterEditing']>=>({canSave:true,reviewCanConfirm:plus,validation:plus?null:'blockedDomains',upgradeReason:plus?undefined:'blockedDomains',refreshing:false,lists:[],blocked:[],allowed:[{id:'staged.invalid',pending:true,undo:false}]});
async function settle(){measure();await flushFrames();await act(async()=>{await Promise.resolve();});}
test('actual native-stack contextual Plus replacement retains inner removal ownership without a provider update loop',async()=>{
 current={...current,security:{unavailable:false,readRevision:1,ownerRevision:'7:0',sourceRevision:'baseline',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]},filterSession:session(true),filterEditing:edit(false),plus:{enabled:false,checking:false,busy:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]}};
 port.command.mockImplementation(async(raw:string)=>{
  const command=JSON.parse(raw);
  if(command.type==='filter.save')return JSON.stringify({status:'completed',value:'review'});
  if(command.type==='filter.review')return JSON.stringify({status:'completed',value:'capacity-review'});
  return completed;
 });
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 await flushFrames();await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Save'}).props.accessibilityState.disabled).toBe(false));
 expect(mockTabNotifications.some(event=>event.changed)).toBe(true);
 expect(mockTabPrevented[mockParentTabKey]).toEqual({preventRemove:true});expect(mockTabCorePrevented).toEqual({});
 mockTabNotifications.length=0;
 await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));await settle();
 await waitFor(()=>expect(screen.getByText('Upgrade to Lava Plus to block more domains')).toBeTruthy());
 const upgrade=mockOwnedRoutes.get('Upgrade')!;
 await emit({plus:{...current.plus!,enabled:true},filterEditing:edit(true)});await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Confirm changes'}).props.accessibilityState.disabled).toBe(false));
 expect(commands().filter(command=>command.type==='filter.save')).toHaveLength(1);
 expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
 expect(mockTabPrevented[mockParentTabKey]).toEqual({preventRemove:true});expect(mockTabCorePrevented).toEqual({});
 expect(mockTabNotifications.filter(event=>!event.changed)).toHaveLength(0);
 const review=mockOwnedRoutes.get('Review')!;
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Review');
 await phase('inactive');
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await phase('active');
 expect(mockCurrentApp.getPresentationHydration().required).toBe(true);
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 const oldDispatch=jest.spyOn(upgrade.navigation,'dispatch');
 await act(async()=>upgrade.callback({data:{action:{type:'GO_BACK',source:upgrade.route.key,target:upgrade.navigation.getState().key}}}));
 expect(oldDispatch).not.toHaveBeenCalled();oldDispatch.mockRestore();
 await act(async()=>review.navigation.goBack());await settle();
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Filter');
 expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
 await view.unmountAsync();
},15_000);

test('actual native-stack contextual Plus settles fresh native owner snapshots during every command',async()=>{
 current={...current,security:{unavailable:false,readRevision:1,ownerRevision:'7:0',sourceRevision:'baseline',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]},filterSession:session(true),filterEditing:edit(false),plus:{enabled:false,checking:false,busy:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]}};
 port.command.mockImplementation(async(raw:string)=>{
  const command=JSON.parse(raw);
  current={...current,revision:current.revision+1};
  DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));
  if(command.type==='filter.save')return JSON.stringify({status:'completed',value:'review'});
  if(command.type==='filter.review')return JSON.stringify({status:'completed',value:'capacity-review'});
  return completed;
 });
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 await flushFrames();await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Save'}).props.accessibilityState.disabled).toBe(false));
 for(let step=0;step<70;step++){await emit({filterSession:session(true),filterEditing:edit(false)});}
 await settle();
 expect(mockTabNotifications.some(event=>event.changed)).toBe(true);
 expect(mockTabPrevented[mockParentTabKey]).toEqual({preventRemove:true});expect(mockTabCorePrevented).toEqual({});
 mockTabNotifications.length=0;
 await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));await settle();
 await waitFor(()=>expect(screen.getByText('Upgrade to Lava Plus to block more domains')).toBeTruthy());
 const upgrade=mockOwnedRoutes.get('Upgrade')!;
 await emit({plus:{...current.plus!,enabled:true},filterEditing:edit(true)});await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Confirm changes'}).props.accessibilityState.disabled).toBe(false));
 expect(commands().filter(command=>command.type==='filter.save')).toHaveLength(1);
 expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
 expect(mockTabPrevented[mockParentTabKey]).toEqual({preventRemove:true});expect(mockTabCorePrevented).toEqual({});
 expect(mockTabNotifications.filter(event=>!event.changed)).toHaveLength(0);
 const review=mockOwnedRoutes.get('Review')!;
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Review');
 await phase('inactive');
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await phase('active');
 expect(mockCurrentApp.getPresentationHydration().required).toBe(true);
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 const oldDispatch=jest.spyOn(upgrade.navigation,'dispatch');
 await act(async()=>upgrade.callback({data:{action:{type:'GO_BACK',source:upgrade.route.key,target:upgrade.navigation.getState().key}}}));
 expect(oldDispatch).not.toHaveBeenCalled();oldDispatch.mockRestore();
 await act(async()=>review.navigation.goBack());await settle();
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Filter');
 expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
 await view.unmountAsync();
},15_000);

const Native=require('@react-navigation/native') as typeof import('@react-navigation/native');
const {createNativeStackNavigator}=require('@react-navigation/native-stack') as typeof import('@react-navigation/native-stack');
const {AndroidNestedRemovalBoundary}=require('../canonical/review/independent-navigation.android') as typeof import('../canonical/review/independent-navigation.android');
const Parent=createNativeStackNavigator(),Inner=createNativeStackNavigator();
const Registration=React.createContext({prevent:true,show:true,allow:false});
let parentNavigation:any,parentKey='',parentPrevention:Record<string,unknown>={},childActions:any[];
function InnerPage(){
 const navigation=Native.useNavigation(),{prevent,allow}=React.useContext(Registration);
 Native.usePreventRemove(prevent,({data})=>{childActions.push(data.action);if(allow)navigation.dispatch(data.action);});
 return <View testID="inner-protected-body"/>;
}
function NestedPage(){
 parentKey=Native.useRoute().key;parentNavigation=Native.useNavigation();parentPrevention=Native.usePreventRemoveContext().preventedRoutes;
 const {show}=React.useContext(Registration);
 return <AndroidNestedRemovalBoundary>{show?<Inner.Navigator screenOptions={{headerShown:false}}><Inner.Screen name="Inner" component={InnerPage}/></Inner.Navigator>:<View/>}</AndroidNestedRemovalBoundary>;
}
const EmptyPage=()=> <View/>;
function ParentTree({prevent=true,show=true,allow=false}:{prevent?:boolean;show?:boolean;allow?:boolean}){
 return <Registration.Provider value={{prevent,show,allow}}><Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Nested'}]}}><Parent.Navigator screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Nested" component={NestedPage}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree></Registration.Provider>;
}
test('nested facade forwards actual registration, removal, disposal and original ancestor Back through real native providers',async()=>{
 childActions=[];
 const view=await renderAsync(<ParentTree/>);
 await waitFor(()=>expect(parentPrevention[parentKey]).toEqual({preventRemove:true}));
 await act(async()=>parentNavigation.goBack());
 expect(parentNavigation.getState().routes.at(-1)?.name).toBe('Nested');expect(childActions).toHaveLength(1);
 await view.rerenderAsync(<ParentTree prevent={false}/>);
 await waitFor(()=>expect(parentPrevention[parentKey]).toBeUndefined());
 await view.rerenderAsync(<ParentTree/>);
 await waitFor(()=>expect(parentPrevention[parentKey]).toEqual({preventRemove:true}));
 await view.rerenderAsync(<ParentTree show={false}/>);
 await waitFor(()=>expect(parentPrevention[parentKey]).toBeUndefined());
 await view.rerenderAsync(<ParentTree allow/>);
 await waitFor(()=>expect(parentPrevention[parentKey]).toEqual({preventRemove:true}));
 await act(async()=>parentNavigation.goBack());
 expect(parentNavigation.getState().routes.at(-1)?.name).toBe('Root');
 expect(childActions).toHaveLength(2);expect(childActions[1].type).toBe('GO_BACK');
 await view.unmountAsync();
});

test('facade follows actual parent methods and late old-parent teardown cannot mutate its replacement',async()=>{
 const first={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{}},second={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{successor:{preventRemove:true}}};
 let facade!:ReturnType<typeof Native.usePreventRemoveContext>;
 function Capture(){facade=Native.usePreventRemoveContext();return <View/>;}
 const tree=(parent:typeof first|typeof second)=><Native.PreventRemoveContext.Provider value={parent}><AndroidNestedRemovalBoundary><Capture/></AndroidNestedRemovalBoundary></Native.PreventRemoveContext.Provider>;
 const view=await renderAsync(tree(first));const old=facade;
 act(()=>{old.setPreventRemove('nested-id','original',true);old.notifyPreventRemove();});
 expect(first.setPreventRemove).toHaveBeenLastCalledWith('nested-id','original',true);expect(first.notifyPreventRemove).toHaveBeenCalledTimes(1);
 await view.rerenderAsync(tree({...first,preventedRoutes:{original:{preventRemove:true}}}));
 expect(facade.setPreventRemove).toBe(old.setPreventRemove);expect(facade.notifyPreventRemove).toBe(old.notifyPreventRemove);
 await view.rerenderAsync(tree(second));const fresh=facade;
 expect(fresh.setPreventRemove).not.toBe(old.setPreventRemove);expect(fresh.preventedRoutes).toBe(second.preventedRoutes);
 act(()=>{fresh.setPreventRemove('nested-id','successor',true);fresh.notifyPreventRemove();fresh.notifyPreventRemove();});
 expect(second.setPreventRemove).toHaveBeenCalledTimes(1);expect(second.setPreventRemove).toHaveBeenLastCalledWith('nested-id','successor',true);expect(second.notifyPreventRemove).toHaveBeenCalledTimes(1);
 act(()=>{old.setPreventRemove('nested-id','original',false);old.notifyPreventRemove();});
 expect(first.setPreventRemove).toHaveBeenLastCalledWith('nested-id','original',false);expect(first.notifyPreventRemove).toHaveBeenCalledTimes(2);
 expect(second.setPreventRemove).toHaveBeenCalledTimes(1);expect(second.notifyPreventRemove).toHaveBeenCalledTimes(1);expect(facade.preventedRoutes.successor).toEqual({preventRemove:true});
 await view.unmountAsync();
});


test('facade publishes only final registry changes across an owned insertion cleanup and reattachment',async()=>{
 const parent={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{}};
 let facade!:ReturnType<typeof Native.usePreventRemoveContext>;
 function Capture(){facade=Native.usePreventRemoveContext();return <View/>;}
 const view=await renderAsync(<Native.PreventRemoveContext.Provider value={parent}><AndroidNestedRemovalBoundary><Capture/></AndroidNestedRemovalBoundary></Native.PreventRemoveContext.Provider>);
 act(()=>{facade.setPreventRemove('owned-id','retained-route',true);facade.notifyPreventRemove();});
 expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(1);
 act(()=>{facade.setPreventRemove('owned-id','retained-route',false);facade.setPreventRemove('owned-id','retained-route',true);facade.notifyPreventRemove();});
 expect(parent.setPreventRemove.mock.calls.slice(-2)).toEqual([['owned-id','retained-route',false],['owned-id','retained-route',true]]);
 expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(1);
 act(()=>{facade.setPreventRemove('owned-id','retained-route',false);facade.setPreventRemove('successor-id','retained-route',true);facade.notifyPreventRemove();});
 expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(2);
 act(()=>{facade.setPreventRemove('successor-id','moved-route',true);facade.notifyPreventRemove();});
 expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(3);
 const forwarded=parent.setPreventRemove.mock.calls.length;
 act(()=>{facade.setPreventRemove('successor-id','retained-route',false);facade.notifyPreventRemove();});
 expect(parent.setPreventRemove).toHaveBeenCalledTimes(forwarded);expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(3);
 act(()=>{facade.setPreventRemove('successor-id','moved-route',false);facade.notifyPreventRemove();facade.notifyPreventRemove();});
 expect(parent.notifyPreventRemove).toHaveBeenCalledTimes(4);
 await view.unmountAsync();
});


test('actual native-stack replacement retires only a hidden removed route and keeps its successor protected',async()=>{
 const Visibility=React.createContext<'visible'|'hidden'>('visible');
 let navigation:any,oldKey='',prevented:Record<string,unknown>={};
 function ProtectedPage(){
  navigation=Native.useNavigation();oldKey=Native.useRoute().key;
  return <React.Activity mode={React.useContext(Visibility)}><InnerPage/></React.Activity>;
 }
 function Capture({children}:{children:React.ReactNode}){prevented=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 const layout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><AndroidNestedRemovalBoundary state={state}><Capture>{children}</Capture></AndroidNestedRemovalBoundary>;
 const tree=(mode:'visible'|'hidden')=><Visibility.Provider value={mode}><Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Protected'}]}}><Parent.Navigator layout={layout} screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Protected" component={ProtectedPage}/><Parent.Screen name="Replacement" component={InnerPage}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree></Visibility.Provider>;
 childActions=[];
 const view=await renderAsync(tree('visible'));
 await waitFor(()=>expect(prevented[oldKey]).toEqual({preventRemove:true}));
 const originalKey=oldKey;
 for(let cycle=0;cycle<2;cycle++){
  await view.rerenderAsync(tree('hidden'));
  expect(navigation.getState().routes.some((route:any)=>route.key===originalKey)).toBe(true);
  expect(prevented[originalKey]).toEqual({preventRemove:true});
  await view.rerenderAsync(tree('visible'));
  expect(oldKey).toBe(originalKey);
  await waitFor(()=>expect(prevented[originalKey]).toEqual({preventRemove:true}));
 }
 await act(async()=>navigation.goBack());
 expect(navigation.getState().routes.at(-1)?.key).toBe(originalKey);expect(childActions).toHaveLength(1);
 await view.rerenderAsync(tree('hidden'));
 await act(async()=>navigation.dispatch(Native.StackActions.replace('Replacement')));
 const successor=mockOwnedRoutes.get('Replacement')!;
 expect(successor.navigation.getState().routes.some((route:any)=>route.key===originalKey)).toBe(false);
 await waitFor(()=>expect(prevented[originalKey]).toBeUndefined());
 expect(prevented[successor.route.key]).toEqual({preventRemove:true});
 await act(async()=>successor.navigation.goBack());
 expect(successor.navigation.getState().routes.at(-1)?.key).toBe(successor.route.key);expect(childActions).toHaveLength(2);
 await view.unmountAsync();
});


test('whole retained native navigator preserves original removal ownership through hide and reveal',async()=>{
 const Visibility=React.createContext<'visible'|'hidden'>('visible');
 const ownedLayout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><AndroidNestedRemovalBoundary state={state}>{children}</AndroidNestedRemovalBoundary>;
 function WholeNested(){
  parentKey=Native.useRoute().key;parentNavigation=Native.useNavigation();
  parentPrevention=Native.usePreventRemoveContext().preventedRoutes;
  return <AndroidNestedRemovalBoundary><React.Activity mode={React.useContext(Visibility)}><Inner.Navigator layout={ownedLayout} screenOptions={{headerShown:false}}><Inner.Screen name="Inner" component={InnerPage}/></Inner.Navigator></React.Activity></AndroidNestedRemovalBoundary>;
 }
 const tree=(mode:'visible'|'hidden')=><Visibility.Provider value={mode}><Registration.Provider value={{prevent:true,show:true,allow:false}}><Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Nested'}]}}><Parent.Navigator screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Nested" component={WholeNested}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree></Registration.Provider></Visibility.Provider>;
 childActions=[];
 const view=await renderAsync(tree('visible'));const key=parentKey;
 await waitFor(()=>expect(parentPrevention[key]).toEqual({preventRemove:true}));
 for(let cycle=0;cycle<5;cycle++){
  await view.rerenderAsync(tree('hidden'));
  expect(parentNavigation.getState().routes.at(-1)?.key).toBe(key);
  await view.rerenderAsync(tree('visible'));
  await waitFor(()=>expect(parentPrevention[key]).toEqual({preventRemove:true}));
 }
 await act(async()=>parentNavigation.goBack());
 expect(parentNavigation.getState().routes.at(-1)?.key).toBe(key);
 expect(childActions).toHaveLength(1);
 await view.unmountAsync();
});




test('native-QA removal diagnostics remain finite, public and read-only in a dev=false bundle',async()=>{
 const info=jest.spyOn(console,'info').mockImplementation(()=>{});
 const dev=(globalThis as any).__DEV__;(globalThis as any).__DEV__=false;
 const parent={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{'secret-key':{preventRemove:true}}};
 const state:any={key:'secret-state-key',routes:[{key:'secret-key',name:'not-a-public-route',params:{id:'secret-filter-id',input:'secret-input'}}]};
 let facade!:ReturnType<typeof Native.usePreventRemoveContext>;
 function Capture(){facade=Native.usePreventRemoveContext();return <View/>;}
 const tree=(diagnostics?:'guard-routes')=><Native.PreventRemoveContext.Provider value={{...parent}}><AndroidNestedRemovalBoundary diagnostics={diagnostics} state={state}><Capture/></AndroidNestedRemovalBoundary></Native.PreventRemoveContext.Provider>;
 try{
  const view=await renderAsync(tree());expect(info).not.toHaveBeenCalled();
  await view.rerenderAsync(tree('guard-routes'));
  act(()=>{facade.setPreventRemove('secret-owner-id','secret-key',true);facade.notifyPreventRemove();});
  for(let turn=0;turn<300;turn++)await view.rerenderAsync(tree('guard-routes'));
  expect(info).toHaveBeenCalledTimes(256);
  const rows=info.mock.calls.map(([line])=>JSON.parse((line as string).replace('LavaNavigationRemovalTrace ','')));
  expect(rows[0]).toMatchObject({boundary:'guard-routes',publishedProtectedRoutes:1,nativeRouteCount:1,routes:['other']});
  expect(rows.at(-1)).toMatchObject({events:256,ownedRegistrations:1,ownedProtectedRoutes:1,setCalls:1,setChanges:1,notifyCalls:1,forwardedNotifications:1});
  const text=JSON.stringify(rows);for(const secret of ['secret-key','secret-state-key','secret-filter-id','secret-input','secret-owner-id','not-a-public-route'])expect(text).not.toContain(secret);
  expect(parent.setPreventRemove.mock.calls).toEqual([['secret-owner-id','secret-key',true],['secret-owner-id','secret-key',false]]);expect(parent.notifyPreventRemove).not.toHaveBeenCalled();
  expect(facade.preventedRoutes).toEqual({'secret-key':{preventRemove:true}});
  await view.unmountAsync();
 }finally{(globalThis as any).__DEV__=dev;info.mockRestore();}
});


test('owned native-stack registry publishes protection without populating the core passive-sync registry',async()=>{
 let core:Record<string,unknown>={},owned:Record<string,unknown>={},navigation:any,key='';
 function CoreProbe({children}:{children:React.ReactNode}){core=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 function OwnedProbe({children}:{children:React.ReactNode}){owned=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 function Protected(){navigation=Native.useNavigation();key=Native.useRoute().key;Native.usePreventRemove(true,({data})=>childActions.push(data.action));return <View/>;}
 const layout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><CoreProbe><AndroidNestedRemovalBoundary state={state}><OwnedProbe>{children}</OwnedProbe></AndroidNestedRemovalBoundary></CoreProbe>;
 childActions=[];
 const view=await renderAsync(<Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Protected'}]}}><Parent.Navigator layout={layout} screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Protected" component={Protected}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree>);
 await waitFor(()=>expect(owned[key]).toEqual({preventRemove:true}));
 expect(core).toEqual({});
 await act(async()=>navigation.goBack());
 expect(navigation.getState().routes.at(-1)?.key).toBe(key);expect(childActions).toHaveLength(1);
 expect(owned[key]).toEqual({preventRemove:true});expect(core).toEqual({});
 await view.unmountAsync();
});


test('owned map validates actual routes and fences same-ID successor cleanup while publishing only final changes',async()=>{
 let core:Record<string,unknown>={},coreValue!:ReturnType<typeof Native.usePreventRemoveContext>,owned!:ReturnType<typeof Native.usePreventRemoveContext>,navigation:any;let commits=0;
 function CoreProbe({children}:{children:React.ReactNode}){coreValue=Native.usePreventRemoveContext();core=coreValue.preventedRoutes;return <>{children}</>;}
 function OwnedProbe({children}:{children:React.ReactNode}){owned=Native.usePreventRemoveContext();commits++;return <>{children}</>;}
 function Page(){navigation=Native.useNavigation();return <View/>;}
 const layout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><CoreProbe><AndroidNestedRemovalBoundary state={state}><OwnedProbe>{children}</OwnedProbe></AndroidNestedRemovalBoundary></CoreProbe>;
 const view=await renderAsync(<Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'A'},{name:'B'}]}}><Parent.Navigator layout={layout} screenOptions={{headerShown:false}}><Parent.Screen name="A" component={Page}/><Parent.Screen name="B" component={Page}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree>);
 const [a,b]=navigation.getState().routes.map((route:any)=>route.key);
 expect(()=>owned.setPreventRemove('invalid-owner','absent-route',true)).toThrow("Couldn't find a route");
 act(()=>{owned.setPreventRemove('first',a,true);owned.setPreventRemove('second',a,true);owned.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({[a]:{preventRemove:true}});expect(core).toEqual({});
 const stable=owned,baseline=commits;
 // Real NativeStack NavigationContent still owns its installed provider and
 // every-commit passive synchronization. Repeat unchanged child admissions,
 // absent teardown, and the real core notification without a mocked setter.
 act(()=>{for(let turn=0;turn<100;turn++){
  owned.setPreventRemove('first',a,true);owned.setPreventRemove('second',a,true);
  owned.setPreventRemove('absent-child',a,false);owned.notifyPreventRemove();coreValue.notifyPreventRemove();
 }});
 expect(commits).toBe(baseline);expect(owned.setPreventRemove).toBe(stable.setPreventRemove);
 expect(owned.preventedRoutes).toEqual({[a]:{preventRemove:true}});expect(core).toEqual({});
 act(()=>{owned.setPreventRemove('first',a,false);owned.setPreventRemove('first',a,true);owned.notifyPreventRemove();});
 expect(commits).toBe(baseline);expect(core).toEqual({});
 act(()=>{owned.setPreventRemove('first',b,true);owned.setPreventRemove('first',a,false);owned.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({[a]:{preventRemove:true},[b]:{preventRemove:true}});expect(core).toEqual({});
 act(()=>{owned.setPreventRemove('second',a,false);owned.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({[b]:{preventRemove:true}});
 // Actual router retirement independently removes an abandoned registration.
 await act(async()=>navigation.reset({index:0,routes:[{name:'A',key:a}]}));
 await waitFor(()=>expect(owned.preventedRoutes).toEqual({}));expect(core).toEqual({});
 await view.unmountAsync();
});


test('owned provider replacement fences late old publication and preserves the new consumer scope',async()=>{
 const first={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{}},second={setPreventRemove:jest.fn(),notifyPreventRemove:jest.fn(),preventedRoutes:{}};
 const state:any={key:'navigator',routes:[{key:'A',name:'A'},{key:'B',name:'B'}]};
 let owned!:ReturnType<typeof Native.usePreventRemoveContext>;
 function Capture(){owned=Native.usePreventRemoveContext();return <View/>;}
 const tree=(parent:typeof first)=><Native.PreventRemoveContext.Provider value={parent}><AndroidNestedRemovalBoundary state={state}><Capture/></AndroidNestedRemovalBoundary></Native.PreventRemoveContext.Provider>;
 const view=await renderAsync(tree(first));const old=owned;
 act(()=>{old.setPreventRemove('same-id','A',true);old.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({A:{preventRemove:true}});
 await view.rerenderAsync(tree(second));const fresh=owned;
 act(()=>{fresh.setPreventRemove('same-id','B',true);fresh.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({B:{preventRemove:true}});
 act(()=>{old.setPreventRemove('same-id','A',false);old.notifyPreventRemove();});
 expect(owned.preventedRoutes).toEqual({B:{preventRemove:true}});
 expect(second.setPreventRemove.mock.calls).toEqual([['same-id','B',true],['same-id','B',false]]);
 expect(second.notifyPreventRemove).not.toHaveBeenCalled();
 await view.unmountAsync();
});

test('two owned native navigator maps retain real ancestor protection through child disposal and fresh admission',async()=>{
 let outerCore:Record<string,unknown>={},innerCore:Record<string,unknown>={};
 function OuterProbe({children}:{children:React.ReactNode}){outerCore=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 function InnerProbe({children}:{children:React.ReactNode}){innerCore=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 const innerLayout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><InnerProbe><AndroidNestedRemovalBoundary state={state}>{children}</AndroidNestedRemovalBoundary></InnerProbe>;
 function OwnedNested(){
  parentKey=Native.useRoute().key;parentNavigation=Native.useNavigation();parentPrevention=Native.usePreventRemoveContext().preventedRoutes;
  return <AndroidNestedRemovalBoundary>{React.useContext(Registration).show?<Inner.Navigator layout={innerLayout} screenOptions={{headerShown:false}}><Inner.Screen name="Inner" component={InnerPage}/></Inner.Navigator>:<View/>}</AndroidNestedRemovalBoundary>;
 }
 const outerLayout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><OuterProbe><AndroidNestedRemovalBoundary state={state}>{children}</AndroidNestedRemovalBoundary></OuterProbe>;
 const tree=(show=true,allow=false)=><Registration.Provider value={{show,allow,prevent:true}}><Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Nested'}]}}><Parent.Navigator layout={outerLayout} screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Nested" component={OwnedNested}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree></Registration.Provider>;
 childActions=[];const view=await renderAsync(tree());const key=parentKey;
 await waitFor(()=>expect(parentPrevention[key]).toEqual({preventRemove:true}));expect(outerCore).toEqual({});expect(innerCore).toEqual({});
 await act(async()=>parentNavigation.goBack());
 expect(parentNavigation.getState().routes.at(-1)?.key).toBe(key);expect(childActions).toHaveLength(1);
 await view.rerenderAsync(tree(false));await waitFor(()=>expect(parentPrevention[key]).toBeUndefined());expect(outerCore).toEqual({});expect(innerCore).toEqual({});
 await view.rerenderAsync(tree(true,true));await waitFor(()=>expect(parentPrevention[key]).toEqual({preventRemove:true}));
 await act(async()=>parentNavigation.goBack());
 expect(parentNavigation.getState().routes.at(-1)?.name).toBe('Root');expect(childActions).toHaveLength(2);expect(childActions[1].type).toBe('GO_BACK');expect(outerCore).toEqual({});expect(innerCore).toEqual({});
 await view.unmountAsync();
});


test('an actual independent owned child tree never publishes protection into the surrounding owned navigator',async()=>{
 const {AndroidIndependentNavigation}=require('../canonical/review/independent-navigation.android') as typeof import('../canonical/review/independent-navigation.android');
 let outerMap:Record<string,unknown>={},innerMap:Record<string,unknown>={},outer:any,inner:any,outerKey='',innerKey='';
 function OuterProbe({children}:{children:React.ReactNode}){outerMap=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 function InnerProbe({children}:{children:React.ReactNode}){innerMap=Native.usePreventRemoveContext().preventedRoutes;return <>{children}</>;}
 const outerLayout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><AndroidNestedRemovalBoundary state={state}><OuterProbe>{children}</OuterProbe></AndroidNestedRemovalBoundary>;
 const innerLayout=({state,children}:{state:import('@react-navigation/native').NavigationState;children:React.ReactNode})=><AndroidNestedRemovalBoundary state={state}><InnerProbe>{children}</InnerProbe></AndroidNestedRemovalBoundary>;
 function Protected(){inner=Native.useNavigation();innerKey=Native.useRoute().key;Native.usePreventRemove(true,({data})=>childActions.push(data.action));return <View/>;}
 function Independent(){
  outer=Native.useNavigation();outerKey=Native.useRoute().key;
  return <AndroidNestedRemovalBoundary><AndroidIndependentNavigation><Native.NavigationContainer initialState={{index:1,routes:[{name:'InnerRoot'},{name:'Protected'}]}}><Inner.Navigator layout={innerLayout} screenOptions={{headerShown:false}}><Inner.Screen name="InnerRoot" component={EmptyPage}/><Inner.Screen name="Protected" component={Protected}/></Inner.Navigator></Native.NavigationContainer></AndroidIndependentNavigation></AndroidNestedRemovalBoundary>;
 }
 childActions=[];const view=await renderAsync(<Native.NavigationIndependentTree><Native.NavigationContainer initialState={{index:1,routes:[{name:'Root'},{name:'Independent'}]}}><Parent.Navigator layout={outerLayout} screenOptions={{headerShown:false}}><Parent.Screen name="Root" component={EmptyPage}/><Parent.Screen name="Independent" component={Independent}/></Parent.Navigator></Native.NavigationContainer></Native.NavigationIndependentTree>);
 await waitFor(()=>expect(innerMap[innerKey]).toEqual({preventRemove:true}));
 expect(outerMap[outerKey]).toBeUndefined();
 await act(async()=>inner.goBack());expect(inner.getState().routes.at(-1)?.key).toBe(innerKey);expect(childActions).toHaveLength(1);
 await act(async()=>outer.goBack());expect(outer.getState().routes.at(-1)?.name).toBe('Root');expect(childActions).toHaveLength(1);
 await view.unmountAsync();
});
