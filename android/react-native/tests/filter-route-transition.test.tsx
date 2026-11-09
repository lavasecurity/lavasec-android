import {useSyncExternalStore,type PropsWithChildren} from 'react';
import {AppState} from 'react-native';
import {act,renderHook} from '@testing-library/react-native';
import {StackRouter} from '@react-navigation/routers';
import {useFilterRoute} from '../canonical/review/filter-route';
import {ReviewContext,type ReviewState} from '../canonical/review/ReviewContext';
import type {AppSnapshot} from '../canonical/app/contract';
import {AppStore} from '../canonical/app/store';
import type {Spec} from '../canonical/specs/NativeLavaApp';
import {parseProductSnapshot,type ProductSnapshot} from '../src/productContract';
import {projectAndroidSnapshot} from '../canonical/android/runtime';
import {productFixture} from './productFixture';

let mockFocused=true;
let mockNavigation:any;
const mockRoute={key:'review-owned',name:'Review',params:{id:'saved'}};
jest.mock('@react-navigation/native',()=>({
 ...jest.requireActual('@react-navigation/native'),
 useIsFocused:()=>mockFocused,useNavigation:()=>mockNavigation,useRoute:()=>mockRoute,
}));
const deferred=()=>{let resolve!:(value:unknown)=>void;let reject!:(error:Error)=>void;const promise=new Promise<unknown>((done,fail)=>{resolve=done;reject=fail;});return {promise,resolve,reject};};
function setup(restore=false){
 let live={generation:'owned-generation',nativeHostID:'host-A',session:{filterID:'saved',editing:true},filters:[{id:'saved',frozen:false},{id:'other',frozen:false}],filterPreparationPresented:false} as unknown as AppSnapshot;
 let required=false;const listeners=new Set<()=>void>();
 const command=jest.fn().mockResolvedValue(null);
 const app={command,subscribe:(listener:()=>void)=>{listeners.add(listener);return()=>listeners.delete(listener);},getSnapshot:()=>({snapshot:live}),getPresentationAuthority:()=>true,getPresentationHydration:()=>({epoch:1,required}),registerPresentationRead:()=>undefined,settlePresentationRead:()=>{}};
 const wrapper=({children}:PropsWithChildren)=><ReviewContext.Provider value={{app,live} as unknown as ReviewState}>{children}</ReviewContext.Provider>;
 const hook=renderHook(()=>useFilterRoute(restore),{wrapper});
 return {...hook,app,command,snapshot:()=>live,update:(patch:Partial<AppSnapshot>)=>{live={...live,...patch};hook.rerender(undefined);},hydrate:(next:boolean)=>{required=next;act(()=>listeners.forEach(listener=>listener()));}};
}
beforeEach(()=>{AppState.currentState='active';mockFocused=true;mockRoute.key='review-owned';mockRoute.name='Review';mockRoute.params={id:'saved'};mockNavigation={goBack:jest.fn(),getState:()=>({key:'owned-stack',index:1,routes:[{key:'filter-owned'},{key:mockRoute.key}]}),dispatch:jest.fn()};});

test('a retained Android Review retires only its captured route once while native snapshots and hydration settle',()=>{
 const router=StackRouter({initialRouteName:'Guard'});
 const options={routeNames:['Guard','Filter','Review'],routeParamList:{},routeGetIdList:{}};
 let state=router.getInitialState(options);
 state={...state,routes:[{key:'guard-owned',name:'Guard'},{key:'filter-owned',name:'Filter',params:{id:'saved'}},{...mockRoute}],index:2};
 const apply=(action:Parameters<typeof router.getStateForAction>[1])=>{const next=router.getStateForAction(state,action,options);if(next)state=router.getRehydratedState(next,options);};
 mockNavigation={getState:()=>state,dispatch:jest.fn(apply),goBack:jest.fn(()=>apply({type:'GO_BACK',source:mockRoute.key}))};
 const {update,snapshot,hydrate}=setup();
 update({session:{...snapshot().session,editing:false}});
 expect(state.routes.map(route=>route.name)).toEqual(['Guard','Filter']);
 // Native fragment teardown is delayed; the old hook can still receive a current
 // product frame before its focus notification/unmount. Its action must not pop Filter.
 hydrate(true);hydrate(false);
 update({session:{...snapshot().session,filterID:'',editing:false}});
 expect(state.routes.map(route=>route.name)).toEqual(['Guard','Filter']);
 expect(mockNavigation.dispatch).toHaveBeenCalledTimes(1);
 expect(mockNavigation.dispatch).toHaveBeenCalledWith({type:'GO_BACK',source:'review-owned',target:state.key});
});

test('Android restore retains one native request through read-layout and selected-target churn',async()=>{
 mockRoute.name='Filter';mockRoute.key='filter-owned';
 const {command,update,snapshot,hydrate}=setup(true);
 const pending=deferred();command.mockImplementation(()=>pending.promise);
 update({session:{...snapshot().session,filterID:'other'}});
 expect(command).toHaveBeenCalledTimes(1);
 hydrate(true);hydrate(false);
 update({session:{...snapshot().session,filterID:''}});
 expect(command).toHaveBeenCalledTimes(1);
 // A revoked/unfocused React effect never cancels or replaces native work. A
 // late refusal for this exact retained route remains a failure, not an auth replay.
 mockFocused=false;update({});
 await act(async()=>pending.reject(new Error('Authentication cancelled.')));
 mockFocused=true;update({});
 expect(command).toHaveBeenCalledTimes(1);
 expect(mockNavigation.dispatch).toHaveBeenCalledTimes(1);
});


test('late restoration refusal belongs only to its original route and cannot close a successor with the same filter id',async()=>{
 mockRoute.name='Filter';mockRoute.key='old-filter-visit';
 const {command,update,snapshot}=setup(true);const old=deferred(),next=deferred();
 command.mockImplementationOnce(()=>old.promise).mockImplementationOnce(()=>next.promise);
 update({session:{...snapshot().session,filterID:'other'}});
 expect(command).toHaveBeenCalledTimes(1);
 mockRoute.key='new-filter-visit';update({});
 expect(command).toHaveBeenCalledTimes(2);
 await act(async()=>old.reject(new Error('The old native host closed.')));
 expect(mockNavigation.dispatch).not.toHaveBeenCalled();
 update({session:{...snapshot().session,filterID:'saved'}});
 await act(async()=>next.resolve(null));
 expect(mockNavigation.dispatch).not.toHaveBeenCalled();
 expect(command).toHaveBeenCalledTimes(2);
});


test('a same-store same-graph same-route replacement native host gets one fresh restoration and ignores the old host failure',async()=>{
 mockRoute.name='Filter';mockRoute.key='filter-owned';
 const {command,update,snapshot,hydrate}=setup(true);const old=deferred(),next=deferred();
 command.mockImplementationOnce(()=>old.promise).mockImplementationOnce(()=>next.promise);
 update({session:{...snapshot().session,filterID:'other'}});
 expect(command).toHaveBeenCalledTimes(1);
 update({nativeHostID:'host-B'});
 expect(command).toHaveBeenCalledTimes(2);
 hydrate(true);hydrate(false);
 expect(command).toHaveBeenCalledTimes(2);
 await act(async()=>old.reject(new Error('The old native host closed.')));
 expect(mockNavigation.dispatch).not.toHaveBeenCalled();
 update({session:{...snapshot().session,filterID:'saved'}});
 await act(async()=>next.resolve(null));
 expect(mockNavigation.dispatch).not.toHaveBeenCalled();
 expect(command).toHaveBeenCalledTimes(2);
});


test.each([true,false])('native FILTERS-only redaction retains the actual %s detail/review until its grant returns, while admitted deletion still pops',async(restore)=>{
  mockRoute.name=restore?'Filter':'Review';mockRoute.key='protected-owned';mockRoute.params={id:'filter1'};
  const router=StackRouter({initialRouteName:'Guard'}),options={routeNames:['Guard','Filter','Review'],routeParamList:{},routeGetIdList:{}};
  let navState={...router.getInitialState(options),routes:[{key:'guard-owned',name:'Guard'},{...mockRoute}],index:1};
  mockNavigation={getState:()=>navState,goBack:jest.fn(),dispatch:jest.fn(action=>{const next=router.getStateForAction(navState,action,options);if(next)navState=router.getRehydratedState(next,options);})};
  let wire:ProductSnapshot={...productFixture,nativeHostID:'original-host',revision:10,backgroundPrivacyCoverRequired:true,presentationBlocked:false,
    security:{unavailable:false,hasAuthenticationMethod:true,protectedSurfaces:['filterEditing'],authenticatedSurfaces:['filterEditing'],readRevision:1,ownerRevision:'original-owner'},
    filterSession:{id:'filter1',lease:'original-draft-lease',name:'My filter',editing:true,lists:[],savedLists:[],blocked:['retained.invalid'],allowed:[],savedBlocked:[],savedAllowed:[],names:{}},
  };
  const projection=()=>projectAndroidSnapshot(parseProductSnapshot(JSON.stringify(wire)));
  let event!:(value:string)=>void;
  const native={getSnapshot:jest.fn(async()=>JSON.stringify(projection())),onSnapshot:(callback:(value:string)=>void)=>{event=callback;return {remove(){}};},command:jest.fn(async()=>JSON.stringify({snapshot:projection(),result:null}))};
  const store=new AppStore(native as unknown as Spec),disconnect=store.connect();await store.refresh();
  const frames=new Map<number,(time:number)=>void>();let nextFrame=0;
  const request=jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.set(++nextFrame,callback);return nextFrame;});
  const cancel=jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(id=>{if(id!=null)frames.delete(id);});
  const flush=async()=>{for(let turn=0;turn<6;turn++){await act(async()=>{await Promise.resolve();});if(!frames.size)break;const pending=[...frames.values()];frames.clear();await act(async()=>{pending.forEach(frame=>frame(turn*16));});}};
  const wrapper=({children}:PropsWithChildren)=>{const current=useSyncExternalStore(store.subscribe,store.getSnapshot);return <ReviewContext.Provider value={{app:store,live:current.snapshot} as unknown as ReviewState}>{children}</ReviewContext.Provider>;};
  const hook=renderHook(()=>useFilterRoute(restore),{wrapper});
  try{
    await act(async()=>store.completePresentationLayout(store.getPresentationHydration().epoch));await flush();
    expect(hook.result.current).toEqual({id:'filter1',ready:true});
    const original=wire;
    wire={...wire,revision:11,filters:{locked:true,available:false,rows:[]},filterSession:undefined,security:{...wire.security!,readRevision:2,authenticatedSurfaces:[]}};
    act(()=>event(JSON.stringify(projection())));
    // APP_UNLOCK remains admitted. Empty rows are the native privacy projection, not deletion.
    expect(store.getPresentationAuthority()).toBe(true);
    await act(async()=>store.completePresentationLayout(store.getPresentationHydration().epoch));await flush();
    expect(hook.result.current).toEqual({id:'filter1',ready:false});
    expect(navState.routes.map(route=>route.name)).toEqual(['Guard',restore?'Filter':'Review']);
    expect(mockNavigation.dispatch).not.toHaveBeenCalled();expect(native.command).not.toHaveBeenCalled();
    wire={...original,revision:12,security:{...original.security!,readRevision:3}};act(()=>event(JSON.stringify(projection())));await flush();
    expect(hook.result.current).toEqual({id:'filter1',ready:true});
    expect(store.getSnapshot().snapshot?.draft.blocked).toEqual(['retained.invalid']);
    expect(mockNavigation.dispatch).not.toHaveBeenCalled();
    wire={...wire,revision:13,filters:{locked:false,available:true,rows:[]},filterSession:undefined};act(()=>event(JSON.stringify(projection())));await flush();
    expect(hook.result.current).toEqual({id:'filter1',ready:false});
    expect(navState.routes.map(route=>route.name)).toEqual(['Guard']);
    expect(mockNavigation.dispatch).toHaveBeenCalledTimes(1);
    expect(mockNavigation.dispatch).toHaveBeenCalledWith({type:'GO_BACK',source:'protected-owned',target:navState.key});
  }finally{hook.unmount();disconnect();request.mockRestore();cancel.mockRestore();}
});
