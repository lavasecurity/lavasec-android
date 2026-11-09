import React from 'react';
import {AppState,DeviceEventEmitter,NativeModules,View,Text,type AppStateStatus} from 'react-native';
import {act,fireEvent,renderAsync,screen,waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';

// The production tree has an independent setup stack around a tab navigator
// and its route stacks. Keep every real navigator and removal registry here.
// Only page content/native leaves are reduced, so lifecycle admission remains real.
jest.mock('@react-navigation/elements',()=>jest.requireActual('../node_modules/@react-navigation/elements/lib/module/index.js'));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{stopDemo:jest.fn(),getGuardAccents:()=>JSON.stringify({original:{light:'#BF4000',dark:'#FF8855'}}),getLegalNotices:()=>'{"sections":[]}',getBlocklistCatalog:()=> '[]'}}));
let mockPageNavigation:any,mockCurrentApp:any;
const mockRemovalCallbacks=new Map<string,(event:{data:{action:any}})=>void>();
jest.mock('@react-navigation/native',()=>{
 const actual=jest.requireActual('@react-navigation/native');
 return {...actual,usePreventRemove:(prevent:boolean,callback:(event:{data:{action:any}})=>void)=>{
  const route=actual.useRoute();mockRemovalCallbacks.set(route.key,callback);
  return actual.usePreventRemove(prevent,callback);
 }};
});
const mockRouteOwners=new Map<string,{navigation:any;route:any;prevented:Record<string,unknown>}>();
function mockRoutePage(){
 const React=require('react'),{View,Text}=require('react-native');
 const {useNavigation,useRoute,usePreventRemoveContext}=require('@react-navigation/native');
 const navigation=useNavigation(),route=useRoute(),{app,live}=require('../canonical/review/ReviewContext').useReview();
 const {useToolbar}=require('../canonical/review/scaffold');
 mockPageNavigation=navigation;mockCurrentApp=app;
 mockRouteOwners.set(route.name,{navigation,route,prevented:usePreventRemoveContext().preventedRoutes});
 useToolbar({title:route.name},[live?.session]);
 return React.createElement(View,{testID:`lifecycle.${route.name}`},React.createElement(Text,null,route.name));
}
jest.mock('../canonical/review/screens',()=>new Proxy({__esModule:true},{get:(target:any,key:string)=>key in target?target[key]:mockRoutePage}));
// Preserve FilterRoute's real removal registration, retained boundary, identity
// context and filter.close cleanup; reduce only the detail page contents.
jest.mock('../canonical/review/FilterScreens',()=>({FilterScreen:mockRoutePage}));
jest.mock('../canonical/review/OnboardingFlow',()=>({OnboardingFlow:require('react-native').View}));

const port={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const CanonicalApp=require('../CanonicalApp').default as typeof import('../CanonicalApp').default;
const {StackActions}=require('@react-navigation/native') as typeof import('@react-navigation/native');
let current:ProductSnapshot,lifecycle:Set<(state:AppStateStatus)=>void>,frames:Map<number,(time:number)=>void>,nextFrame:number;
const completed=JSON.stringify({status:'completed'});
const commands=()=>port.command.mock.calls.map(([raw])=>JSON.parse(raw) as {type:string;serial:number;ownerID?:string;nativeHostID?:string;id?:string});
const presentations=()=>commands().filter(command=>command.type==='navigation.presentation');
const ready=()=>commands().filter(command=>command.type==='navigation.presentationReady');
const deferred=()=>{let resolve!:(reply:string)=>void;const promise=new Promise<string>(done=>{resolve=done;});return {promise,resolve};};
async function phase(state:AppStateStatus){await act(async()=>{AppState.currentState=state;for(const listener of lifecycle)listener(state);await Promise.resolve();});}
async function emit(patch:Partial<ProductSnapshot>){current={...current,...patch,revision:current.revision+1};await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current)));}
async function flushFrames(){for(let turn=0;turn<20;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const queued=[...frames.values()];frames.clear();await act(async()=>{for(const frame of queued)frame(turn*16);await Promise.resolve();});}}
function measure(){act(()=>{for(const node of screen.UNSAFE_getAllByType(View))if(node.props.onLayout)fireEvent(node,'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});});}
beforeEach(()=>{
 jest.clearAllMocks();mockRouteOwners.clear();mockRemovalCallbacks.clear();AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
 jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(frame=>{frames.set(++nextFrame,frame);return nextFrame;});
 jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
 current={...productFixture,nativeHostID:'lifecycle-host',revision:10,backgroundPrivacyCoverRequired:false};
 port.getSnapshot.mockImplementation(async()=>JSON.stringify(current));port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(current));port.command.mockResolvedValue(completed);
});
afterEach(()=>jest.restoreAllMocks());

// Exercise shipping privateRoute, real stack/tab providers, exact AppStore
// admission and original visited beforeRemove actions. Native leaves alone are mocked.
test('shipping Android removal registration stays owned through hydration and forwards only the live original action',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 const settle=async()=>{measure();await flushFrames();};
 await flushFrames();await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await act(async()=>mockPageNavigation.navigate('Upgrade',{reason:'blockedDomains',intent:'owned-intent'}));await settle();
 const original=mockRouteOwners.get('Upgrade')!;
 expect(original).toBeDefined();
 const registryKeys=Object.keys(original.prevented).sort();
 // A ready route keeps interception ownership; readiness is checked by the
 // callback, rather than removing/recreating nested registry entries.
 expect(registryKeys).toContain(original.route.key);
 const visited:any[]=[];
 const removeListener=original.navigation.addListener('beforeRemove',(event:any)=>visited.push(event.data.action));
 await phase('inactive');
 await act(async()=>original.navigation.goBack());
 expect(original.navigation.getState().routes.at(-1)?.key).toBe(original.route.key);
 expect(Object.keys(mockRouteOwners.get('Upgrade')!.prevented).sort()).toEqual(registryKeys);
 await phase('active');
 expect(mockCurrentApp.getPresentationHydration().required).toBe(true);
 await act(async()=>original.navigation.goBack());
 expect(original.navigation.getState().routes.at(-1)?.key).toBe(original.route.key);
 await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 expect(Object.keys(mockRouteOwners.get('Upgrade')!.prevented).sort()).toEqual(registryKeys);
 for(let revision=0;revision<5;revision++){
  await emit({security:{unavailable:false,readRevision:revision+2,ownerRevision:'7:0',sourceRevision:'same-source',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}});
  await settle();
  expect(Object.keys(mockRouteOwners.get('Upgrade')!.prevented).sort()).toEqual(registryKeys);
 }
 const dispatch=jest.spyOn(original.navigation,'dispatch');
 await act(async()=>original.navigation.goBack());await settle();
 expect(original.navigation.getState().routes.at(-1)?.name).toBe('Filter');
 expect(visited).toHaveLength(3);
 expect(dispatch).toHaveBeenCalledWith(visited[2]);
 // React Navigation's internal visited-route metadata must survive exactly;
 // replacing the action would re-enter the same prevention listener.
 expect(dispatch.mock.calls.at(-1)?.[0]).toBe(visited[2]);
 removeListener();dispatch.mockRestore();
 await view.unmountAsync();
},15_000);

test('a queued callback cannot adopt a replacement graph and a removed route cannot pop its successor',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 const settle=async()=>{measure();await flushFrames();};
 await flushFrames();await settle();
 await act(async()=>mockPageNavigation.navigate('Upgrade',{reason:'blockedDomains',intent:'owned-intent'}));await settle();
 const owner=mockRouteOwners.get('Upgrade')!,old=mockRemovalCallbacks.get(owner.route.key)!;
 const action={type:'GO_BACK',source:owner.route.key,target:owner.navigation.getState().key};
 const dispatch=jest.spyOn(owner.navigation,'dispatch');
 const accepted=mockCurrentApp.getSnapshot();
 const rebound=jest.spyOn(mockCurrentApp,'getSnapshot').mockReturnValue({...accepted,snapshot:{...accepted.snapshot,nativeHostID:'replacement-host'}});
 await act(async()=>old({data:{action}}));
 expect(dispatch).not.toHaveBeenCalled();
 expect(owner.navigation.getState().routes.at(-1)?.key).toBe(owner.route.key);
 rebound.mockRestore();
 await emit({generation:'8'});await settle();
 await act(async()=>old({data:{action}}));
 expect(dispatch).not.toHaveBeenCalled();
 expect(owner.navigation.getState().routes.at(-1)?.key).toBe(owner.route.key);
 // A newly hydrated graph on this same native host receives its own current
 // callback. The queued graph-7 callback must not prevent graph-8 user Back.
 expect(mockRemovalCallbacks.get(owner.route.key)).not.toBe(old);
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>owner.navigation.goBack());await settle();
 expect(owner.navigation.getState().routes.at(-1)?.name).toBe('Guard');
 dispatch.mockClear();
 await act(async()=>old({data:{action}}));
 expect(dispatch).not.toHaveBeenCalled();
 expect(owner.navigation.getState().routes.at(-1)?.name).toBe('Guard');
 dispatch.mockRestore();await view.unmountAsync();
},15_000);

test('a native acknowledged license modal retires its exact stack while hydration is covered and never pops a successor',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 const settle=async()=>{measure();await flushFrames();};
 await flushFrames();await settle();
 await act(async()=>mockPageNavigation.navigate('Legal'));await settle();
 const legal=mockRouteOwners.get('Legal')!;
 await emit({foregroundFlow:{id:'owned-license',kind:'licenses',name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[],notices:'Complete license text'}});
 await waitFor(()=>expect(screen.getByText('Complete license text')).toBeTruthy());await settle();
 expect(legal.navigation.getState().routes.at(-1)?.name).toBe('Foreground');
 // Revocation starts a fresh layout epoch. Native acknowledgment is read-free
 // cleanup of only the original modal, even if its credential graph retired.
 await phase('inactive');await phase('active');
 expect(mockCurrentApp.getPresentationHydration().required).toBe(true);
 await emit({generation:'8',foregroundFlow:null,foregroundClosing:['owned-license']});
 await waitFor(()=>expect(legal.navigation.getState().routes.at(-1)?.name).toBe('Legal'));
 await settle();
 await emit({foregroundClosing:['owned-license'],catalog:{...current.catalog!,fresh:false}});await settle();
 expect(legal.navigation.getState().routes.at(-1)?.key).toBe(legal.route.key);
 await view.unmountAsync();
},15_000);

test('a reused foreground route key refuses the prior owner callback and admits only the fresh owner acknowledgment',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 const settle=async()=>{measure();await flushFrames();};
 await flushFrames();await settle();
 await act(async()=>mockPageNavigation.navigate('Legal'));await settle();
 const legal=mockRouteOwners.get('Legal')!;
 const flow=(id:string)=>({id,kind:'licenses' as const,name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[],notices:`Complete license ${id}`});
 await emit({foregroundFlow:flow('license-A')});
 await waitFor(()=>expect(screen.getByText('Complete license license-A')).toBeTruthy());await settle();
 const stack=legal.navigation.getState(),routeA=stack.routes.at(-1)!;
 expect(routeA.name).toBe('Foreground');
 const old=mockRemovalCallbacks.get(routeA.key)!;
 const action={type:'GO_BACK',source:routeA.key,target:stack.key};
 await emit({foregroundFlow:flow('license-B'),foregroundClosing:['license-A']});
 await waitFor(()=>expect(screen.getByText('Complete license license-B')).toBeTruthy());await settle();
 const routeB=legal.navigation.getState().routes.at(-1)!;
 // NAVIGATE to the same native-stack screen reuses its key but changes owner.
 expect(routeB.key).toBe(routeA.key);
 expect(routeB.params.id).toBe('license-B');
 expect(mockRemovalCallbacks.get(routeB.key)).not.toBe(old);
 await act(async()=>old({data:{action}}));await settle();
 expect(legal.navigation.getState().routes.at(-1)?.params?.id).toBe('license-B');
 expect(screen.getByText('Complete license license-B')).toBeTruthy();
 // B's actual native acknowledgment still closes its current exact route.
 await emit({foregroundFlow:null,foregroundClosing:['license-A','license-B']});
 await waitFor(()=>expect(legal.navigation.getState().routes.at(-1)?.key).toBe(legal.route.key));
 await view.unmountAsync();
},15_000);

test('bootstrap without a graph binds only the first real native visit and then permits its original action',async()=>{
 const {NavigationContainer,useNavigation,useRoute}=require('@react-navigation/native') as typeof import('@react-navigation/native');
 const {createNativeStackNavigator}=require('@react-navigation/native-stack') as typeof import('@react-navigation/native-stack');
 const {ReviewContext}=require('../canonical/review/ReviewContext') as typeof import('../canonical/review/ReviewContext');
 const {useAndroidRouteRemoval}=require('../canonical/review/android-route-removal') as typeof import('../canonical/review/android-route-removal');
 const Stack=createNativeStackNavigator();
 let live={nativeHostID:'bootstrap-host'} as import('../canonical/app/contract').AppSnapshot;
 const app={getSnapshot:()=>({snapshot:live}),subscribe:()=>()=>{},getPresentationAuthority:()=>true,getPresentationHydration:()=>({required:false})};
 const owners=new Map<string,any>();
 function Page(){const navigation=useNavigation(),route=useRoute();useAndroidRouteRemoval();owners.set(route.name,navigation);return <Text>{route.name}</Text>;}
 const tree=()=> <ReviewContext.Provider value={{app,live} as unknown as import('../canonical/review/ReviewContext').ReviewState}><NavigationContainer><Stack.Navigator><Stack.Screen name="Home" component={Page}/><Stack.Screen name="Upgrade" component={Page}/></Stack.Navigator></NavigationContainer></ReviewContext.Provider>;
 const view=await renderAsync(tree());
 await act(async()=>owners.get('Home').navigate('Upgrade'));
 const navigation=owners.get('Upgrade');
 await act(async()=>navigation.goBack());
 expect(navigation.getState().routes.at(-1)?.name).toBe('Upgrade');
 live={...live,generation:'7'};await view.rerenderAsync(tree());
 await act(async()=>navigation.goBack());
 expect(navigation.getState().routes.at(-1)?.name).toBe('Home');
 await view.unmountAsync();
});

test('Review Back retires its exact preparation before forwarding the original removal action',async()=>{
 const {NavigationContainer,useNavigation,useRoute}=require('@react-navigation/native') as typeof import('@react-navigation/native');
 const {createNativeStackNavigator}=require('@react-navigation/native-stack') as typeof import('@react-navigation/native-stack');
 const {ReviewContext}=require('../canonical/review/ReviewContext') as typeof import('../canonical/review/ReviewContext');
 const {useAndroidRouteRemoval}=require('../canonical/review/android-route-removal') as typeof import('../canonical/review/android-route-removal');
 const Stack=createNativeStackNavigator(),retirement=deferred();
 const live={nativeHostID:'preparation-host',generation:'7',preparation:{id:'native-attempt'}} as import('../canonical/app/contract').AppSnapshot;
 const app={getSnapshot:()=>({snapshot:live}),subscribe:()=>()=>{},getPresentationAuthority:()=>true,
   getPresentationHydration:()=>({required:false}),retirePreparation:jest.fn(()=>retirement.promise)};
 const owners=new Map<string,any>();
 function Page(){const navigation=useNavigation(),route=useRoute();useAndroidRouteRemoval();owners.set(route.name,navigation);return <Text>{route.name}</Text>;}
 const view=await renderAsync(<ReviewContext.Provider value={{app,live} as unknown as import('../canonical/review/ReviewContext').ReviewState}>
   <NavigationContainer><Stack.Navigator><Stack.Screen name="Filter" component={Page}/><Stack.Screen name="Review" component={Page}/></Stack.Navigator></NavigationContainer>
 </ReviewContext.Provider>);
 await act(async()=>owners.get('Filter').navigate('Review'));
 const navigation=owners.get('Review');
 await act(async()=>navigation.goBack());
 expect(app.retirePreparation).toHaveBeenCalledWith('native-attempt');
 expect(navigation.getState().routes.at(-1)?.name).toBe('Review');
 await act(async()=>retirement.resolve(completed));
 expect(navigation.getState().routes.at(-1)?.name).toBe('Filter');
 expect(navigation.getState().routes).toHaveLength(1);
 await view.unmountAsync();
});
