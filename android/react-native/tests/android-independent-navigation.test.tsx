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
const mockForeignNotifications=jest.fn();
const mockRemovalCallbacks=new Map<string,(event:{data:{action:any}})=>void>();
jest.mock('@react-navigation/native',()=>{
 const actual=jest.requireActual('@react-navigation/native');
 return {...actual,NavigationIndependentTree:({children}:{children:React.ReactNode})=>{
  const React=require('react'),parent=React.useContext(actual.PreventRemoveContext);
  // Observe the real unrelated parent, preserving all actual registry methods.
  const observed=React.useMemo(()=>parent?{...parent,notifyPreventRemove:()=>{mockForeignNotifications();parent.notifyPreventRemove();}}:undefined,[parent]);
  return React.createElement(actual.PreventRemoveContext.Provider,{value:observed},React.createElement(actual.NavigationIndependentTree,null,children));
 },usePreventRemove:(prevent:boolean,callback:(event:{data:{action:any}})=>void)=>{
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
 jest.clearAllMocks();mockForeignNotifications.mockClear();mockRouteOwners.clear();mockRemovalCallbacks.clear();AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
 jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(frame=>{frames.set(++nextFrame,frame);return nextFrame;});
 jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
 current={...productFixture,nativeHostID:'lifecycle-host',revision:10,backgroundPrivacyCoverRequired:false};
 port.getSnapshot.mockImplementation(async()=>JSON.stringify(current));port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(current));port.command.mockResolvedValue(completed);
});
afterEach(()=>jest.restoreAllMocks());

// Exercise shipping privateRoute, real stack/tab providers, exact AppStore
// admission and original visited beforeRemove actions. Native leaves alone are mocked.
test('independent native setup and runtime isolate foreign notifications while keeping the actual protected Review registry',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 const settle=async()=>{measure();await flushFrames();};
 await flushFrames();await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await act(async()=>mockPageNavigation.navigate('Review',{id:'filter1'}));await settle();
 const review=mockRouteOwners.get('Review')!,callback=mockRemovalCallbacks.get(review.route.key)!;
 const registry=Object.keys(review.prevented).sort();
 expect(registry).toContain(review.route.key);
 const action={type:'GO_BACK',source:review.route.key,target:review.navigation.getState().key};
 await phase('inactive');
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await phase('active');
 expect(mockCurrentApp.getPresentationHydration().required).toBe(true);
 await act(async()=>review.navigation.goBack());
 expect(review.navigation.getState().routes.at(-1)?.key).toBe(review.route.key);
 await settle();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 for(let revision=0;revision<5;revision++){
  await emit({security:{unavailable:false,readRevision:revision+2,ownerRevision:'7:0',sourceRevision:'same-source',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}});await settle();
  expect(Object.keys(mockRouteOwners.get('Review')!.prevented).sort()).toEqual(registry);
 }
 await act(async()=>review.navigation.goBack());await settle();
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Filter');
 const dispatch=jest.spyOn(review.navigation,'dispatch');
 await act(async()=>callback({data:{action}}));
 expect(dispatch).not.toHaveBeenCalled();
 expect(review.navigation.getState().routes.at(-1)?.name).toBe('Filter');
 dispatch.mockRestore();
 await act(async()=>mockRouteOwners.get('Filter')!.navigation.goBack());await settle();
 expect(mockPageNavigation.getState().routes.at(-1)?.name).toBe('Guard');
 // A navigation-independent app has no removal relationship to the setup stack.
 // Child/Tab/Root listeners remain active above; only the foreign parent is isolated.
 expect(mockForeignNotifications).not.toHaveBeenCalled();
 await view.unmountAsync();
},15_000);


test('fold pane resize preserves the visited route and its native presentation owner',async()=>{
 await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 await flushFrames();measure();await flushFrames();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));measure();await flushFrames();
 const route=mockRouteOwners.get('Filter')!.route.key;
 const before=presentations().at(-1);
 expect(before).toBeDefined();expect(before!.serial).toBeGreaterThan(0);
 for(const [width,height] of [[1000,800],[480,800],[800,360],[360,720]]){
  act(()=>fireEvent(screen.getByTestId('lava-window-viewport'),'layout',{nativeEvent:{layout:{x:0,y:0,width,height}}}));
  await flushFrames();
  expect(mockRouteOwners.get('Filter')!.route.key).toBe(route);
  expect(mockPageNavigation.getState().routes.at(-1)?.key).toBe(route);
 }
 expect(presentations().at(-1)).toEqual(before);
});
