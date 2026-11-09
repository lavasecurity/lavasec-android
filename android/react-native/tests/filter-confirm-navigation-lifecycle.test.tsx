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
jest.mock('../canonical/review/screens',()=>new Proxy({__esModule:true},{get:(target:any,key:string)=>key in target?target[key]:['ReviewScreen','AddDomainScreen','AddBlocklistScreen'].includes(key)?jest.requireActual('../canonical/review/FilterScreens')[key]:mockRoutePage}));
// Keep shipping FilterRoute/FilterScreen/ReviewScreen and picker pages: real removal
// registration, read readiness, exact owner identity and filter.close lifecycle.
jest.mock('../canonical/review/OnboardingFlow',()=>({OnboardingFlow:require('react-native').View}));

const port={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const CanonicalApp=require('../CanonicalApp').default as typeof import('../CanonicalApp').default;
let current:ProductSnapshot,lifecycle:Set<(state:AppStateStatus)=>void>,frames:Map<number,(time:number)=>void>,nextFrame:number;
const completed=JSON.stringify({status:'completed'});
const commands=()=>port.command.mock.calls.map(([raw])=>JSON.parse(raw) as {type:string;serial:number;ownerID?:string;nativeHostID?:string;id?:string});
const presentations=()=>commands().filter(command=>command.type==='navigation.presentation');
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


const fixtureDomain='ui-save-owned.invalid';
const session=(editing:boolean,saved=false):NonNullable<ProductSnapshot['filterSession']>=>({
 id:'filter1',lease:'owned-draft-1',name:'My filter',editing,lists:['source1'],savedLists:['source1'],blocked:[fixtureDomain],allowed:[],savedBlocked:saved?[fixtureDomain]:[],savedAllowed:[],names:{source1:'Block List Basic'},
});
const editing=(changed:boolean):NonNullable<ProductSnapshot['filterEditing']>=>({canSave:changed,reviewCanConfirm:changed,validation:null,refreshing:false,lists:[{id:'source1',pending:false,undo:false}],blocked:[{id:fixtureDomain,pending:changed,undo:false}],allowed:[]});
const nativeUpdate=(patch:Partial<ProductSnapshot>)=>{
 current={...current,...patch,revision:current.revision+1};
 DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));
};
async function settle(){measure();await flushFrames();await act(async()=>{await Promise.resolve();});}

// This complete scenario crosses six picker visits, two real host roots and native cleanup.
// Keep the ordinary waitFor bounds; only the whole scenario has a measured 15-second budget.
test.each([false,true])('shipping Filter Confirm and native cleanup retain removal ownership through host reopen (hidden Activity=%s)',async(hiddenActivity)=>{
 current={...current,security:{unavailable:false,readRevision:1,ownerRevision:'7:0',sourceRevision:'baseline',displayClearRevision:'0',hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]},filterSession:session(true),filterEditing:editing(true)};
 port.command.mockImplementation(async(raw:string)=>{
  const command=JSON.parse(raw);
  if(command.nativeHostID!==current.nativeHostID)return JSON.stringify({status:'rejected',reason:'host-retired'});
  if(command.type==='catalog.query')return JSON.stringify({status:'completed',value:{sections:[{title:'Core',sources:[{id:'source1',name:'Block List Basic',licenseName:'GPL3',sourceURL:'https://example.invalid/source',metadata:'10 rules'}]}],count:84,budget:500000,pending:0,exceeded:false,summary:'84/500000',fraction:84/500000,indeterminate:false,atOrOverBudget:false}});
  if(command.type==='filter.save')return JSON.stringify({status:'completed',value:'review'});
  if(command.type==='filter.review')return JSON.stringify({status:'completed',value:'owned-review'});
  if(command.type==='filter.apply'){
   expect(command.review).toBe('owned-review');expect(current.filterSession?.editing).toBe(true);
   nativeUpdate({filterSession:{...current.filterSession!,editing:false,savedBlocked:[...current.filterSession!.blocked]},filterEditing:editing(false),security:{...current.security!,sourceRevision:'committed-'+current.revision}});
  }
  if(command.type==='filter.edit')nativeUpdate({filterSession:{...current.filterSession!,editing:true}});
  if(command.type==='filter.domain')nativeUpdate({filterSession:{...current.filterSession!,blocked:[]},filterEditing:{...editing(true),blocked:[]}});
  return completed;
 });
 const tree=(hostID:string,mode:'visible'|'hidden'='visible')=><React.Activity mode={mode}><CanonicalApp nativeHostID={hostID}/></React.Activity>;
 let view=await renderAsync(tree('lifecycle-host'));
 const hideAndReveal=async(hostID:string)=>{
  if(!hiddenActivity)return;
  await view.rerenderAsync(tree(hostID,'hidden'));
  await view.rerenderAsync(tree(hostID));await settle();
  await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 };
 await flushFrames();measure();await flushFrames();
 await waitFor(()=>expect(mockCurrentApp.getPresentationHydration().required).toBe(false));
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Save'}).props.accessibilityState.disabled).toBe(false));
 for(let visit=0;visit<6;visit++){
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Add a blocklist'})));await settle();
  await waitFor(()=>expect(screen.getByRole('button',{name:/Block List Basic.*GPL3/}).props.accessibilityState.selected).toBe(true));
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:/Block List Basic.*GPL3/})));await settle();
  await waitFor(()=>expect(screen.getByRole('button',{name:/Block List Basic.*GPL3/}).props.accessibilityState.selected).toBe(false));
  await act(async()=>mockPageNavigation.goBack());await settle();
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save'})).toBeTruthy());
  expect(current.filterSession!.lists).toEqual(['source1']);
 }
 await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Confirm changes'}).props.accessibilityState.disabled).toBe(false));
 await settle();
 await hideAndReveal('lifecycle-host');
 await waitFor(()=>expect(screen.getByRole('button',{name:'Confirm changes'}).props.accessibilityState.disabled).toBe(false));
 await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Confirm changes'})));await settle();
 await waitFor(()=>expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(1));
 await waitFor(()=>expect(screen.queryByRole('button',{name:'Confirm changes'})).toBeNull());
 await settle();await hideAndReveal('lifecycle-host');
 const userReviewCaptures=commands().filter(command=>command.type==='filter.review').length;
 expect(userReviewCaptures).toBeGreaterThanOrEqual(1);
 expect(current.filterSession?.savedBlocked).toEqual([fixtureDomain]);
 const savedSession=current.filterSession, savedEditing=current.filterEditing;
 await phase('inactive');await emit({foreground:false,filterSession:undefined,filterEditing:undefined});await view.unmountAsync();
 AppState.currentState='active';
 current={...current,foreground:true,filterSession:savedSession,filterEditing:savedEditing,nativeHostID:'successor-host',revision:current.revision+1};
 view=await renderAsync(tree('successor-host'));await flushFrames();measure();await flushFrames();
 await act(async()=>mockPageNavigation.navigate('Filter',{id:'filter1'}));await settle();
 await waitFor(()=>expect(screen.getByRole('button',{name:'Edit'}).props.accessibilityState.disabled).toBe(false));
 await act(async()=>{
  await mockCurrentApp.command({type:'filter.open',id:'filter1'});
  await mockCurrentApp.command({type:'filter.edit',id:'filter1'});
  await mockCurrentApp.command({type:'filter.domain',id:'filter1',domain:fixtureDomain,decision:'blocked',remove:true});
 });await settle();await hideAndReveal('successor-host');
 await act(async()=>{
  expect(await mockCurrentApp.command({type:'filter.save',id:'filter1'})).toBe('review');
  expect(await mockCurrentApp.command({type:'filter.review',id:'filter1'})).toBe('owned-review');
  await mockCurrentApp.command({type:'filter.apply',id:'filter1',review:'owned-review'});
 });await settle();
 expect(commands().filter(command=>command.type==='filter.apply')).toHaveLength(2);
 expect(commands().filter(command=>command.type==='filter.save')).toHaveLength(2);
 expect(commands().filter(command=>command.type==='filter.review')).toHaveLength(userReviewCaptures+1);
 expect(commands().filter(command=>command.type==='filter.close').every(command=>command.id==='filter1')).toBe(true);
 expect(current.filterSession?.savedBlocked).toEqual([]);
 expect(mockCurrentApp.getPresentationHydration().required).toBe(false);
 await view.unmountAsync();
},15_000);
