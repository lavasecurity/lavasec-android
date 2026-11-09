import {act,fireEvent,render,screen,waitFor} from '@testing-library/react-native';
import {AppState,DeviceEventEmitter,NativeModules,type AppStateStatus} from 'react-native';
import type {PlusDestination,PlusReason,PlusResume} from '../canonical/review/plus-intents';
import type {ProductSnapshot} from '../src/productContract';
import type {AppSnapshot} from '../canonical/app/contract';
import {productFixture} from './productFixture';
import {AppStore} from '../canonical/app/store';
import {Alert} from '../canonical/app/presentation';

const mockNavigation={navigate:jest.fn(),setOptions:jest.fn(),getState:jest.fn(),dispatch:jest.fn(),goBack:jest.fn()};
const mockUpgrade=jest.fn();
let mockStore:AppStore;
let mockFocused=true;
let mockActualPlus=false;
let mockUpgradeRoute={key:'upgrade-route',params:{reason:'filters' as PlusReason,intent:''}};
let mockReturnBoundary:{finish:(destination:PlusDestination|void)=>void;wait:Promise<void>};
const mockAppStateListeners=new Set<(state:AppStateStatus)=>void>();
jest.mock('@react-navigation/native',()=>({useNavigation:()=>mockNavigation,useIsFocused:()=>mockFocused,usePreventRemove:jest.fn(),useRoute:()=>mockUpgradeRoute,
  StackActions:{replace:(name:string,params:unknown)=>({type:'REPLACE',payload:{name,params}})}}));
jest.mock('../canonical/review/plus-entry',()=>({usePlusEntry:()=>{
  if(!mockActualPlus)return mockUpgrade;
  const entry=jest.requireActual('../canonical/review/plus-entry').usePlusEntry();
  const boundary=mockReturnBoundary;
  return (reason:PlusReason,resume?:PlusResume)=>entry(reason,resume?async()=>{
    const destination=await resume();
    // Park only delivery after the real Library success, before the actual Plus wrapper
    // captures/returns it to Upgrade. No native operation or UI action is replayed.
    boundary.finish(destination);await boundary.wait;return destination;
  }:undefined);
}}));
jest.mock('../canonical/review/ReviewContext',()=>{
  const {initialPreviewDraft}=require('../canonical/review/preview-model');
  const useReview=()=>{
    const live=require('react').useSyncExternalStore(mockStore.subscribe,()=>mockStore.getSnapshot().snapshot);
    return {app:mockStore,live,session:live?.session,draft:initialPreviewDraft(),savedDraft:initialPreviewDraft(),setSession:jest.fn(),setDraft:jest.fn()};
  };
  return {useReview,useOptionalReview:useReview,useRouteBodyConcealed:()=>false};
});
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
const port={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const {androidRuntimeForHost}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
const {LibraryScreen}=require('../canonical/review/FilterScreens') as typeof import('../canonical/review/FilterScreens');
const {UpgradeScreen}=require('../canonical/review/SettingsScreens') as typeof import('../canonical/review/SettingsScreens');
const {PlusIntentProvider}=require('../canonical/review/plus-intents') as typeof import('../canonical/review/plus-intents');
const deferred=<T,>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(yes=>{resolve=yes;});return {promise,resolve};};
type Request={type:string;id?:string;retirementLease?:string;retirementGeneration?:string};

async function fixture(plus=false,actualPlus=false){
  const form=deferred<void>(),cancelPublication=deferred<void>(),finished=deferred<PlusDestination|void>(),delivery=deferred<void>();
  mockActualPlus=actualPlus;mockReturnBoundary={finish:finished.resolve,wait:delivery.promise};
  let revision=1,cancelFails=false,mutationPending=false;
  const drafts=new Set(['created-A']);
  let wire={...productFixture,nativeHostID:'library-host',generation:'7',foreground:true,backgroundPrivacyCoverRequired:false,
    libraryEditing:{active:true,hasChanges:false,deletions:[]},
    ...(actualPlus?{security:{unavailable:false,readRevision:1,hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]}}:{}),
    limits:{maxAllowedDomains:25,maxBlockedDomains:25,maxFilterRules:100,maxFilters:3,allowsCustomBlocklists:true,allowsCustomDNS:true},
    plus:{enabled:!plus,busy:false,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},
    ...(plus?{filters:{...productFixture.filters,rows:Array.from({length:3},(_,i)=>({...productFixture.filters.rows[0]!,id:`saved-${i}`}))}}:{})} as ProductSnapshot&Pick<AppSnapshot,'plus'|'limits'>;
  const requests:Request[]=[],admissionRefusals:string[]=[];
  const created=()=>({...wire,revision:++revision,foregroundFlow:null,
    newFilter:{id:'created-A',name:'New filter',emoji:'🌿',lists:[],empty:true,blockedDomainCount:0,allowedExceptionCount:0},
    filterSession:{id:'created-A',lease:'lease-A',name:'New filter',editing:true,lists:[],savedLists:[],blocked:[],allowed:[],savedBlocked:[],savedAllowed:[],names:{}}});
  const reply=(value:unknown)=>JSON.stringify({status:'completed',value,snapshot:wire});
  port.getSnapshot.mockImplementation(async()=>JSON.stringify(wire));
  port.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(wire));
  port.command.mockImplementation(async(raw:string)=>{
    const request=JSON.parse(raw) as Request;requests.push(request);
    if(request.type==='library.form'){await form.promise;wire=created();return reply('created-A');}
    if(request.type==='library.cancel'){
      mutationPending=true;
      // The real native mutation lane remains held through asynchronous publication.
      // Local session teardown has already happened before the parked reply.
      wire={...wire,revision:++revision,libraryEditing:{active:false,hasChanges:false,deletions:[]}};
      try{await cancelPublication.promise;return cancelFails?JSON.stringify({status:'rejected',reason:'operation-failed'}):reply(null);}
      finally{mutationPending=false;}
    }
    if(request.type==='navigation.authorize'){
      // Native module dispatch may admit cancellation first even when JS fast-lanes
      // authorization. Keep the real authorization request; model only native admission.
      await Promise.resolve();
      if(mutationPending){admissionRefusals.push(request.type);return JSON.stringify({status:'rejected',reason:'busy'});}
      return reply(null);
    }
    if(request.type==='filter.cancel'){
      if(request.id==='created-A'&&request.retirementLease==='lease-A'&&request.retirementGeneration==='7')drafts.delete('created-A');
      return reply(null);
    }
    return reply(null);
  });
  const source=androidRuntimeForHost('library-host');
  mockStore=new AppStore(source,JSON.parse(await source.getSnapshot()));
  const disconnect=mockStore.connect();await act(async()=>{});
  await act(async()=>{mockStore.completePresentationLayout(mockStore.getPresentationHydration().epoch);});
  await waitFor(()=>expect(mockStore.getPresentationHydration().required).toBe(false));
  let showUpgrade=false;
  const tree=()=>actualPlus?<PlusIntentProvider retirementKey="original-library"><LibraryScreen/>{showUpgrade&&<UpgradeScreen/>}</PlusIntentProvider>:<LibraryScreen/>;
  const view=render(tree());
  const start=async()=>{
    await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
    let plusResult:Promise<unknown>|undefined;
    if(plus&&actualPlus){
      await waitFor(()=>expect(mockNavigation.navigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'filters',intent:expect.any(String)})));
      mockUpgradeRoute={key:'upgrade-route',params:mockNavigation.navigate.mock.calls.at(-1)![1]};showUpgrade=true;
      wire={...wire,revision:++revision,plus:{...wire.plus,enabled:true}};
      await act(async()=>{DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire));view.rerender(tree());});
      await waitFor(()=>expect({requests:requests.map(row=>row.type),route:mockUpgradeRoute,authority:mockStore.getPresentationAuthority(),hydration:mockStore.getPresentationHydration(),plus:mockStore.getSnapshot().snapshot?.plus.enabled}).toEqual(expect.objectContaining({requests:expect.arrayContaining(['library.form'])})));
    }else if(plus){mockFocused=false;view.rerender(<LibraryScreen/>);await act(async()=>{});plusResult=(mockUpgrade.mock.calls.at(-1)![1] as ()=>Promise<unknown>)();}
    await act(async()=>form.resolve());
    await waitFor(()=>expect({requests:requests.map(row=>row.type),authority:mockStore.getPresentationAuthority(),hydration:mockStore.getPresentationHydration()}).toEqual(expect.objectContaining({requests:expect.arrayContaining(['library.cancel'])})));
    return {plusResult};
  };
  const settle=async()=>{await act(async()=>cancelPublication.resolve());};
  const successor=async(fields:Pick<Partial<ProductSnapshot>,'generation'|'nativeHostID'>={})=>{
    wire={...wire,...fields,revision:++revision,filterSession:{...wire.filterSession!,id:'created-B',lease:'lease-B'},newFilter:{...wire.newFilter!,id:'created-B'}};
    drafts.add('created-B');await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire)));
  };
  const abandon=async(kind:'background'|'closed')=>{
    await act(async()=>{
      if(kind==='closed'){showUpgrade=false;view.rerender(tree());}
      else{AppState.currentState='background';for(const listener of [...mockAppStateListeners])listener('background');}
    });
  };
  return {view,requests,admissionRefusals,drafts,start,settle,successor,finished:finished.promise,
    deliver:()=>act(async()=>delivery.resolve()),abandon,failCancel:()=>{cancelFails=true;},
    close:async()=>{cancelPublication.resolve();form.resolve();delivery.resolve();await view.unmountAsync();disconnect();}};
}
beforeEach(()=>{
  jest.clearAllMocks();mockFocused=true;mockActualPlus=false;mockAppStateListeners.clear();AppState.currentState='active';
  jest.spyOn(AppState,'addEventListener').mockImplementation((_type,listener)=>{
    const callback=listener as (state:AppStateStatus)=>void;mockAppStateListeners.add(callback);
    return {remove:()=>{mockAppStateListeners.delete(callback);}};
  });
});
afterEach(()=>jest.restoreAllMocks());

test('created Filter navigation waits for actual native cancellation publication before fast-lane authorization',async()=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture();
  try{
    await f.start();
    await act(async()=>{});
    expect({authorization:f.requests.filter(row=>row.type==='navigation.authorize'),busy:f.admissionRefusals,alerts:alerts.mock.calls}).toEqual({authorization:[],busy:[],alerts:[]});
    expect(mockNavigation.navigate).not.toHaveBeenCalled();
    await f.settle();await waitFor(()=>expect(mockNavigation.navigate).toHaveBeenCalledWith('Filter',{id:'created-A'}));
    expect(f.requests.map(row=>row.type)).toEqual(['library.form','library.cancel','navigation.authorize']);
    expect(f.requests.filter(row=>row.type==='filter.open')).toEqual([]);expect(alerts).not.toHaveBeenCalled();
    expect(f.drafts.has('created-A')).toBe(true);
  }finally{await f.close();}
});

test('failed cancellation preserves its error and retires only the captured created draft without navigating',async()=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture();
  try{
    f.failCancel();await f.start();await f.settle();
    await waitFor(()=>expect(f.drafts.has('created-A')).toBe(false));
    expect(f.requests.filter(row=>row.type==='filter.cancel')).toEqual([expect.objectContaining({id:'created-A',retirementLease:'lease-A',retirementGeneration:'7'})]);
    expect(f.requests.filter(row=>row.type==='navigation.authorize')).toEqual([]);expect(mockNavigation.navigate).not.toHaveBeenCalled();
    expect(alerts).toHaveBeenCalledWith('Lava','This action could not be completed. Try again.');
  }finally{await f.close();}
});

test.each(['unmounted','graph','host'] as const)('retired Library %s cancellation uses its pre-await lease and cannot discard the successor draft',async retirement=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture();
  try{
    await f.start();
    if(retirement==='unmounted')await f.view.unmountAsync();
    await f.successor(retirement==='graph'?{generation:'8'}:retirement==='host'?{nativeHostID:'successor-host'}:{});
    await f.settle();
    await waitFor(()=>expect(f.drafts.has('created-A')).toBe(false));
    expect(f.drafts.has('created-B')).toBe(true);
    expect(f.requests.filter(row=>row.type==='filter.cancel')).toEqual([expect.objectContaining({id:'created-A',retirementLease:'lease-A',retirementGeneration:'7'})]);
    if(retirement!=='host')expect(mockStore.getSnapshot().snapshot?.filterSession).toEqual({id:'created-B',lease:'lease-B'});
    expect(mockNavigation.navigate).not.toHaveBeenCalled();expect(alerts).not.toHaveBeenCalled();
  }finally{await f.close();}
});

test('current unfocused Plus create settles cancellation and retains its existing destination shape',async()=>{
  const f=await fixture(true);
  try{
    const {plusResult}=await f.start();expect(f.requests.filter(row=>row.type==='navigation.authorize')).toEqual([]);
    await f.settle();await expect(plusResult).resolves.toEqual({name:'Filter',params:{id:'created-A',retirementGeneration:'7',retirementLease:'lease-A'}});
    expect(f.requests.map(row=>row.type)).toEqual(['library.form','library.cancel']);expect(mockNavigation.navigate).not.toHaveBeenCalled();
  }finally{await f.close();}
});


test.each(['background','closed'] as const)('successful finalized Plus create abandoned by %s retires its captured draft, never a newer visible session',async kind=>{
  const alerts=jest.spyOn(Alert,'alert'),f=await fixture(true,true);
  try{
    await f.start();await f.settle();
    await f.finished;
    expect(mockStore.getSnapshot().snapshot?.libraryEditing?.active).toBe(false);
    expect(f.requests.filter(row=>row.type==='library.form')).toHaveLength(1);
    expect(f.requests.filter(row=>row.type==='library.cancel')).toHaveLength(1);
    expect(mockNavigation.dispatch).not.toHaveBeenCalled();
    // The source has completed successfully. Only its delivery is parked while the
    // actual same-host/G store receives another draft, then Upgrade closes/backgrounds.
    await f.successor();await f.abandon(kind);await f.deliver();
    await waitFor(()=>expect(f.drafts.has('created-A')).toBe(false));
    expect(f.requests.filter(row=>row.type==='filter.cancel')).toEqual([expect.objectContaining({id:'created-A',retirementGeneration:'7',retirementLease:'lease-A'})]);
    expect(f.drafts.has('created-B')).toBe(true);
    expect(mockStore.getSnapshot().snapshot?.filterSession).toEqual({id:'created-B',lease:'lease-B'});
    expect(mockNavigation.dispatch).not.toHaveBeenCalled();expect(mockNavigation.goBack).not.toHaveBeenCalled();
    expect(alerts).not.toHaveBeenCalled();
  }finally{await f.close();}
});

test('current finalized Plus destination reaches the real Upgrade replace once with its original retirement receipt',async()=>{
  const f=await fixture(true,true);
  try{
    await f.start();await f.settle();await f.finished;await f.deliver();
    await waitFor(()=>expect(mockNavigation.dispatch).toHaveBeenCalledWith({type:'REPLACE',payload:{name:'Filter',params:{id:'created-A',retirementGeneration:'7',retirementLease:'lease-A'}}}));
    expect(mockNavigation.dispatch).toHaveBeenCalledTimes(1);
    expect(f.requests.filter(row=>row.type==='filter.cancel')).toEqual([]);
    expect(f.requests.filter(row=>row.type==='library.form')).toHaveLength(1);
    expect(f.requests.filter(row=>row.type==='library.cancel')).toHaveLength(1);
    expect(f.drafts.has('created-A')).toBe(true);
  }finally{await f.close();}
});
