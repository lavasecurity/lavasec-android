import React from 'react';
import {Alert as NativeAlert,AppState,DeviceEventEmitter,NativeModules,View,ScrollView,Text,type AppStateStatus} from 'react-native';
import {act,fireEvent,renderAsync,screen,waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
import type {AppSnapshot} from '../canonical/app/contract';
type NativeFixture=Omit<ProductSnapshot,'filterEditing'>&{plus?:AppSnapshot['plus'];limits?:AppSnapshot['limits'];filterEditing?:NonNullable<ProductSnapshot['filterEditing']>&{upgradeReason?:string}};

// The production tree has an independent setup stack around a tab navigator
// and its route stacks. Keep every real navigator and removal registry here.
// Only page content/native leaves are reduced, so lifecycle admission remains real.
jest.mock('react-native/Libraries/ReactNative/FabricUIManager',()=>{
 type MockFabricNode={tag:number;type:string;props:object;children:MockFabricNode[];handle?:any};
 const roots=new Map<number,MockFabricNode[]>();let nativeEventHandler:((target:any,type:string,event:object)=>void)|undefined;
 const manager={createNode:(tag:number,type:string,_root:number,props:object,handle:any)=>({tag,type,props,handle,children:[]} as MockFabricNode),cloneNode:(node:MockFabricNode)=>({...node,children:[...node.children]}),cloneNodeWithNewChildren:(node:MockFabricNode)=>({...node,children:[]}),cloneNodeWithNewChildrenAndProps:(node:MockFabricNode,props:object)=>({...node,props:{...node.props,...props},children:[]}),cloneNodeWithNewProps:(node:MockFabricNode,props:object)=>({...node,props:{...node.props,...props},children:[...node.children]}),createChildSet:()=>[] as MockFabricNode[],appendChild:(parent:MockFabricNode,child:MockFabricNode)=>parent.children.push(child),appendChildToSet:(set:MockFabricNode[],child:MockFabricNode)=>set.push(child),completeRoot:jest.fn((tag:number,nodes:MockFabricNode[])=>roots.set(tag,nodes)),getTree:(tag:number)=>roots.get(tag)??[],measure:(_node:any,callback:any)=>callback(0,0,360,720,0,0),measureInWindow:(_node:any,callback:any)=>callback(0,0,360,720),registerEventHandler:jest.fn(handler=>{nativeEventHandler=handler;}),dispatchNativeEvent:(target:any,type:string,event:object)=>{if(!nativeEventHandler)throw new Error('Fabric event dispatcher has not registered.');nativeEventHandler(target,type,event);},unstable_DiscreteEventPriority:1,unstable_ContinuousEventPriority:4,unstable_IdleEventPriority:16,unstable_getCurrentEventPriority:()=>1};
 return {getFabricUIManager:()=>manager};
});
jest.mock('react-native/Libraries/Renderer/implementations/ReactFabric-dev',()=>{(globalThis as any).nativeFabricUIManager=require('react-native/Libraries/ReactNative/FabricUIManager').getFabricUIManager();return jest.requireActual('react-native/Libraries/Renderer/implementations/ReactFabric-dev');});
jest.mock('react-native/src/private/webapis/dom/nodes/specs/NativeDOM',()=>({__esModule:true,default:{linkRootNode:()=>({}),getBoundingClientRect:()=>[0,0,360,720],getParentNode:()=>null,getChildNodes:()=>[]}}));
jest.mock('react-native/Libraries/Text/Text',()=>{const React=require('react');const Text=React.forwardRef((props:any,ref:any)=>React.createElement('RCTText',{...props,ref}));Text.displayName='Text';return {__esModule:true,default:Text};});
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
let mockTabPrevented:Record<string,unknown>={},mockParentTabKey='';
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
 return {...actual,createBottomTabNavigator:()=>{
  const stack=actual.createBottomTabNavigator(),Navigator=stack.Navigator;
  return {...stack,Navigator:(props:any)=>React.createElement(Navigator,{...props,layout:(layoutProps:any)=>props.layout?props.layout({...layoutProps,children:React.createElement(ObserveParent,null,layoutProps.children)}):React.createElement(ObserveParent,null,layoutProps.children)})};
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
 jest.clearAllMocks();mockOwnedRoutes.clear();mockTabNotifications.length=0;mockTabPrevented={};AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
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

const mockRegisteredFabricLeaves=new Set<string>();
function registerFabricNativeLeaves(){
 const registry=require('react-native/Libraries/Renderer/shims/ReactNativeViewConfigRegistry');
 const base=require('react-native/Libraries/NativeComponent/BaseViewConfig.android').default;
 for(const name of ['View','Text','Image','ScrollView','ActivityIndicator','RCTRefreshControl','AndroidSwipeRefreshLayout','RCTScrollView','RCTScrollContentView','RCTText','RCTRawText','RNSVGSvgViewAndroid','RNSVGGroup','RNSVGPath','RNSVGDefs','RNSVGClipPath','RNSVGLinearGradient','RNSVGRadialGradient','RNSVGRect','RNSVGCircle','RNSVGEllipse','RNSVGLine','RNSVGUse','RNSVGSymbol','RNSVGMask','RNSVGPattern','RNSVGText','RNSVGImage','RNSScreenContentWrapper','RNSScreen','RNSScreenStack','RNSScreenContainer','RNSScreenStackHeaderConfig','RNSScreenStackHeaderSubview','RNCSafeAreaProvider','RNCSafeAreaView'])if(!mockRegisteredFabricLeaves.has(name)){mockRegisteredFabricLeaves.add(name);try{registry.get(name);continue;}catch(error){if(!(error as Error).message.includes('View config getter callback'))throw error;}registry.register(name,()=>({...base,uiViewClassName:name==='Text'?'RCTText':`RCT${name}`,validAttributes:{...base.validAttributes,style:require('react-native/Libraries/Components/View/ReactNativeStyleAttributes').default,testID:true}}));}
}
test('actual Fabric renderer settles nested native-stack removal propagation',async()=>{
 const manager=require('react-native/Libraries/ReactNative/FabricUIManager').getFabricUIManager();
 registerFabricNativeLeaves();
 const fabric=require('react-native/Libraries/Renderer/implementations/ReactFabric-dev');
 const errors:Error[]=[];
 childActions=[];
 try{
  await act(async()=>fabric.render(<ParentTree/>,301,null,true,{onUncaughtError:(error:Error)=>errors.push(error)}));
  expect(errors).toEqual([]);
  await waitFor(()=>expect(parentPrevention[parentKey]).toEqual({preventRemove:true}));
  await act(async()=>parentNavigation.goBack());
  expect(parentNavigation.getState().routes.at(-1)?.name).toBe('Nested');
  expect(childActions).toHaveLength(1);
 }finally{
  await act(async()=>fabric.render(null,301,null,true));

 }
});


test.each([false,true])('shipping contextual Plus uses the actual Fabric synchronous update pipeline (native authentication turn reset: %s)',async(resetAuthenticationTurn)=>{
 registerFabricNativeLeaves();
 const fabric=require('react-native/Libraries/Renderer/implementations/ReactFabric-dev');
 const manager=require('react-native/Libraries/ReactNative/FabricUIManager').getFabricUIManager();
 const errors:Error[]=[];const rootTag=311;
 const nodes=()=>{const result:any[]=[];const visit=(node:any)=>{result.push(node);for(const child of node.children)visit(child);};for(const node of manager.getTree(rootTag))visit(node);return result;};
 const settle=async()=>{for(let turn=0;turn<10;turn++){await act(async()=>{for(const node of nodes()){const props=node.handle?.memoizedProps;if(props?.onLayout)props.onLayout({nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});}await Promise.resolve();});await flushFrames();}};
 const press=async(label:string)=>{const node=nodes().find(node=>node.handle?.memoizedProps?.accessibilityLabel===label);expect(node).toBeDefined();let handle=node.handle;while(handle&&!handle.memoizedProps?.onPress)handle=handle.return;expect(handle?.memoizedProps?.onPress).toEqual(expect.any(Function));await act(async()=>handle.memoizedProps.onPress());};
 current={...current,security:{unavailable:false,readRevision:1,ownerRevision:'7:0',sourceRevision:'baseline',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]},filterSession:session(true),filterEditing:edit(false),plus:{enabled:false,checking:false,busy:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]}};
 port.command.mockImplementation(async(raw:string)=>{
  const command=JSON.parse(raw);
  current={...current,revision:current.revision+1};
  // Actual parent/child departure still retires authentication. A Plus detour
  // stays in the mounted parent's turn, including when replaced by Review.
  if(resetAuthenticationTurn&&command.type==='navigation.endTurn')current={...current,security:{...current.security!,readRevision:(current.security!.readRevision??0)+1}};
  DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));
  if(command.type==='filter.save')return JSON.stringify({status:'completed',value:'review'});
  if(command.type==='filter.review')return JSON.stringify({status:'completed',value:'capacity-review'});
  return completed;
 });
 try{
  await act(async()=>fabric.render(<CanonicalApp nativeHostID="lifecycle-host"/>,rootTag,null,true,{onUncaughtError:(error:Error)=>errors.push(error)}));await settle();
  expect(errors).toEqual([]);
  await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
  await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
  expect(errors).toEqual([]);
  await press('Save');await settle();
  expect(mockOwnedRoutes.get('Upgrade')?.navigation.getState().routes.at(-1)?.name).toBe('Upgrade');
  await emit({plus:{...current.plus!,enabled:true},filterEditing:edit(true)});await settle();
  expect(errors).toEqual([]);
  const review=mockOwnedRoutes.get('Review')!;
  expect(review.navigation.getState().routes.at(-1)?.name).toBe('Review');
  expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
  expect(commands().filter(command=>command.type==='navigation.endTurn')).toHaveLength(0);
  expect(current.security!.readRevision).toBe(1);
  await act(async()=>review.navigation.goBack());await settle();
  expect(review.navigation.getState().routes.at(-1)?.name).toBe('Filter');
  expect(commands().filter(command=>command.type==='navigation.endTurn').length).toBeGreaterThan(0);
  if(resetAuthenticationTurn)expect(current.security!.readRevision).toBeGreaterThan(1);
  expect(errors).toEqual([]);
 }finally{await act(async()=>fabric.render(null,rootTag,null,true));}
},15_000);


test('shipping Fabric returns from blocked and allowed contextual Plus with every projected rule and the original draft intact',async()=>{
 registerFabricNativeLeaves();
 const fabric=require('react-native/Libraries/Renderer/implementations/ReactFabric-dev');
 const manager=require('react-native/Libraries/ReactNative/FabricUIManager').getFabricUIManager();
 const rootTag=321,errors:Error[]=[];
 const alerts:{title:string;buttons:NonNullable<Parameters<typeof NativeAlert.alert>[2]>}[]=[];
 jest.spyOn(NativeAlert,'alert').mockImplementation((title,_message,buttons)=>{alerts.push({title:title??'',buttons:buttons??[]});});
 const nodes=()=>{const result:any[]=[];const visit=(node:any)=>{result.push(node);for(const child of node.children)visit(child);};for(const node of manager.getTree(rootTag))visit(node);return result;};
 const settle=async()=>{for(let turn=0;turn<10;turn++){await act(async()=>{for(const node of nodes()){const props=node.handle?.memoizedProps;if(props?.onLayout)props.onLayout({nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});}await Promise.resolve();});await flushFrames();}};
 const control=(label:string)=>{const node=nodes().find(node=>node.handle?.memoizedProps?.accessibilityLabel===label);expect(node).toBeDefined();let handle=node.handle;while(handle&&!handle.memoizedProps?.onPress)handle=handle.return;expect(handle?.memoizedProps?.onPress).toEqual(expect.any(Function));return handle;};
 const press=async(label:string)=>{const handle=control(label);expect(handle.memoizedProps.disabled).not.toBe(true);const node=nodes().find(node=>node.handle?.memoizedProps?.accessibilityLabel===label);expect(node.handle.memoizedProps.onClick).toEqual(expect.any(Function));await act(async()=>manager.dispatchNativeEvent(node.handle,'topClick',{target:node.tag}));};
 type Decision='blocked'|'allowed';
 let decision:Decision='blocked',caseIndex=0;
 const freeLimits={maxAllowedDomains:25,maxBlockedDomains:25,maxFilterRules:500000,maxFilters:3,allowsCustomBlocklists:false,allowsCustomDNS:false};
 const plusLimits={maxAllowedDomains:1000,maxBlockedDomains:1000,maxFilterRules:2000000,maxFilters:50,allowsCustomBlocklists:true,allowsCustomDNS:true};
 const reviewKeys=new Set<string>();
 const baseline=()=>current.filterSession!;
 const projectedRows=(ids:string[],saved:string[],editing:boolean)=>([...ids,...(editing?saved:[])]).filter((id,index,all)=>all.indexOf(id)===index).map(id=>({id,pending:!ids.includes(id),undo:editing&&ids.includes(id)!==saved.includes(id)}));
 const editingFacts=(plus:boolean,editing=true):NonNullable<NativeFixture['filterEditing']>=>{
  const owned=baseline(),reason=decision==='blocked'?'blockedDomains':'allowedDomains';
  return {canSave:editing,reviewCanConfirm:editing&&plus,validation:editing&&!plus?reason:null,upgradeReason:editing&&!plus?reason:undefined,refreshing:false,
   lists:projectedRows(owned.lists,owned.savedLists,editing),blocked:projectedRows(owned.blocked,owned.savedBlocked,editing),allowed:projectedRows(owned.allowed,owned.savedAllowed,editing)};
 };
 const stage=(next:Decision)=>{
  decision=next;caseIndex++;
  const other=next==='blocked'?'allowed':'blocked';
  const capacity=Array.from({length:26},(_,index)=>`capacity-${next}-${index}.invalid`);
  const staged=`staged-${other}.invalid`;
  current={...current,filterSession:{...session(true),lease:`capacity-draft-${caseIndex}`,blocked:next==='blocked'?capacity:[staged],allowed:next==='allowed'?capacity:[staged],savedBlocked:next==='blocked'?capacity:[],savedAllowed:next==='allowed'?capacity:[]},
   plus:{enabled:false,checking:false,busy:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},
   limits:freeLimits};
  current={...current,filterEditing:{...editingFacts(false),canSave:true,validation:null,upgradeReason:undefined}};
 };
 const packets:{id:string;lease:string|undefined;value:string;readRevision:number|undefined}[]=[];
 port.command.mockImplementation(async(raw:string)=>{
  const command=JSON.parse(raw);
  if(command.type==='navigation.endTurn')current={...current,security:{...current.security!,readRevision:(current.security!.readRevision??0)+1}};
  if(command.type==='filter.cancel'){
   expect(command.id).toBe('filter1');
   const owned=baseline();current={...current,filterSession:{...owned,editing:false,lease:undefined,blocked:[...owned.savedBlocked],allowed:[...owned.savedAllowed]}};
   current={...current,filterEditing:editingFacts(false,false)};
  }
  const saveCapacity=command.type==='filter.save'&&!current.plus?.enabled;
  if(saveCapacity)current={...current,filterEditing:{...editingFacts(false),canSave:false}};
  current={...current,revision:current.revision+1};DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));
  if(command.type==='filter.save'){
   expect(command.id).toBe('filter1');
   // A fresh native review discovers capacity even when the pre-tap projection
   // has not published that refusal. No reviewed token or durable write exists.
   return JSON.stringify({status:'completed',value:saveCapacity?{upgradeReason:decision==='blocked'?'blockedDomains':'allowedDomains'}:'review'});
  }
  if(command.type==='filter.review'){
   expect(command.id).toBe('filter1');const value=`capacity-review-${caseIndex}-${packets.length+1}`;
   packets.push({id:command.id,lease:baseline().lease,value,readRevision:current.security?.readRevision});
   return JSON.stringify({status:'completed',value});
  }
  return completed;
 });
 current={...current,security:{unavailable:false,readRevision:1,ownerRevision:'7:0',sourceRevision:'baseline',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}};
 stage('blocked');
 try{
  await act(async()=>fabric.render(<CanonicalApp nativeHostID="lifecycle-host"/>,rootTag,null,true,{onUncaughtError:(error:Error)=>errors.push(error)}));await settle();
  await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
  await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
  const filterRouteKey=mockOwnedRoutes.get('Filter')!.route.key;
  for(const next of ['blocked','allowed'] as const){
   if(next==='allowed'){stage(next);await emit({filterSession:current.filterSession,filterEditing:current.filterEditing,plus:current.plus,limits:current.limits});await settle();}
   const original=baseline(),savedBlocked=[...original.savedBlocked],savedAllowed=[...original.savedAllowed];
   const lease=original.lease,packetStart=packets.length,saveStart=commands().filter(command=>command.type==='filter.save').length;
   expect(nodes().filter(node=>node.handle?.memoizedProps?.testID?.startsWith(`filter.rule.${next}.`))).toHaveLength(26);
   expect(mockOwnedRoutes.get('Filter')!.route.key).toBe(filterRouteKey);
   await press('Save');await settle();
   const upgrade=mockOwnedRoutes.get('Upgrade')!;
   expect(upgrade.navigation.getState().routes.at(-1)?.name).toBe('Upgrade');
   expect(upgrade.route.params.reason).toBe(next==='blocked'?'blockedDomains':'allowedDomains');
   await emit({plus:{...current.plus!,enabled:true},limits:plusLimits,filterEditing:editingFacts(true)});await settle();
   const review=mockOwnedRoutes.get('Review')!,state=review.navigation.getState();
   expect(state.routes.at(-1)).toMatchObject({name:'Review',params:{id:'filter1',standaloneReview:''}});
   expect(state.routes.filter((route:any)=>route.name==='Review')).toHaveLength(1);
   expect(reviewKeys.has(review.route.key)).toBe(false);reviewKeys.add(review.route.key);
   expect(state.routes.some((route:any)=>route.name==='Upgrade')).toBe(false);
   expect(control('Confirm changes').memoizedProps.disabled).not.toBe(true);
   expect(commands().filter(command=>command.type==='filter.save')).toHaveLength(saveStart+2);
   expect(packets.length).toBeGreaterThan(packetStart);
   expect(packets.slice(packetStart).every(packet=>packet.id==='filter1'&&packet.lease===lease&&!!packet.value)).toBe(true);
   expect(baseline().savedBlocked).toEqual(savedBlocked);expect(baseline().savedAllowed).toEqual(savedAllowed);
   expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);
   // The packaged journey revokes the controlled entitlement before closing the
   // still-mounted Review, then cancels through Filter's actual toolbar consent.
   await emit({plus:{...current.plus!,enabled:false},limits:freeLimits});await settle();
   expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
   await press('Close');await settle();
   expect(review.navigation.getState().routes.at(-1)).toMatchObject({name:'Filter',key:filterRouteKey});
   await press('Cancel editing');await settle();
   const discard=alerts.at(-1);expect(discard?.title).toBe('Discard changes?');
   const confirmed=discard!.buttons.find(button=>button.text==='Discard');expect(confirmed?.onPress).toEqual(expect.any(Function));
   await act(async()=>confirmed!.onPress!());await settle();
   expect(baseline().editing).toBe(false);expect(baseline().lease).toBeUndefined();
   expect(baseline().blocked).toEqual(savedBlocked);expect(baseline().allowed).toEqual(savedAllowed);
   expect(mockOwnedRoutes.get('Filter')!.route.key).toBe(filterRouteKey);
   expect(review.navigation.getState().routes.at(-1)).toMatchObject({name:'Filter',key:filterRouteKey});
   expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(0);expect(errors).toEqual([]);
  }
  expect(alerts.map(alert=>alert.title)).toEqual(['Discard changes?','Discard changes?']);
  expect(commands().filter(command=>command.type==='filter.cancel')).toHaveLength(2);
  expect(reviewKeys.size).toBe(2);expect(new Set(packets.map(packet=>packet.lease))).toEqual(new Set(['capacity-draft-1','capacity-draft-2']));
 }finally{await act(async()=>fabric.render(null,rootTag,null,true));}
},15_000);
