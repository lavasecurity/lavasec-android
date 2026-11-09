import {act,fireEvent,screen,waitFor} from '@testing-library/react-native';
import {installLifecycleMock,mountJourney,completed,deferred,type Reply,type Wire} from './interruptionHarness';
import {productFixture} from './productFixture';
import {AppCommandDetachedError} from '../canonical/app/store';
import {GuardScreen} from '../canonical/review/screens';
import {OnboardingGeometryContext,type Anchor} from '../canonical/review/onboarding-geometry';
import type {OnboardingSetup} from '../canonical/app/contract';
import {AndroidPresentationContext,withAndroidPresentation,type AndroidPresentation} from '../canonical/review/presentation-owner.android';

const mockNavigate=jest.fn();
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true,useRoute:()=>({key:'first-guard',params:undefined}),
  useNavigation:()=>({setOptions:jest.fn(),navigate:mockNavigate,getState:()=>({routes:[],index:0})}),usePreventRemove:jest.fn()}));
jest.mock('react-native-safe-area-context',()=>{const React=require('react');return {
  SafeAreaInsetsContext:React.createContext({top:0,right:0,bottom:0,left:0}),useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})};});

const GuardRoute=withAndroidPresentation(GuardScreen);
const rootPresentation:AndroidPresentation={serial:1,routeKey:'first-guard',surface:'none'};
afterEach(()=>jest.restoreAllMocks());

test('a native-owned first-run bootstrap admits the measured Guard then opens its shared onboarding owner',async()=>{
  const lifecycle=installLifecycleMock();
  const wire:Wire={...productFixture,available:false,onboardingRequired:true,onboardingAvailable:true,
    protection:{...productFixture.protection,onboarding:'freshInstall'},
    filters:{locked:false,available:false,rows:[]},
    activity:{locked:false,status:'unavailable',allowed:null,blocked:null,startDate:null,endDate:null}};
  const harness=mountJourney({lifecycle,wire,render:()=> <AndroidPresentationContext.Provider value={rootPresentation}><GuardRoute/></AndroidPresentationContext.Provider>});
  const completion=deferred<Reply>();
  harness.owner.setHandler((command,owner)=>{
    if(command.type==='native.flow'&&command.flow==='onboarding'){
      owner.publish({onboardingSetup:{id:'first-onboarding',mock:false,page:0,history:[],visited:[0],phase:'setup',busy:'',error:'',
        level:'balanced',fallback:false,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:false,notifications:false}});
      return completion.promise;
    }
    return completed();
  });
  try{
    await harness.ready();
    await waitFor(()=>expect(harness.count('navigation.presentationReady')).toBe(1));
    expect(harness.owner.commands.find(command=>command.type==='navigation.presentationReady'))
      .toEqual(expect.objectContaining({type:'navigation.presentationReady',serial:1}));
    expect(harness.owner.wire.available).toBe(false);
    expect(harness.store.getPresentationAuthority()).toBe(true);
    expect(harness.runtime()?.filters).toEqual([]);
    fireEvent.press(screen.getByRole('button',{name:'Continue setup'}));
    await waitFor(()=>expect(harness.count('native.flow')).toBe(1));
    expect(harness.owner.commands.find(command=>command.type==='native.flow'))
      .toEqual(expect.objectContaining({type:'native.flow',flow:'onboarding',generation:'7'}));
    expect(harness.runtime()?.onboardingSetup?.id).toBe('first-onboarding');
    await act(async()=>{completion.resolve(completed());await Promise.resolve();});
  }finally{harness.unmount();}
});

test.each(['protection.toggle','protection.pause'] as const)('Guard %s cannot begin from a concealed retained frame or alert after its authority is revoked',async type=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{}),lifecycle=installLifecycleMock();
  const harness=mountJourney({lifecycle,wire:{...productFixture,protection:{...productFixture.protection,lifecycle:'protected',action:'turnOff',canPause:true}},render:()=> <GuardScreen/>});
  const reply=deferred<Reply>();
  harness.owner.setHandler(command=>command.type===type?reply.promise:completed());
  try{
    await harness.ready();
    // Simulate a callback already captured by the old native control. Its
    // scoped admission must close even when that control is now inaccessible.
    let control=screen.getByRole('button',{name:'Turn off protection'});
    while(typeof control.props.onPress!=='function'&&control.parent)control=control.parent;
    const invoke=type==='protection.toggle'?control.props.onPress
      :()=>screen.getByTestId('guard.pause-menu',{includeHiddenElements:true}).props.onAction({nativeEvent:{id:'5'}});
    const press=()=>act(()=>invoke());press();
    await waitFor(()=>expect(harness.count(type)).toBe(1));
    harness.emitAppState('inactive');press();
    await act(async()=>{reply.resolve({status:'rejected',reason:'storage-write-failed'});await Promise.resolve();});
    expect(harness.count(type)).toBe(1);
    expect(alert).not.toHaveBeenCalled();
  }finally{harness.unmount();}
});

test('Guard reloads the actual native saved onboarding owner and keeps configured private families unavailable during reconstruction',async()=>{
  const lifecycle=installLifecycleMock();
  const wire:Wire={...productFixture,available:false,onboardingRequired:true,onboardingAvailable:true,onboardingRecoveryRequired:true,onboardingReconstructionInFlight:false,
    filters:{locked:false,available:false,rows:[]},activity:{locked:false,status:'unavailable',allowed:null,blocked:null,startDate:null,endDate:null}};
  const harness=mountJourney({lifecycle,wire,render:()=> <AndroidPresentationContext.Provider value={rootPresentation}><GuardRoute/></AndroidPresentationContext.Provider>});
  const completion=deferred<Reply>();
  harness.owner.setHandler((command,owner)=>{
    if(command.type==='native.flow'&&command.flow==='onboarding'){
      owner.publish({onboardingReconstructionInFlight:true});return completion.promise;
    }
    return completed();
  });
  try{
    await harness.ready();
    expect(screen.getByText('Lava needs to reload the saved setup on this device before you continue. Unsaved choices will not be applied.')).toBeTruthy();
    const reload=screen.getByRole('button',{name:'Reload setup'});
    expect(reload).toHaveProp('accessibilityState',{disabled:false,busy:false});
    fireEvent.press(reload);
    await waitFor(()=>expect(harness.count('native.flow')).toBe(1));
    expect(harness.owner.commands.find(command=>command.type==='native.flow')).toEqual(expect.objectContaining({type:'native.flow',flow:'onboarding',generation:'7'}));
    const loading=screen.getByRole('button',{name:'Reloading setup'});
    expect(loading).toHaveProp('accessibilityState',{disabled:true,busy:true});
    fireEvent.press(loading);
    expect(harness.count('native.flow')).toBe(1);
    expect(harness.owner.wire.available).toBe(false);
    expect(harness.runtime()?.filters).toEqual([]);
    expect(harness.runtime()?.newFilter).toBeUndefined();
    expect(harness.runtime()?.protection.today).toBeUndefined();
    await act(async()=>{
      harness.owner.publish({onboardingReconstructionInFlight:false,onboardingRecoveryRequired:false,
        onboardingSetup:{id:'reloaded-onboarding',mock:false,page:0,history:[],visited:[0],phase:'setup',busy:'',error:'',level:'balanced',fallback:false,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:false,notifications:false}});
      completion.resolve(completed());await Promise.resolve();
    });
    expect(harness.runtime()?.onboardingSetup?.id).toBe('reloaded-onboarding');
    expect(harness.runtime()?.draft).toEqual({blocked:[],allowed:[]});
  }finally{harness.unmount();}
});


test.each(['completed','rejected','detached'] as const)('a native Guardian reveal authorization %s from a retired window cannot navigate or alert the current Guard',async outcome=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{}),lifecycle=installLifecycleMock();
  mockNavigate.mockClear();
  const harness=mountJourney({lifecycle,wire:productFixture,render:()=> <GuardScreen/>}),authorization=deferred<Reply>();
  harness.owner.setHandler(command=>command.type==='navigation.authorize'?authorization.promise:completed());
  try{
    await harness.ready();
    // Fire the real Android decoration leaf, which dispatches Guard's reveal
    // callback and its native authorization continuation.
    fireEvent(screen.getByTestId('guard.mascot.gesture',{includeHiddenElements:true}),'guardianGesture',{nativeEvent:{gesture:'reveal'}});
    await waitFor(()=>expect(harness.count('navigation.authorize')).toBe(1));
    expect(harness.owner.commands.find(command=>command.type==='navigation.authorize')).toEqual(expect.objectContaining({type:'navigation.authorize',surface:'appSettings',generation:'7'}));
    if(outcome!=='detached'){
      // This route is retained and becomes active again before the old reply,
      // so active.current alone cannot fence its obsolete authorization.
      harness.emitAppState('background');harness.emitAppState('active');await harness.ready();
    }
    await act(async()=>{
      if(outcome==='detached')authorization.reject(new AppCommandDetachedError('afterStart'));
      else authorization.resolve(outcome==='completed'?completed():{status:'rejected',reason:'stale-host'});
      await Promise.resolve();
    });
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(alert).not.toHaveBeenCalled();
    expect(harness.owner.commands.some(command=>command.type==='guard.gesture'&&command.gesture==='reveal')).toBe(false);
  }finally{harness.unmount();}
});


test('shared onboarding conceals the retained Guard actions while keeping its measured destination mounted',async()=>{
  const lifecycle=installLifecycleMock();
  const anchor=():Anchor=>({current:null});
  const geometry={panel:anchor(),mascot:anchor(),action:anchor(),source:anchor(),root:anchor(),origin:{x:0,y:0},size:{width:320,height:640}};
  let setup:OnboardingSetup|undefined;
  const harness=mountJourney({lifecycle,wire:productFixture,render:()=>
    <OnboardingGeometryContext.Provider value={{...geometry,setup}}><GuardScreen/></OnboardingGeometryContext.Provider>});
  try{
    await harness.ready();
    const retained=screen.UNSAFE_getByType(GuardScreen);
    const mascot=screen.getByTestId('guard.mascot.gesture',{includeHiddenElements:true});
    expect(screen.getByRole('button',{name:'Turn on protection'})).toBeTruthy();
    setup={id:'shared-onboarding',mock:false,page:5,history:[0,1,2,3,4],visited:[0,1,2,3,4,5],
      level:'essential',fallback:true,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:true,notifications:false,busy:'',error:'',phase:'ready'};
    harness.rerender();
    // The underlay intentionally paints the measured Ready destination. It
    // must not expose a second Open Guard control that ignores a real AX click.
    expect(screen.queryByRole('button',{name:'Open Guard'})).toBeNull();
    expect(screen.getByText('Open Guard',{includeHiddenElements:true})).toBeTruthy();
    expect(screen.UNSAFE_getByType(GuardScreen)).toBe(retained);
    expect(screen.getByTestId('guard.mascot.gesture',{includeHiddenElements:true})).toBe(mascot);
    expect(harness.count('protection.toggle')).toBe(0);
    setup=undefined;harness.rerender();
    expect(screen.getByRole('button',{name:'Turn on protection'})).toBeTruthy();
    expect(screen.getByTestId('guard.mascot.gesture',{includeHiddenElements:true})).toBe(mascot);
  }finally{harness.unmount();}
});
