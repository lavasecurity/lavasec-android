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
function mockRoutePage(){
 const React=require('react'),{View,Text}=require('react-native');
 const {useNavigation,useRoute}=require('@react-navigation/native');
 const navigation=useNavigation(),route=useRoute(),{app,live}=require('../canonical/review/ReviewContext').useReview();
 const {useToolbar}=require('../canonical/review/scaffold');
 mockPageNavigation=navigation;mockCurrentApp=app;
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
 jest.clearAllMocks();AppState.currentState='active';lifecycle=new Set();frames=new Map();nextFrame=0;
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
 jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(frame=>{frames.set(++nextFrame,frame);return nextFrame;});
 jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
 current={...productFixture,nativeHostID:'lifecycle-host',revision:10,backgroundPrivacyCoverRequired:false};
 port.getSnapshot.mockImplementation(async()=>JSON.stringify(current));port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(current));port.command.mockResolvedValue(completed);
});
afterEach(()=>jest.restoreAllMocks());

// Thirty native presentation publications across five retained-route cycles exceed a single
// component test budget; the complete scenario remains bounded at 15 seconds.
test('real nested navigators retain removal ownership through delayed receipts, Plus replacement, native foreground and resume hydration',async()=>{
 const view=await renderAsync(<CanonicalApp nativeHostID="lifecycle-host"/>);
 await flushFrames();
 await waitFor(()=>expect(presentations()).toHaveLength(1));measure();await flushFrames();
 await waitFor(()=>expect(ready()).toHaveLength(1));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));measure();await flushFrames();
 await waitFor(()=>expect(presentations()).toHaveLength(2));
 const old=deferred();let delayed=true;
 port.command.mockImplementation(async(raw:string)=>JSON.parse(raw).type==='navigation.presentation'&&delayed?(delayed=false,old.promise):completed);
 await act(async()=>mockPageNavigation.navigate('Upgrade',{reason:'blockedDomains',intent:'owned-intent'}));
 await waitFor(()=>expect(presentations()).toHaveLength(3));measure();await flushFrames();
 expect(ready()).toHaveLength(2);
 await phase('inactive');await phase('active');
 await waitFor(()=>expect(presentations()).toHaveLength(4));measure();await flushFrames();
 await waitFor(()=>expect(ready()).toHaveLength(3));
 await act(async()=>old.resolve(JSON.stringify({status:'rejected',reason:'background'})));await flushFrames();
 expect(presentations()).toHaveLength(4);
 for(let visit=0;visit<5;visit++){
  await act(async()=>mockPageNavigation.dispatch(StackActions.replace('Review',{id:'filter1'})));
  measure();await flushFrames();
  await emit({foregroundFlow:{id:`owned-${visit}`,kind:'licenses',name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[],notices:'Complete license text'}});
  await waitFor(()=>expect(screen.getByText('Complete license text')).toBeTruthy());measure();await flushFrames();
  await emit({foregroundFlow:null,foregroundClosing:[`owned-${visit}`]});await flushFrames();
  await phase('inactive');await phase('active');measure();await flushFrames();
  await act(async()=>mockPageNavigation.navigate('Upgrade',{reason:'blockedDomains',intent:`intent-${visit}`}));measure();await flushFrames();
 }
 await act(async()=>mockPageNavigation.dispatch(StackActions.popToTop()));measure();await flushFrames();
 await waitFor(()=>expect(commands().filter(command=>command.type==='filter.close')).toEqual([expect.objectContaining({id:'filter1'})]));
 // Four initial publications, five per retained-route cycle, and final pop.
 expect(presentations()).toHaveLength(30);
 expect(commands().filter(command=>command.type==='filter.apply')).toEqual([]);
 expect(commands().filter(command=>command.type==='navigation.presentationReady').every(command=>command.nativeHostID==='lifecycle-host')).toBe(true);
 expect(mockCurrentApp.getPresentationHydration().required).toBe(false);
 await view.unmountAsync();
},15_000);
