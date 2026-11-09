import {AppState,Platform} from 'react-native';
import {act,renderHook} from '@testing-library/react-native';
import {useReviewNavigation} from '../canonical/review/navigation';
import {androidReviewRouteAllowed,androidUnavailableScreenRoutes,isCapabilityAvailable,routeAllowed,SYSTEM_CAPABILITIES} from '../src/systemCapabilities';
const mockNavigate=jest.fn();
const mockCommand=jest.fn();
let mockPresentationAuthority=true;
let mockHydrationRequired=false;
jest.mock('@react-navigation/native',()=>({
  useNavigation:()=>({navigate:mockNavigate}),useIsFocused:()=>true,usePreventRemove:jest.fn(),
}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({app:{command:mockCommand,getSnapshot:()=>({snapshot:{}}),getPresentationAuthority:()=>mockPresentationAuthority,getPresentationHydration:()=>({epoch:1,required:mockHydrationRequired})},session:{protectedActions:{}}})}));
jest.mock('../canonical/app/presentation',()=>({Alert:{alert:jest.fn()}}));
const originalOS=Platform.OS;
const originalAppState=AppState.currentState;
beforeEach(()=>{AppState.currentState='active';mockPresentationAuthority=true;mockHydrationRequired=false;});
afterEach(()=>{Platform.OS=originalOS;AppState.currentState=originalAppState;jest.clearAllMocks();});
test('Android security now renders the shared screen behind credential authorization',async()=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('Security'));
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'credentials'});
  expect(mockCommand).not.toHaveBeenCalledWith({type:'native.flow',flow:'security'});
  expect(mockNavigate).toHaveBeenCalledWith('Security');
});
test('iOS security preserves canonical credential authorization and navigation',async()=>{
  Platform.OS='ios';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('Security'));
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'credentials'});
  expect(mockNavigate).toHaveBeenCalledWith('Security');
});

test.each(['Filters','Library','Filter','Review'] as const)('Android %s asks for the native filter grant before navigating',async name=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate(name));
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'filterEditing'});
  expect(mockNavigate).toHaveBeenCalledWith(name);
});

test.each([
  ['Activity','activityViewing'],['TopDomains','activityViewing'],['History','activityViewing'],
  ['Stats','activityViewing'],['Network','activityViewing'],
  ['Privacy','appSettings'],['Customization','appSettings'],['Account','appSettings'],
] as const)('Android %s renders the shared screen behind its access surface instead of a native handoff',async(name,surface)=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate(name));
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface});
  expect(mockCommand).not.toHaveBeenCalledWith({type:'native.flow',flow:expect.anything()});
  expect(mockNavigate).toHaveBeenCalledWith(name);
});

test('Android Feedback starts its native owner before the shared Foreground route is mounted',async()=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('Feedback'));
  expect(mockCommand).toHaveBeenCalledWith({type:'native.flow',flow:'feedback'});
  expect(mockNavigate).not.toHaveBeenCalled();
});

test('contextual Plus only registers return work after the Upgrade push is authorized',async()=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  const params=jest.fn(()=>({reason:'customDNS' as const}));
  result.current.navigateToUpgrade(params);
  await act(async()=>{});
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'appSettings'});
  expect(mockNavigate).toHaveBeenCalledWith('Upgrade',{reason:'customDNS'});
  expect(params).toHaveBeenCalledTimes(1);
});

test('a cancelled Upgrade authorization never computes its return work',async()=>{
  Platform.OS='android';mockCommand.mockRejectedValue(new Error('Authentication cancelled.'));
  const {result}=renderHook(()=>useReviewNavigation());
  const params=jest.fn(()=>({reason:'customDNS' as const}));
  result.current.navigateToUpgrade(params);
  await act(async()=>{});
  expect(params).not.toHaveBeenCalled();
  expect(mockNavigate).not.toHaveBeenCalled();
});

test('Android Legal opens the searchable shared catalog without launching a native flow',async()=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('Legal'));
  expect(mockNavigate).toHaveBeenCalledWith('Legal');
  expect(mockCommand).not.toHaveBeenCalled();
});

test('iOS Legal still renders the shared screen (no Android-only native route)',async()=>{
  Platform.OS='ios';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('Legal'));
  expect(mockNavigate).toHaveBeenCalledWith('Legal');
});

test.each(['Import','Share','ShareDetail'] as const)('Android %s stays on its shared screen instead of a native handoff',async name=>{
  Platform.OS='android';mockCommand.mockResolvedValue(null);
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate(name));
  expect(mockNavigate).toHaveBeenCalledWith(name);
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'filterEditing'});
  expect(mockCommand).not.toHaveBeenCalledWith({type:'native.flow',flow:expect.anything()});
});

test('Android route gate mirrors the native F21 capability policy',()=>{
  // deviceQA/designGallery are DEBUG_ONLY; DNSPatch and the Apple AutoSwitch signpost are unavailable.
  expect(routeAllowed('DeviceQA',false)).toBe(false);
  expect(routeAllowed('DeviceQA',true)).toBe(true);
  expect(routeAllowed('Components',false)).toBe(false);
  expect(routeAllowed('Components',true)).toBe(true);
  expect(isCapabilityAvailable('dnsPatch',true)).toBe(false);
  expect(isCapabilityAvailable('autoSwitch',true)).toBe(false);
  expect(SYSTEM_CAPABILITIES.autoSwitch.disposition).toBe('unavailable');
  expect(SYSTEM_CAPABILITIES.autoSwitch.iosOnly).toBe(true);
  expect(SYSTEM_CAPABILITIES.autoSwitch.androidEquivalent).toBeNull();
  expect(routeAllowed('AutoSwitch',false)).toBe(false);
  expect(routeAllowed('AutoSwitch',true)).toBe(false);
  expect(isCapabilityAvailable('notificationActions',false)).toBe(true);
  // A route with no owning capability is never gated by the catalog.
  expect(routeAllowed('Filters',false)).toBe(true);
  // The review stack gate consults the same policy, with the review-gallery exception intact.
  expect(androidReviewRouteAllowed('DeviceQA',false,true)).toBe(false);
  expect(androidReviewRouteAllowed('DeviceQA',true,true)).toBe(true);
  expect(androidReviewRouteAllowed('Components',false,false)).toBe(true);
});

test('Android renders the real Guardian screen instead of the Coming-to-Android placeholder',()=>{
  // The shared Guardian screen renders the projected `guards` catalog (original selectable,
  // alternates locked), so the reachable Customization "Choose Lava Guard" row is not a dead end.
  expect(androidReviewRouteAllowed('Guardian',false,true)).toBe(true);
  expect(androidUnavailableScreenRoutes.has('Guardian')).toBe(false);
  // The shared chooser uses the production catalog/draft owners on Android.
  expect(androidReviewRouteAllowed('AddBlocklist',false,true)).toBe(true);
  expect(androidUnavailableScreenRoutes.has('AddBlocklist')).toBe(false);
  // Capability-deferred/absent routes still resolve to the placeholder.
  expect(androidUnavailableScreenRoutes.has('AutoSwitch')).toBe(true);
  expect(androidUnavailableScreenRoutes.has('DNSPatch')).toBe(true);
});


test.each(['DNS','Feedback','Legal'] as const)('a retained %s link cannot start navigation or a native service while scoped presentation reads are pending',async name=>{
  Platform.OS='android';mockHydrationRequired=true;
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate(name));
  expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();
});

test('an authorization reply cannot push a retained page after its native authority was revoked',async()=>{
  Platform.OS='android';let resolve!:(value:null)=>void;
  mockCommand.mockImplementationOnce(()=>new Promise<null>(yes=>{resolve=yes;}));
  const {result}=renderHook(()=>useReviewNavigation());
  await act(async()=>result.current.navigate('DNS'));
  expect(mockCommand).toHaveBeenCalledWith({type:'navigation.authorize',surface:'appSettings'});
  mockPresentationAuthority=false;
  await act(async()=>resolve(null));
  expect(mockNavigate).not.toHaveBeenCalled();
});


// Real Android owner/runtime failures must stay scoped to the connection that dispatched them.
import {AppStore,AppCommandDetachedError} from '../canonical/app/store';
import {NativeModules,DeviceEventEmitter,type AppStateStatus} from 'react-native';
import {waitFor} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import {Alert} from '../canonical/app/presentation';
import type {ProductSnapshot} from '../src/productContract';
const navigationNativePort={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=navigationNativePort;
const navigationPresentation=jest.requireMock('../canonical/app/presentation');
const actualNavigationPresentation=jest.requireActual('../canonical/app/presentation');
Object.assign(navigationPresentation,{localized:actualNavigationPresentation.localized,localizedFormat:actualNavigationPresentation.localizedFormat});
const {androidRuntimeForHost,projectAndroidSnapshot}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
function navigationDeferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(yes=>{resolve=yes;});return {promise,resolve};}

describe('Android navigation command error connection custody',()=>{
  afterEach(()=>jest.restoreAllMocks());
  async function actualNavigationFixture(importProtected=false){
    const held=navigationDeferred<string>(),requests:{type:string;surface?:string;flow?:string}[]=[],errors:unknown[]=[];
    let wire:ProductSnapshot={...productFixture,nativeHostID:'navigation-error-host',foreground:true,backgroundPrivacyCoverRequired:false,
      security:{unavailable:false,ownerRevision:'security-owner-A',readRevision:1,sourceRevision:'source-A',hasAuthenticationMethod:importProtected,protectedSurfaces:importProtected?['filterEditing']:[],authenticatedSurfaces:[]}};
    navigationNativePort.getSnapshot.mockImplementation(async()=>JSON.stringify(wire));
    navigationNativePort.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(wire));
    navigationNativePort.command.mockImplementation(async(raw:string)=>{requests.push(JSON.parse(raw));return held.promise;});
    const runtime=androidRuntimeForHost('navigation-error-host');
    const app=new AppStore(runtime,projectAndroidSnapshot(wire));
    const original=app.command.bind(app);
    jest.spyOn(app,'command').mockImplementation(command=>original(command).catch(error=>{errors.push(error);throw error;}));
    const stateListeners=new Set<(state:AppStateStatus)=>void>();
    jest.spyOn(AppState,'addEventListener').mockImplementation((_type,listener)=>{const callback=listener as (state:AppStateStatus)=>void;stateListeners.add(callback);return {remove:()=>{stateListeners.delete(callback);}};});
    let disconnect=app.connect();
    async function settleLayout(){await act(async()=>{});await act(async()=>app.completePresentationLayout(app.getPresentationHydration().epoch));await waitFor(()=>expect(app.getPresentationHydration().required).toBe(false));}
    await settleLayout();
    jest.spyOn(jest.requireMock('../canonical/review/ReviewContext'),'useReview').mockImplementation(()=>({app,live:app.getSnapshot().snapshot,session:app.getSnapshot().snapshot?.session}));
    const hook=renderHook(()=>useReviewNavigation());
    const start=async(name:'DNS'|'Feedback'|'Import'|'Share'|'ShareDetail')=>{await act(async()=>hook.result.current.navigate(name));await waitFor(()=>expect(requests).toHaveLength(1));};
    const reply=async(response:{status:string;reason?:string;snapshot?:ProductSnapshot;value?:null}={status:'rejected',reason:'operation-failed'})=>{await act(async()=>held.resolve(JSON.stringify(response)));await act(async()=>{});};
    const reconnect=async()=>{disconnect();wire={...wire,revision:wire.revision+1};disconnect=app.connect();await settleLayout();};
    const publish=async()=>{wire={...wire,revision:wire.revision+1,security:{...wire.security!,sourceRevision:'source-B'}};await act(async()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire)));await settleLayout();};
    const background=async()=>{await act(async()=>{AppState.currentState='background';for(const listener of [...stateListeners])listener('background');});};
    const grantImport=async()=>{wire={...wire,revision:wire.revision+1,security:{...wire.security!,readRevision:2,authenticatedSurfaces:['filterEditing']}};await reply({status:'completed',value:null,snapshot:wire});};
    const fresh=async(name:'DNS'|'Feedback'|'Share'|'ShareDetail')=>{
      if(name==='Share'||name==='ShareDetail')wire={...wire,revision:wire.revision+1,security:{...wire.security!,readRevision:wire.security!.readRevision!+1,authenticatedSurfaces:['filterEditing']}};
      navigationNativePort.command.mockImplementationOnce(async(raw:string)=>{requests.push(JSON.parse(raw));return JSON.stringify({status:'completed',value:null,snapshot:wire});});
      await act(async()=>hook.result.current.navigate(name));await waitFor(()=>expect(requests).toHaveLength(2));await act(async()=>{});
    };
    return {app,hook,requests,errors,start,reply,reconnect,publish,background,grantImport,fresh,disconnect:()=>disconnect(),
      close:async()=>{held.resolve(JSON.stringify({status:'rejected',reason:'operation-failed'}));await act(async()=>{});hook.unmount();disconnect();}};
  }
  test.each(['DNS','Feedback'] as const)('a real held %s reply detached by reconnect stays quiet in a readable focused hook',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{
      await f.start(name);expect(f.requests[0]).toEqual(expect.objectContaining(name==='DNS'?{type:'navigation.authorize',surface:'appSettings'}:{type:'native.flow',flow:'feedback'}));
      await f.reconnect();
      await f.reply();expect(f.errors).toHaveLength(1);expect(f.errors[0]).toBeInstanceOf(AppCommandDetachedError);
      expect((f.errors[0] as Error).message).toBe('The app screen closed while this action completed.');
      expect(Alert.alert).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();expect(f.requests).toHaveLength(1);
      await f.fresh(name);expect(f.requests[1]).toEqual(expect.objectContaining(name==='DNS'?{type:'navigation.authorize',surface:'appSettings'}:{type:'native.flow',flow:'feedback'}));
      expect(f.errors).toHaveLength(1);expect(Alert.alert).not.toHaveBeenCalled();
      if(name==='DNS')expect(mockNavigate).toHaveBeenCalledTimes(1);else expect(mockNavigate).not.toHaveBeenCalled();
      if(name==='DNS')expect(mockNavigate).toHaveBeenCalledWith('DNS');
    }finally{await f.close();}
  });
  test.each(['DNS','Feedback'] as const)('a real disconnected %s dispatch is refused before the native port without an Alert',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{
      f.disconnect();expect(f.app.getPresentationAuthority()).toBe(true);
      await act(async()=>f.hook.result.current.navigate(name));await act(async()=>{});
      expect(f.errors).toHaveLength(1);expect(f.errors[0]).toBeInstanceOf(AppCommandDetachedError);
      expect((f.errors[0] as Error).message).toBe('The app screen closed before this action started.');
      expect(Alert.alert).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();expect(f.requests).toHaveLength(0);
    }finally{await f.close();}
  });
  test.each(['DNS','Feedback'] as const)('a current %s native refusal still shows its truthful ordinary error once',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{
      await f.start(name);await f.reply();expect(f.errors).toHaveLength(1);expect(f.errors[0]).not.toBeInstanceOf(AppCommandDetachedError);
      expect(Alert.alert).toHaveBeenCalledTimes(1);expect(Alert.alert).toHaveBeenCalledWith('Lava','This action could not be completed. Try again.');
      expect(mockNavigate).not.toHaveBeenCalled();expect(f.requests).toHaveLength(1);
    }finally{await f.close();}
  });
  test('ordinary same-owner source publication does not hide a current authorization error',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{await f.start('DNS');await f.publish();expect(f.app.getSnapshot().snapshot?.security?.sourceRevision).toBe('source-B');await f.reply();
      expect(Alert.alert).toHaveBeenCalledTimes(1);expect(Alert.alert).toHaveBeenCalledWith('Lava','This action could not be completed. Try again.');expect(mockNavigate).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test('a current authorization cancellation remains quiet without computing or pushing Upgrade',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture();const params=jest.fn(()=>({reason:'customDNS' as const}));
    try{await act(async()=>f.hook.result.current.navigateToUpgrade(params));await waitFor(()=>expect(f.requests).toHaveLength(1));await f.reply({status:'cancelled',reason:'authentication-cancelled'});
      expect((f.errors[0] as Error).message).toBe('Authentication cancelled.');expect(Alert.alert).not.toHaveBeenCalled();expect(params).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test('existing background authority loss suppresses an ordinary held navigation error',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{await f.start('DNS');await f.background();expect(f.app.getPresentationAuthority()).toBe(false);await f.reply();expect(Alert.alert).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();}
    finally{await f.close();}
  });
  test('existing hook unmount suppresses a current ordinary native flow error',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture();
    try{await f.start('Feedback');f.hook.unmount();await f.reply();expect(Alert.alert).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();}
    finally{await f.close();}
  });
  test('iOS keeps its original current error presentation for the shared hook',async()=>{
    Platform.OS='ios';mockCommand.mockRejectedValueOnce(new AppCommandDetachedError('afterStart'));
    const {result}=renderHook(()=>useReviewNavigation());await act(async()=>result.current.navigate('DNS'));
    expect(Alert.alert).toHaveBeenCalledWith('Lava','The app screen closed while this action completed.');expect(mockNavigate).not.toHaveBeenCalled();
  });

  test('protected Import waits for the real Filters authorization while Settings is unprotected',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start('Import');expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect(f.app.getSnapshot().snapshot?.security?.protectedSurfaces).toEqual(['filterEditing']);
      expect(f.app.getSnapshot().snapshot?.security?.authenticatedSurfaces).toEqual([]);expect(mockNavigate).not.toHaveBeenCalled();
      await f.grantImport();expect(f.errors).toEqual([]);expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith('Import');
      expect(f.app.getSnapshot().snapshot?.security?.authenticatedSurfaces).toEqual(['filterEditing']);expect(Alert.alert).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test('cancelled Import authorization cannot push the shared route or start an import service',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start('Import');await f.reply({status:'cancelled',reason:'authentication-cancelled'});
      expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect((f.errors[0] as Error).message).toBe('Authentication cancelled.');expect(mockNavigate).not.toHaveBeenCalled();expect(Alert.alert).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test('a late Import grant cannot push after the real source presentation was paused',async()=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start('Import');await f.background();expect(f.app.getPresentationAuthority()).toBe(false);await f.grantImport();
      expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect(mockNavigate).not.toHaveBeenCalled();expect(Alert.alert).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test('Import stays undispatched while current scoped presentation reads await layout',async()=>{
    Platform.OS='android';mockHydrationRequired=true;const {result}=renderHook(()=>useReviewNavigation());
    await act(async()=>result.current.navigate('Import'));expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();
  });
  test('iOS Import preserves its original shared route without the Android Filters grant',async()=>{
    Platform.OS='ios';mockCommand.mockResolvedValue(null);const {result}=renderHook(()=>useReviewNavigation());
    await act(async()=>result.current.navigate('Import'));expect(mockNavigate).toHaveBeenCalledWith('Import');expect(mockCommand).not.toHaveBeenCalled();
  });

  test.each(['Share','ShareDetail'] as const)('protected %s waits for a real Filters grant while Settings is unprotected',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start(name);expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect(f.app.getSnapshot().snapshot?.security?.protectedSurfaces).toEqual(['filterEditing']);
      expect(f.app.getSnapshot().snapshot?.security?.authenticatedSurfaces).toEqual([]);expect(mockNavigate).not.toHaveBeenCalled();
      await f.grantImport();expect(f.errors).toEqual([]);expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith(name);
      expect(f.app.getSnapshot().snapshot?.security?.authenticatedSurfaces).toEqual(['filterEditing']);expect(Alert.alert).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test.each(['Share','ShareDetail'] as const)('a cancelled %s grant stays quiet and a fresh explicit retry authorizes Filters',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start(name);await f.reply({status:'cancelled',reason:'authentication-cancelled'});
      expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect(mockNavigate).not.toHaveBeenCalled();expect(Alert.alert).not.toHaveBeenCalled();
      expect((f.errors[0] as Error).message).toBe('Authentication cancelled.');
      await f.fresh(name);expect(f.requests[1]).toEqual(expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'}));
      expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith(name);
    }finally{await f.close();}
  });
  test.each(['Share','ShareDetail'] as const)('a current denied %s grant never pushes and preserves the error before a fresh retry',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start(name);await f.reply();expect(mockNavigate).not.toHaveBeenCalled();
      expect(f.app.getSnapshot().snapshot?.security?.authenticatedSurfaces).toEqual([]);
      expect(Alert.alert).toHaveBeenCalledTimes(1);expect(Alert.alert).toHaveBeenCalledWith('Lava','This action could not be completed. Try again.');
      await f.fresh(name);expect(f.requests).toHaveLength(2);expect(f.requests.every(request=>request.type==='navigation.authorize'&&request.surface==='filterEditing')).toBe(true);
      expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith(name);expect(Alert.alert).toHaveBeenCalledTimes(1);
    }finally{await f.close();}
  });
  test.each(['Share','ShareDetail'] as const)('a held %s grant cannot cross real reconnect and a new explicit request owns navigation',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start(name);await f.reconnect();await f.grantImport();
      expect(f.errors).toHaveLength(1);expect(f.errors[0]).toBeInstanceOf(AppCommandDetachedError);
      expect(mockNavigate).not.toHaveBeenCalled();expect(Alert.alert).not.toHaveBeenCalled();expect(f.requests).toHaveLength(1);
      await f.fresh(name);expect(f.requests[1]).toEqual(expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'}));
      expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith(name);
    }finally{await f.close();}
  });
  test.each(['Share','ShareDetail'] as const)('a late %s grant cannot push the retained page after the real presentation pauses',async name=>{
    Platform.OS='android';const f=await actualNavigationFixture(true);
    try{
      await f.start(name);await f.background();expect(f.app.getPresentationAuthority()).toBe(false);await f.grantImport();
      expect(f.requests).toEqual([expect.objectContaining({type:'navigation.authorize',surface:'filterEditing'})]);
      expect(mockNavigate).not.toHaveBeenCalled();expect(Alert.alert).not.toHaveBeenCalled();
    }finally{await f.close();}
  });
  test.each(['Share','ShareDetail'] as const)('%s remains undispatched while scoped presentation reads await measured layout',async name=>{
    Platform.OS='android';mockHydrationRequired=true;const {result}=renderHook(()=>useReviewNavigation());
    await act(async()=>result.current.navigate(name));expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigate).not.toHaveBeenCalled();
  });
  test.each(['Share','ShareDetail'] as const)('iOS %s preserves its original shared route without an Android filter grant',async name=>{
    Platform.OS='ios';mockCommand.mockResolvedValue(null);const {result}=renderHook(()=>useReviewNavigation());
    await act(async()=>result.current.navigate(name));expect(mockNavigate).toHaveBeenCalledWith(name);expect(mockCommand).not.toHaveBeenCalled();
  });
});
