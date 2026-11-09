import React from 'react';
import {act,fireEvent,render,screen} from '@testing-library/react-native';
import {AppState,ScrollView,type AppStateStatus} from 'react-native';
import type {AppCommand,AppSnapshot,ForegroundFlow} from '../canonical/app/contract';
import type {Spec} from '../canonical/specs/NativeLavaApp';
import {AppStore} from '../canonical/app/store';
import {projectAndroidSnapshot} from '../canonical/android/runtime';
import {productFixture} from './productFixture';

let mockStore:AppStore;
let mockRoute={key:'form-route',name:'Foreground',params:{id:'form-A'}};
const mockNavigation={setOptions:jest.fn()};
jest.mock('@react-navigation/native',()=>({useNavigation:()=>mockNavigation,useIsFocused:()=>true,useRoute:()=>mockRoute}));
jest.mock('../canonical/review/ReviewContext',()=>{
  const useReview=()=>({app:mockStore,live:require('react').useSyncExternalStore(mockStore.subscribe,()=>mockStore.getSnapshot().snapshot)});
  return {useReview,useOptionalReview:useReview};
});
jest.mock('../canonical/review/scaffold',()=>({...jest.requireActual('../canonical/review/scaffold'),useSheetHeaderInset:()=>0}));
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
import {ForegroundFlowScreen} from '../canonical/review/ForegroundFlowScreen';
import {AndroidPresentationInteractionContext,useAndroidPresentationReady,type AndroidPresentation} from '../canonical/review/presentation-owner.android';
import {LavaActionButton} from '../canonical/src';
import {FormField} from '../canonical/review/form-scaffold';
import {Alert} from '../canonical/app/presentation';

const headerButton=(side:'Left'|'Right')=>{const options=mockNavigation.setOptions.mock.calls.filter(([value])=>typeof value[`header${side}`]==='function').at(-1)![0];return options[`header${side}`]().props.children[0];};
const deferred=<T,>()=>{let resolve!:(value:T)=>void,reject!:(error:Error)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const flow=(kind:ForegroundFlow['kind']='createFilter',id='form-A'):ForegroundFlow=>({id,kind,name:'Original',emoji:'🌿',dismissAttempt:0,canCreate:true,templates:[],groups:[],hasDeletions:true});
const initial=(kind:ForegroundFlow['kind']):AppSnapshot=>{
  const base=projectAndroidSnapshot(productFixture);
  return {...base,revision:1,generation:'7',nativeHostID:'host-A',foreground:true,available:true,backgroundPrivacyCoverRequired:false,
    security:{...base.security,unavailable:false,recoveryRequired:false,protectedSurfaces:[],authenticatedSurfaces:[],ownerRevision:'7',readRevision:1,sourceRevision:'source',displayClearRevision:'clear'},foregroundFlow:flow(kind)};
};
let frames:Map<number,(time:number)=>void>,nextFrame:number,lifecycle:Set<(state:AppStateStatus)=>void>;
beforeEach(()=>{
  jest.clearAllMocks();mockRoute={key:'form-route',name:'Foreground',params:{id:'form-A'}};AppState.currentState='active';frames=new Map();nextFrame=0;lifecycle=new Set();
  jest.spyOn(AppState,'addEventListener').mockImplementation((_type,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
  jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.set(++nextFrame,callback);return nextFrame;});
  jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
});
afterEach(()=>jest.restoreAllMocks());
async function flushFrames(){for(let turn=0;turn<12;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const current=[...frames.values()];frames.clear();await act(async()=>{for(const callback of current)callback(turn*16);await Promise.resolve();});}}
async function fixture(kind:ForegroundFlow['kind']='createFilter'){
  let current=initial(kind),listener:(value:string)=>void=()=>{};
  const acknowledgements:Array<ReturnType<typeof deferred<string>>>=[],submissions:Array<ReturnType<typeof deferred<string>>>=[],requests:AppCommand[]=[];
  const native={getSnapshot:jest.fn(async()=>JSON.stringify(current)),onSnapshot:(callback:(value:string)=>void)=>{listener=callback;return {remove(){listener=()=>{};}};},command:jest.fn((raw:string)=>{
    const command=JSON.parse(raw) as AppCommand;requests.push(command);
    if(command.type==='navigation.presentationReady'){const reply=deferred<string>();acknowledgements.push(reply);return reply.promise;}
    if(command.type==='foreground.submit'){const reply=deferred<string>();submissions.push(reply);return reply.promise;}
    return Promise.resolve(JSON.stringify({snapshot:current,result:command.type==='foreground.identity'?{valid:true,dirty:false,message:''}:null}));
  })};
  mockStore=new AppStore(native as unknown as Spec);const disconnect=mockStore.connect();await act(async()=>mockStore.refresh());
  let presentation:AndroidPresentation={serial:1,routeKey:mockRoute.key,ownerID:'foreground:form-A',surface:'filterEditing',accepted:true};
  function Frame(){const receipt=useAndroidPresentationReady(presentation,true,true);return <AndroidPresentationInteractionContext.Provider value={receipt}><ForegroundFlowScreen/></AndroidPresentationInteractionContext.Provider>;}
  const view=render(<Frame/>);
  const measure=async()=>{await act(async()=>fireEvent(screen.UNSAFE_getByType(ScrollView),'layout',{nativeEvent:{layout:{x:0,y:0,width:320,height:640}}}));await flushFrames();};
  await measure();
  const replace=async(fields:Partial<AppSnapshot>,newOwner=false)=>{
    current={...current,...fields,revision:current.revision+1};
    if(newOwner){mockRoute={...mockRoute,params:{id:current.foregroundFlow!.id}};presentation={...presentation,serial:presentation.serial+1,ownerID:`foreground:${current.foregroundFlow!.id}`};}
    await act(async()=>{listener(JSON.stringify(current));view.rerender(<Frame/>);});if(newOwner)await measure();else await flushFrames();
  };
  const acknowledge=async(index:number)=>{await act(async()=>acknowledgements[index]!.resolve(JSON.stringify({snapshot:current,result:null})));await flushFrames();};
  const submitButton=()=>kind==='renameFilter'?headerButton('Right'):screen.UNSAFE_getAllByType(LavaActionButton).find(node=>node.props.title=== (kind==='deleteFilters'?'Confirm changes':'Create'))!;
  const press=async()=>act(async()=>submitButton().props.onPress());
  const close=async()=>{view.unmount();for(const reply of acknowledgements)reply.resolve(JSON.stringify({snapshot:current,result:null}));for(const reply of submissions)reply.resolve(JSON.stringify({snapshot:current,result:null}));await act(async()=>{});disconnect();};
  return {native,requests,acknowledgements,submissions,replace,acknowledge,submitButton,press,close};
}

test.each(['createFilter','deleteFilters','renameFilter'] as const)('%s mutation waits for actual native Ready while layout and owner entry settle',async kind=>{
  const f=await fixture(kind);
  try{
    expect(f.acknowledgements).toHaveLength(1);expect(mockStore.getPresentationHydration().required).toBe(false);
    expect(f.requests).toContainEqual({type:'foreground.enter',id:'form-A'});
    expect(f.submitButton().props.disabled).toBe(true);await f.press();expect(f.submissions).toHaveLength(0);
    await f.acknowledge(0);expect(f.submitButton().props.disabled).toBe(false);expect(f.submissions).toHaveLength(0);
    await f.press();expect(f.submissions).toHaveLength(1);expect(f.requests.filter(row=>row.type==='foreground.submit')).toEqual([{type:'foreground.submit',id:'form-A',...(kind==='renameFilter'?{name:'Original',emoji:'🌿'}:{})}]);
  }finally{await f.close();}
});

test('clean Close remains read-free while native Ready is parked',async()=>{
  const f=await fixture();
  try{
    const close=headerButton('Left');expect(close.props.disabled).toBe(false);
    await act(async()=>close.props.onPress());expect(f.requests.filter(row=>row.type==='foreground.dismiss')).toEqual([{type:'foreground.dismiss',id:'form-A'}]);
    expect(f.submissions).toHaveLength(0);expect(f.acknowledgements).toHaveLength(1);
  }finally{await f.close();}
});

test.each(['owner','host','revision','graph'] as const)('a saved submit callback and old native Ready cannot borrow the successor %s receipt',async boundary=>{
  const f=await fixture(),saved=f.submitButton().props.onPress;
  try{
    await f.replace(boundary==='owner'?{foregroundFlow:flow('createFilter','form-B')}:boundary==='host'?{nativeHostID:'host-B'}:boundary==='graph'?{generation:'8'}:{security:{...mockStore.getSnapshot().snapshot!.security,readRevision:2}},boundary==='owner');
    expect(f.submitButton().props.disabled).toBe(true);await f.acknowledge(0);
    expect(f.submitButton().props.disabled).toBe(true);await act(async()=>saved());expect(f.submissions).toHaveLength(0);
    expect(f.acknowledgements).toHaveLength(2);await f.acknowledge(1);expect(f.submitButton().props.disabled).toBe(false);
    await act(async()=>saved());expect(f.submissions).toHaveLength(0);await f.press();expect(f.submissions).toHaveLength(1);
  }finally{await f.close();}
});

test.each(['owner','host','revision','graph'] as const)('an old rejected submit cannot paint an inline error after its %s retires',async boundary=>{
  const f=await fixture();
  try{
    await f.acknowledge(0);await f.press();expect(f.submissions).toHaveLength(1);
    await f.replace(boundary==='owner'?{foregroundFlow:flow('createFilter','form-B')}:boundary==='host'?{nativeHostID:'host-B'}:boundary==='graph'?{generation:'8'}:{security:{...mockStore.getSnapshot().snapshot!.security,readRevision:2}},boundary==='owner');
    await f.acknowledge(1);await act(async()=>f.submissions[0]!.reject(new Error('Old form failed.')));
    expect(screen.queryByText('Old form failed.')).toBeNull();
    expect(f.submitButton().props.disabled).toBe(false);
  }finally{await f.close();}
});

test('a current exact Ready submit retains its ordinary inline error',async()=>{
  const f=await fixture();
  try{await f.acknowledge(0);await f.press();await act(async()=>f.submissions[0]!.reject(new Error('Current form failed.')));expect(screen.getByText('Current form failed.')).toBeTruthy();}
  finally{await f.close();}
});


test('rename fields and dirty hints wait for Ready while identity metadata remains available',async()=>{
  const f=await fixture('renameFilter');
  try{
    const field=()=>screen.UNSAFE_getAllByType(FormField).find(node=>node.props.title==='Name')!;
    expect(f.requests).toContainEqual({type:'foreground.identity',id:'form-A',name:'Original',emoji:'🌿'});
    expect(field().props.editable).toBe(false);await act(async()=>field().props.onChangeText('Not admitted'));
    expect(field().props.value).toBe('Original');expect(f.requests.filter(row=>row.type==='foreground.dirty')).toEqual([]);
    await f.acknowledge(0);expect(field().props.editable).toBe(true);await act(async()=>field().props.onChangeText('Current name'));
    expect(field().props.value).toBe('Current name');expect(f.requests).toContainEqual({type:'foreground.dirty',id:'form-A',dirty:true});
  }finally{await f.close();}
});

test('dirty native Back is retained through renewed Ready, confirms once and never lends an old discard callback a new receipt',async()=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture('renameFilter');
  try{
    await f.acknowledge(0);await act(async()=>screen.UNSAFE_getAllByType(FormField).find(node=>node.props.title==='Name')!.props.onChangeText('Unsaved name'));
    await f.replace({security:{...mockStore.getSnapshot().snapshot!.security,readRevision:2},foregroundFlow:{...flow('renameFilter'),dismissAttempt:1}});
    expect(headerButton('Left').props.disabled).toBe(true);expect(alerts).not.toHaveBeenCalled();
    await f.acknowledge(1);expect(alerts).toHaveBeenCalledTimes(1);expect(alerts.mock.calls[0]![0]).toBe('Discard changes?');
    const oldDiscard=alerts.mock.calls[0]![2]!.find(button=>button.text==='Discard Changes')!.onPress!;
    expect(f.requests.filter(row=>row.type==='foreground.dismiss')).toEqual([]);
    await f.replace({security:{...mockStore.getSnapshot().snapshot!.security,readRevision:3}});await f.acknowledge(2);
    await act(async()=>oldDiscard());expect(f.requests.filter(row=>row.type==='foreground.dismiss')).toEqual([]);expect(alerts).toHaveBeenCalledTimes(1);
    await f.replace({foregroundFlow:{...flow('renameFilter'),dismissAttempt:2}});expect(alerts).toHaveBeenCalledTimes(2);
    const currentDiscard=alerts.mock.calls[1]![2]!.find(button=>button.text==='Discard Changes')!.onPress!;
    await act(async()=>currentDiscard());expect(f.requests.filter(row=>row.type==='foreground.dismiss')).toEqual([{type:'foreground.dismiss',id:'form-A'}]);
  }finally{await f.close();}
});

test('native Back counter reset never creates a new request in a retained rename body',async()=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture('renameFilter');
  try{
    await f.acknowledge(0);await act(async()=>screen.UNSAFE_getAllByType(FormField).find(node=>node.props.title==='Name')!.props.onChangeText('Unsaved name'));
    await f.replace({foregroundFlow:{...flow('renameFilter'),dismissAttempt:1}});expect(alerts).toHaveBeenCalledTimes(1);
    await f.replace({foregroundFlow:flow('renameFilter')});expect(alerts).toHaveBeenCalledTimes(1);
    await f.replace({foregroundFlow:{...flow('renameFilter'),dismissAttempt:1}});expect(alerts).toHaveBeenCalledTimes(2);
  }finally{await f.close();}
});
