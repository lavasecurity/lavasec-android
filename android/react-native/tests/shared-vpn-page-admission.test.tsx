import {Alert as NativeAlert,AppState,DeviceEventEmitter,NativeModules,Platform,type AppStateStatus} from 'react-native';
import {act,fireEvent,render,renderHook,screen,waitFor} from '@testing-library/react-native';
import type {PropsWithChildren} from 'react';
import {Children,useSyncExternalStore,type ReactElement} from 'react';
import type {AppCommand,AppSnapshot,ForegroundFlow,VPNDraft,VpnDraftRetirement} from '../canonical/app/contract';
import {AppStore} from '../canonical/app/store';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
import {ReviewContext,type ReviewState} from '../canonical/review/ReviewContext';
import {initialSession} from '../canonical/review/session';
import {useVPNPageAdmission} from '../canonical/review/vpn-page-admission';
import {VPNChainingScreen} from '../canonical/review/NativePageScreen';

let mockFocused=true;let mockRouteKey='vpn-visit';
const mockGoBack=jest.fn();const mockSetOptions=jest.fn();
let mockState={index:1,routes:[{name:'Settings',key:'settings'},{name:'VPNChaining',key:'vpn-visit'}]};
const toolbarItems=(side:'Left'|'Right')=>{const options=mockSetOptions.mock.calls.at(-1)![0];
  // The Android native-stack adapter renders the shared items through its
  // header slots. Invoke those actual guarded Pressables, not an iOS-only slot.
  const tree=options[`header${side}`]({});return Children.toArray(tree.props.children).filter(Boolean).map(node=>{const props=(node as ReactElement<{onPress:()=>void;disabled:boolean}>).props;return {onPress:props.onPress,disabled:props.disabled};});};
const mockNavigation={goBack:mockGoBack,setOptions:mockSetOptions,getState:()=>mockState,navigate:jest.fn()};
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>mockFocused,useRoute:()=>({key:mockRouteKey}),useNavigation:()=>mockNavigation,usePreventRemove:jest.fn()}));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{getGuardAccents:()=>JSON.stringify({original:{light:'#BF4000',dark:'#FF8855'}})}}));
jest.mock('../canonical/specs/LavaNativePageNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaSliderNativeComponent',()=>({__esModule:true,default:require('react-native').View}));

const deferred=()=>{let resolve!:()=>void;let reject!:(error:Error)=>void;const promise=new Promise<void>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
let previousPhase:typeof AppState.currentState;let lifecycle:Set<(value:AppStateStatus)=>void>;
beforeEach(()=>{
  previousPhase=AppState.currentState;AppState.currentState='active';mockFocused=true;mockRouteKey='vpn-visit';
  mockState={index:1,routes:[{name:'Settings',key:'settings'},{name:'VPNChaining',key:'vpn-visit'}]};
  mockGoBack.mockClear();mockSetOptions.mockClear();lifecycle=new Set();
  jest.spyOn(AppState,'addEventListener').mockImplementation((_event,listener)=>{lifecycle.add(listener);return {remove:()=>lifecycle.delete(listener)};});
});
afterEach(()=>{AppState.currentState=previousPhase;jest.restoreAllMocks();});
function emit(value:AppStateStatus){AppState.currentState=value;act(()=>{for(const listener of lifecycle)listener(value);});}
function parentCoverFence(){
  let ancestor=screen.getByTestId('vpn-page-privacy-cover',{includeHiddenElements:true}).parent;
  while(ancestor&&ancestor.props.importantForAccessibility!=='no-hide-descendants')ancestor=ancestor.parent;
  return ancestor;
}
function setup(authorized=true){
  const listeners=new Set<()=>void>();let authoritative=true;let hydrated=true;let fieldsAvailable=true;let privacyCoverRequired:boolean|undefined=false;
  const command=jest.fn(async(_command:AppCommand):Promise<unknown>=>null);
  const app={command,getSnapshot:()=>({snapshot:authoritative?live:null,privacyCoverRequired}),getPresentationHydration:()=>({epoch:1,required:!hydrated}),
    subscribe:(listener:()=>void)=>{listeners.add(listener);return()=>{listeners.delete(listener);};},
    registerPresentationRead:jest.fn(()=>({epoch:1,id:1})),settlePresentationRead:jest.fn()};
  let live={foregroundFlow:undefined as ForegroundFlow|null|undefined,vpn:{authorized,setup:true,enabled:false,canEdit:true,canChangeFallback:true,needsPlus:false,rows:[],generation:'saved'},plus:{enabled:true},security:{ownerRevision:'native-owner',readRevision:1}};
  const wrapper=({children}:PropsWithChildren)=><ReviewContext.Provider value={{app,live:fieldsAvailable?live:undefined,session:initialSession()} as unknown as ReviewState}>{children}</ReviewContext.Provider>;
  const notify=()=>act(()=>{for(const listener of listeners)listener();});
  return {app,command,wrapper,setAuthorized:(value:boolean,revision=live.security.readRevision)=>{live={...live,vpn:{...live.vpn,authorized:value},security:{...live.security,readRevision:revision}};},
    setNativeChild:(id?:string)=>{live={...live,foregroundFlow:id?{id,kind:'vpnConfiguration',name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[],vpnEditor:null}:null};},
    setPrivacyPolicy:(value:boolean|undefined)=>{privacyCoverRequired=value;},
    replaceOwner:()=>{live={...live,security:{...live.security,ownerRevision:'replacement-owner'}};},
    setFieldsAvailable:(value:boolean)=>{fieldsAvailable=value;},
    setAuthority:(value:boolean)=>{authoritative=value;notify();},setHydrated:(value:boolean)=>{hydrated=value;notify();}};
}

test('a committed child return conceals controls and retains its actual native draft until native reauthorization',async()=>{
  const fixture=setup();const draft={id:'native-draft',revision:1,changed:false,containsFullTunnel:false,rows:[]};
  fixture.command.mockImplementation(async action=>action.type==='vpn.begin'?draft:null);
  const view=render(<VPNChainingScreen/>,{wrapper:fixture.wrapper});await act(async()=>{});
  expect(fixture.command).not.toHaveBeenCalled();
  act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
  const panel=screen.getByTestId('vpn.configuration-panel');
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.begin',id:expect.any(String),generation:'saved'}]]);
  mockFocused=false;view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  const staleSave=toolbarItems('Right')[0]!;
  fixture.setAuthorized(false,2);mockFocused=true;
  const request=deferred();fixture.command.mockImplementation(async action=>action.type==='vpn.enter'?request.promise:null);
  view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.queryByTestId('vpn.setup-toggle')).toBeNull();
  expect(screen.getByTestId('vpn-page-content',{includeHiddenElements:true}).props).toMatchObject({pointerEvents:'none',accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
  act(()=>staleSave.onPress());await act(async()=>{});
  expect(fixture.command.mock.calls.map(([action])=>action.type)).toEqual(['vpn.begin','vpn.enter']);
  await act(async()=>request.resolve());
  expect(screen.queryByTestId('vpn.setup-toggle')).toBeNull();
  fixture.setAuthorized(true);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.getByTestId('vpn.configuration-panel')).toBe(panel);
  fireEvent.press(screen.getByRole('button',{name:'Add configuration'}));await act(async()=>{});
  expect(fixture.command.mock.calls.at(-1)).toEqual([{type:'vpn.edit',id:draft.id,index:0}]);
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel')).toBe(false);
  view.unmount();await act(async()=>{});
  expect(fixture.command.mock.calls.at(-1)).toEqual([{type:'vpn.cancel',id:draft.id}]);
});

test('the native WireGuard modal owns resume authorization without retiring its obscured parent draft',async()=>{
  const fixture=setup();const draft={id:'native-draft',revision:1,changed:false,containsFullTunnel:false,rows:[]};
  fixture.command.mockImplementation(async action=>action.type==='vpn.begin'?draft:null);
  const view=render(<VPNChainingScreen/>,{wrapper:fixture.wrapper});await act(async()=>{});
  act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
  fixture.setNativeChild('wireguard-child');view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.queryByTestId('vpn.setup-toggle')).toBeNull();
  expect(screen.queryByTestId('vpn-page-privacy-cover',{includeHiddenElements:true})).toBeNull();
  expect(screen.queryByText('Lava Security',{includeHiddenElements:true})).toBeNull();
  const toolbar=mockSetOptions.mock.calls.at(-1)![0];
  const cancel=toolbarItems('Left')[0]!;const save=toolbarItems('Right')[0]!;
  expect(cancel.disabled).toBe(false);expect(save.disabled).toBe(false);
  act(()=>{cancel.onPress();save.onPress();});await act(async()=>{});
  expect(fixture.command.mock.calls.map(([action])=>action.type)).toEqual(['vpn.begin']);
  emit('background');fixture.setFieldsAvailable(false);fixture.setAuthority(false);view.rerender(<VPNChainingScreen/>);
  fixture.setAuthorized(false,2);fixture.setFieldsAvailable(true);emit('active');fixture.setAuthority(true);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(fixture.command.mock.calls.map(([action])=>action.type)).toEqual(['vpn.begin']);
  fixture.command.mockRejectedValueOnce(new Error('Authentication cancelled.'));
  await act(async()=>{await fixture.command({type:'vpnEditor.enter',id:'wireguard-child'}).catch(()=>{});});
  view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(mockGoBack).not.toHaveBeenCalled();
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel'||action.type==='vpn.enter')).toBe(false);
  expect(screen.getByTestId('vpn-page-content',{includeHiddenElements:true}).props).toMatchObject({pointerEvents:'none',accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});

  const request=deferred();fixture.command.mockImplementation(async action=>action.type==='vpn.enter'?request.promise:null);
  fixture.setNativeChild();view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(fixture.command.mock.calls.at(-1)).toEqual([{type:'vpn.enter'}]);
  await act(async()=>request.resolve());expect(screen.queryByTestId('vpn.setup-toggle')).toBeNull();
  fixture.setAuthorized(true);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  fireEvent.press(screen.getByRole('button',{name:'Add configuration'}));await act(async()=>{});
  expect(fixture.command.mock.calls.at(-1)).toEqual([{type:'vpn.edit',id:draft.id,index:0}]);
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel')).toBe(false);
});

test('an authorized all-off parent keeps its accepted paint below the native modal with no input or privacy accessibility',async()=>{
  const fixture=setup();fixture.setNativeChild('wireguard-child');
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(result.current).toMatchObject({canInteract:false,coverRequired:false,coverAccessibilityHidden:true});
  expect(result.current.isAdmitted()).toBe(false);expect(fixture.command).not.toHaveBeenCalled();
  emit('background');fixture.setAuthority(false);rerender(undefined);await act(async()=>{});
  expect(result.current).toMatchObject({canInteract:false,coverRequired:false,coverAccessibilityHidden:true});
  emit('active');fixture.setAuthority(true);rerender(undefined);await act(async()=>{});
  expect(result.current).toMatchObject({canInteract:false,coverRequired:false,coverAccessibilityHidden:true});
  expect(result.current.isAdmitted()).toBe(false);expect(fixture.command).not.toHaveBeenCalled();
});

test.each([true,undefined] as const)('a current grant paints the parent with %s policy; revocation conceals it while the native child owns accessibility',async policy=>{
  const fixture=setup();fixture.setPrivacyPolicy(policy);fixture.setNativeChild('wireguard-child');
  const view=render(<VPNChainingScreen/>,{wrapper:fixture.wrapper});await act(async()=>{});
  expect(screen.queryByTestId('vpn-page-privacy-cover',{includeHiddenElements:true})).toBeNull();
  expect(screen.getByTestId('vpn-page-content',{includeHiddenElements:true}).props).toMatchObject({pointerEvents:'none',accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
  fixture.setAuthorized(false);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.queryByTestId('vpn-page-privacy-cover')).toBeNull();
  expect(parentCoverFence()?.props).toMatchObject({accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
  expect(screen.queryByText('Lava Security')).toBeNull();
  fixture.setAuthorized(true);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.queryByTestId('vpn-page-privacy-cover',{includeHiddenElements:true})).toBeNull();
  fixture.setAuthority(false);view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.queryByTestId('vpn-page-privacy-cover')).toBeNull();
  expect(parentCoverFence()?.props).toMatchObject({accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
  expect(screen.queryByText('Lava Security')).toBeNull();
  expect(fixture.command).not.toHaveBeenCalled();expect(mockGoBack).not.toHaveBeenCalled();
});

test('an unauthorized parent retains opaque concealment even with explicit opt-out while the native child owns accessibility',async()=>{
  const fixture=setup(false);fixture.setNativeChild('wireguard-child');
  render(<VPNChainingScreen/>,{wrapper:fixture.wrapper});await act(async()=>{});
  expect(screen.queryByTestId('vpn-page-privacy-cover')).toBeNull();
  expect(parentCoverFence()?.props).toMatchObject({accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
  expect(screen.queryByText('Lava Security')).toBeNull();expect(fixture.command).not.toHaveBeenCalled();
});

test('a parent cancellation published after native child presentation cannot pop it, including before the React commit',async()=>{
  const fixture=setup(false);const old=deferred();const fresh=deferred();
  fixture.command.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  fixture.setNativeChild('wireguard-child');
  // The command callback sees current native identity before React commits the
  // subscription render. Promise cancellation cannot remove the modal's parent.
  await act(async()=>old.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).not.toHaveBeenCalled();
  rerender(undefined);await act(async()=>{});expect(result.current.canInteract).toBe(false);
  expect(fixture.command).toHaveBeenCalledTimes(1);
  fixture.setNativeChild();rerender(undefined);await act(async()=>{});
  expect(fixture.command).toHaveBeenCalledTimes(2);expect(mockGoBack).not.toHaveBeenCalled();
  await act(async()=>fresh.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).toHaveBeenCalledTimes(1);
});

test('only a fresh native child removal resumes the parent; missing fields and replacement security owners grant nothing',async()=>{
  const fixture=setup();fixture.setNativeChild('wireguard-child');
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(result.current.canInteract).toBe(false);expect(fixture.command).not.toHaveBeenCalled();
  fixture.setFieldsAvailable(false);rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(false);expect(fixture.command).not.toHaveBeenCalled();
  fixture.replaceOwner();fixture.setAuthorized(false,2);fixture.setFieldsAvailable(true);rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(false);expect(fixture.command).not.toHaveBeenCalled();
  fixture.setNativeChild();rerender(undefined);await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);expect(result.current.canInteract).toBe(false);
});

test('cancelled edge Back retains the native grant and does not issue another authentication',async()=>{
  const fixture=setup();const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});
  expect(result.current.canInteract).toBe(true);
  // A cancelled interactive transition does not commit another navigation state.
  rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(true);expect(fixture.command).not.toHaveBeenCalled();
});

test('retired callbacks cannot inherit a replacement admission or reopen an unmounted visit',async()=>{
  const fixture=setup();const {result,rerender,unmount}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});
  const previous=result.current.isAdmitted;expect(previous()).toBe(true);
  fixture.replaceOwner();rerender(undefined);await act(async()=>{});
  expect(previous()).toBe(false);expect(result.current.isAdmitted()).toBe(true);
  const current=result.current.isAdmitted;unmount();expect(current()).toBe(false);
});

test('current authentication cancellation releases hydration before leaving only the protected VPN visit',async()=>{
  const fixture=setup(false);fixture.setHydrated(false);const request=deferred();fixture.command.mockReturnValue(request.promise);
  renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);expect(mockGoBack).not.toHaveBeenCalled();
  await act(async()=>request.reject(new Error('Authentication cancelled.')));
  expect(fixture.app.settlePresentationRead).toHaveBeenCalled();expect(mockGoBack).not.toHaveBeenCalled();
  fixture.setHydrated(true);await act(async()=>{});
  expect(mockGoBack).toHaveBeenCalledTimes(1);
});

test('a biometric inactive/active interval retains one pending native admission and one cancellation leaves the page',async()=>{
  const fixture=setup(false);const request=deferred();fixture.command.mockReturnValue(request.promise);
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
  emit('inactive');fixture.setAuthority(false);fixture.setFieldsAvailable(false);rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(false);
  emit('active');fixture.setFieldsAvailable(true);fixture.setAuthority(true);rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(false);
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
  await act(async()=>request.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).toHaveBeenCalledTimes(1);
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
});

test('a current biometric cancellation delivered while inactive waits for foreground before popping',async()=>{
  const fixture=setup(false);const request=deferred();fixture.command.mockReturnValue(request.promise);
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  emit('inactive');fixture.setAuthority(false);fixture.setFieldsAvailable(false);rerender(undefined);await act(async()=>{});
  await act(async()=>request.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).not.toHaveBeenCalled();expect(result.current.canInteract).toBe(false);
  emit('active');fixture.setFieldsAvailable(true);fixture.setAuthority(true);rerender(undefined);await act(async()=>{});
  expect(mockGoBack).toHaveBeenCalledTimes(1);
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
});

test.each(['cancel','success'] as const)('true background retirement waits for the old %s before requesting fresh evidence without borrowing its result',async outcome=>{
  const fixture=setup(false);const old=deferred();const fresh=deferred();
  fixture.command.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  emit('background');fixture.setAuthority(false);fixture.setFieldsAvailable(false);rerender(undefined);
  fixture.setAuthorized(false,2);fixture.setFieldsAvailable(true);emit('active');fixture.setAuthority(true);rerender(undefined);await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
  await act(async()=>outcome==='cancel'?old.reject(new Error('Authentication cancelled.')):old.resolve());
  expect(mockGoBack).not.toHaveBeenCalled();expect(result.current.canInteract).toBe(false);
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}],[{type:'vpn.enter'}]]);
  await act(async()=>fresh.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).toHaveBeenCalledTimes(1);
});

test.each(['blur','background','inactive','replace-route','replace-owner','revision','unmount'] as const)('a stale authentication cancellation after %s cannot pop or reopen the page',async event=>{
  const fixture=setup(false);const request=deferred();fixture.command.mockReturnValueOnce(request.promise).mockImplementation(()=>new Promise<unknown>(()=>{}));
  const {result,rerender,unmount}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  if(event==='blur'){mockFocused=false;rerender(undefined);}
  if(event==='background'||event==='inactive')emit(event);
  if(event==='replace-route')mockState={index:1,routes:[{name:'Settings',key:'settings'},{name:'VPNChaining',key:'other-visit'}]};
  if(event==='replace-owner'){fixture.replaceOwner();rerender(undefined);}
  if(event==='revision'){fixture.setAuthorized(false,2);rerender(undefined);}
  if(event==='unmount')unmount();
  await act(async()=>request.reject(new Error('Authentication cancelled.')));
  expect(mockGoBack).not.toHaveBeenCalled();
  if(event!=='unmount')expect(result.current.canInteract).toBe(false);
});

test('resume entry waits for current native fields, may run beneath hydration, and never admits mutations from its promise',async()=>{
  const fixture=setup(false);fixture.setAuthority(false);fixture.setHydrated(false);
  const request=deferred();fixture.command.mockReturnValue(request.promise);
  const {result,rerender}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(fixture.command).not.toHaveBeenCalled();expect(result.current.canInteract).toBe(false);
  fixture.setAuthority(true);await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}]]);
  await act(async()=>request.resolve());expect(result.current.canInteract).toBe(false);
  fixture.setAuthorized(true);rerender(undefined);await act(async()=>{});
  expect(result.current.canInteract).toBe(false);
  fixture.setHydrated(true);await act(async()=>{});expect(result.current.canInteract).toBe(true);
});

test('an admission error remains concealed and can retry without granting or changing VPN setup',async()=>{
  const fixture=setup(false);fixture.command.mockRejectedValueOnce(new Error('Read access changed.'));
  const {result}=renderHook(()=>useVPNPageAdmission(),{wrapper:fixture.wrapper});await act(async()=>{});
  expect(result.current.canRetry).toBe(true);expect(result.current.canInteract).toBe(false);expect(mockGoBack).not.toHaveBeenCalled();
  act(()=>result.current.retry());await act(async()=>{});
  expect(fixture.command.mock.calls).toEqual([[{type:'vpn.enter'}],[{type:'vpn.enter'}]]);
  expect(result.current.canInteract).toBe(false);
});


async function dirtyParentConfirmation(){
  const fixture=setup();
  Object.assign(fixture.app.getSnapshot().snapshot!,{generation:'7',nativeHostID:'host-a'});
  let draft!: {id:string;revision:number;changed:boolean;containsFullTunnel:boolean;rows:[]};
  fixture.command.mockImplementation(async action=>{
    if(action.type==='vpn.begin'){
      draft={id:action.id,revision:1,changed:true,containsFullTunnel:false,rows:[]};
      Object.assign(fixture.app.getSnapshot().snapshot!.vpn,{draft});return draft;
    }
    return null;
  });
  const alerts=jest.spyOn(NativeAlert,'alert').mockImplementation(()=>{});
  const view=render(<VPNChainingScreen/>,{wrapper:fixture.wrapper});await act(async()=>{});
  act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
  act(()=>toolbarItems('Left')[0]!.onPress());await act(async()=>{});
  const prompt=alerts.mock.calls.at(-1)!;
  expect(prompt[0]).toBe('Discard changes?');
  const confirm=prompt[2]!.find(button=>button.text==='Discard')!.onPress!;
  return {...fixture,view,alerts,draft,confirm,dismiss:()=>prompt[3]?.onDismiss?.()};
}

test('a held dirty parent confirmation survives same-owner child scope renewal and cancels exactly once',async()=>{
  const fixture=await dirtyParentConfirmation();
  fixture.setNativeChild('returning-native-child');fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  fixture.setNativeChild();fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(screen.getByTestId('vpn-page-content').props.pointerEvents).toBe('auto');
  act(()=>{fixture.confirm();fixture.dismiss();fixture.confirm();});await act(async()=>{});
  expect(fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toEqual([[{type:'vpn.cancel',id:fixture.draft.id}]]);
  expect(toolbarItems('Right')[0]!.disabled).toBe(false);
});

test('a confirmed parent discard waits through concealment for its original admission without another click',async()=>{
  const fixture=await dirtyParentConfirmation();
  fixture.setNativeChild('returning-native-child');fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  act(()=>{fixture.confirm();fixture.dismiss();});await act(async()=>{});
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel')).toBe(false);
  fixture.setNativeChild();fixture.setHydrated(false);fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel')).toBe(false);
  fixture.setHydrated(true);fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  expect(fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toEqual([[{type:'vpn.cancel',id:fixture.draft.id}]]);
  expect(fixture.alerts).toHaveBeenCalledTimes(1);
});


test.each(['host','graph','draft','owner','read-turn','grant','route','Plus','unmount'] as const)('a held parent discard cannot adopt a successor %s',async boundary=>{
  const fixture=await dirtyParentConfirmation();
  const fields=fixture.app.getSnapshot().snapshot!;
  if(boundary==='host')Object.assign(fields,{nativeHostID:'host-b'});
  if(boundary==='graph')Object.assign(fields,{generation:'8'});
  if(boundary==='draft')Object.assign(fields.vpn,{draft:{...fixture.draft,id:'successor-draft'}});
  if(boundary==='owner')fixture.replaceOwner();
  if(boundary==='read-turn')fixture.setAuthorized(true,2);
  if(boundary==='grant')fixture.setAuthorized(false);
  if(boundary==='route')mockState={index:1,routes:[{name:'Settings',key:'settings'},{name:'VPNChaining',key:'successor-route'}]};
  if(boundary==='Plus')Object.assign(fields.vpn,{needsPlus:true});
  if(boundary==='unmount')fixture.view.unmount();else fixture.view.rerender(<VPNChainingScreen/>);
  await act(async()=>{});
  // Existing unmount/entitlement teardown is separate from the old dialog.
  const before=fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel').length;
  act(()=>{fixture.confirm();fixture.dismiss();});await act(async()=>{});
  expect(fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toHaveLength(before);
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel'&&action.id==='successor-draft')).toBe(false);
  if(boundary==='grant'){
    fixture.setAuthorized(true);fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
    expect(fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toHaveLength(before);
  }
});

test('dismissing an unconfirmed parent dialog never queues cancellation and a fresh confirmation still works',async()=>{
  const fixture=await dirtyParentConfirmation();
  act(()=>fixture.dismiss());
  fixture.setNativeChild('returning-native-child');fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  fixture.setNativeChild();fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  act(()=>fixture.confirm());await act(async()=>{});
  expect(fixture.command.mock.calls.some(([action])=>action.type==='vpn.cancel')).toBe(false);
  act(()=>toolbarItems('Left')[0]!.onPress());await act(async()=>{});
  const current=fixture.alerts.mock.calls.at(-1)![2]!.find(button=>button.text==='Discard')!.onPress!;
  act(()=>current());await act(async()=>{});
  expect(fixture.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toEqual([[{type:'vpn.cancel',id:fixture.draft.id}]]);
});

test.each(['current','successor'] as const)('a parent cancellation error belongs only to its %s owner',async owner=>{
  const fixture=await dirtyParentConfirmation();const request=deferred();
  fixture.command.mockImplementation(async action=>action.type==='vpn.cancel'?request.promise:null);
  act(()=>fixture.confirm());await act(async()=>{});
  if(owner==='successor'){
    Object.assign(fixture.app.getSnapshot().snapshot!,{nativeHostID:'host-b'});
    fixture.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  }
  await act(async()=>request.reject(new Error('Exact native pending cleanup failed.')));
  const errors=fixture.alerts.mock.calls.filter(([title])=>title==='Lava');
  expect(errors).toHaveLength(owner==='current'?1:0);
  if(owner==='current')expect(errors[0]![1]).toBe('Exact native pending cleanup failed.');
});


// Drive the real host-bound adapter and store. Only the native repository/command
// port is controlled; a cleanup refusal must reach the actual page as a rejection.
const cancellationPort={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=cancellationPort;
const {androidRuntimeForHost}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
async function runtimeParentCancellation(options:{open?:boolean;issuedGeneration?:string;issuedHost?:string;rows?:VPNDraft['rows']}={}){
  let wire:ProductSnapshot&Pick<AppSnapshot,'plus'>={...productFixture,nativeHostID:options.issuedHost??'vpn-cancel-host',generation:options.issuedGeneration??'7',backgroundPrivacyCoverRequired:false,
    security:{unavailable:false,ownerRevision:'vpn-owner',readRevision:1,hasAuthenticationMethod:false,protectedSurfaces:[],authenticatedSurfaces:[]},
    plus:{enabled:true,busy:false,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},
    vpn:{authorized:true,setup:true,enabled:false,canEnable:true,canEdit:true,fallback:false,canChangeFallback:true,needsPlus:false,busy:false,
      restriction:'',error:'',unavailable:false,generation:'saved',rows:[]}};
  let pendingImport:string|undefined;const requests:AppCommand[]=[],cancelReplies:Array<Promise<string>>=[],operationReplies:Array<Promise<string>>=[];
  const cancelAttempts=new Set<string>(),abandoned=new Set<string>(),retirementRequests:unknown[]=[];
  const visits=new Map<string,VpnDraftRetirement>();let serial=0;let lastIssued:VpnDraftRetirement|undefined;
  const cancelDenials:string[]=[];const beginReplies:Array<Promise<void>>=[];
  const cleanupAttempts:string[]=[];
  const result=(value:unknown)=>JSON.stringify({status:'completed',value,snapshot:wire});
  cancellationPort.getSnapshot.mockImplementation(async()=>JSON.stringify(wire));
  cancellationPort.getSnapshotForHost.mockImplementation(async()=>JSON.stringify(wire));
  cancellationPort.command.mockImplementation(async(raw:string)=>{
    const action=JSON.parse(raw) as AppCommand;requests.push(action);
    if(action.type==='vpn.begin'){
      if(pendingImport){
        if(!abandoned.has(pendingImport))return JSON.stringify({status:'rejected',reason:'unavailable'});
        cleanupAttempts.push(pendingImport);abandoned.delete(pendingImport);pendingImport=undefined;
      }
      const retirement={id:action.id,retirementGeneration:wire.generation!,nativeHostID:wire.nativeHostID!,
        retirementLease:`00000000-0000-4000-8000-${String(++serial).padStart(12,'0')}`};
      visits.set(action.id,{...retirement});lastIssued=retirement;
      const draft:VPNDraft={id:action.id,retirement,revision:1,changed:true,containsFullTunnel:false,rows:options.rows??[]};pendingImport=action.id;
      // Actual snapshots need not propagate the Begin result-only capability.
      const projected={...draft};delete projected.retirement;
      wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,draft:projected}};
      const held=beginReplies.shift();if(held)await held;
      return result(draft);
    }
    if(action.type==='vpn.cancel'){
      const denial=cancelDenials.shift();if(denial)return JSON.stringify({status:'rejected',reason:denial});
      cancelAttempts.add(action.id);
      // The shipping bridge cancels the transient editor before repository removal.
      // Its exact published draft may therefore disappear even when the atomic
      // pending import remains and the command truthfully reports cleanup failure.
      wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,draft:null}};
      DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire));
      const held=cancelReplies.shift();if(held)return held;
      // The exact editor tag is the only simulated removal authority.
      if(pendingImport===action.id){pendingImport=undefined;visits.delete(action.id);wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,draft:null}};}
      return result(null);
    }
    if(action.type==='vpn.retire'){
      retirementRequests.push(action);
      const visit=visits.get(action.id);
      if(!visit||action.id!==pendingImport||action.nativeHostID!==visit.nativeHostID||action.retirementGeneration!==visit.retirementGeneration||action.retirementLease!==visit.retirementLease)
        return JSON.stringify({status:'rejected',reason:'stale-vpn-draft'});
      abandoned.add(action.id);return JSON.stringify({status:'completed',value:null});
    }
    if(action.type==='vpn.swap'){
      const draft={...wire.vpn!.draft!,revision:2};wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,draft}};return result(draft);
    }
    if(action.type==='settings.set'){const held=operationReplies.shift();if(held)return held;}
    return result(null);
  });
  const source=androidRuntimeForHost(options.issuedHost??'vpn-cancel-host');const app=new AppStore(source,JSON.parse(await source.getSnapshot()));
  const disconnect=app.connect();await act(async()=>{});
  await act(async()=>app.completePresentationLayout(app.getPresentationHydration().epoch));
  await waitFor(()=>expect(app.getPresentationHydration().required).toBe(false));
  const wrapper=({children}:PropsWithChildren)=>{
    const live=useSyncExternalStore(app.subscribe,()=>app.getSnapshot().snapshot);
    return <ReviewContext.Provider value={{app,live:live??undefined,session:live?.session??initialSession()} as ReviewState}>{children}</ReviewContext.Provider>;
  };
  const alerts=jest.spyOn(NativeAlert,'alert').mockImplementation(()=>{});
  const view=render(<VPNChainingScreen/>,{wrapper});await act(async()=>{});
  if(options.open!==false){act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});}
  const id=app.getSnapshot().snapshot!.vpn!.draft?.id??'';
  const cancel=async()=>{act(()=>toolbarItems('Left')[0]!.onPress());await act(async()=>{});
    const prompt=alerts.mock.calls.at(-1)!;expect(prompt[0]).toBe('Discard changes?');
    return prompt[2]!.find(button=>button.text==='Discard')!.onPress!;};
  return {app,view,wrapper,alerts,requests,id,cancel,cancelReplies,operationReplies,retirementRequests,cleanupAttempts,cancelAttempts,cancelDenials,beginReplies,issued:()=>lastIssued,visit:(id:string)=>visits.get(id),pendingImport:()=>pendingImport,
    currentWire:()=>wire,
    setNeedsPlus:(needsPlus:boolean)=>{wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,needsPlus}};DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire));},
    replaceDraftReceipt:()=>{const replacement={...wire.vpn!.draft!,retirement:{id:pendingImport!,retirementGeneration:'8',nativeHostID:'successor',retirementLease:'22222222-2222-4222-8222-222222222222'}};wire={...wire,revision:wire.revision+1,vpn:{...wire.vpn!,draft:replacement}};DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(wire));},
    close:async()=>{await view.unmountAsync();disconnect();}};
}

test('a failed native pending-import Discard retains the same parent for one explicit retry and clears it only after success',async()=>{
  const f=await runtimeParentCancellation();
  let finish!:(value:string)=>void;
  f.cancelReplies.push(new Promise<string>(resolve=>{finish=resolve;}));
  try{
    const confirm=await f.cancel();act(()=>{confirm();confirm();});await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    // Actual native AuthoritativeReadFailed/Failed envelope, not a fabricated JS exception.
    await act(async()=>finish(JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'})));
    expect(f.alerts.mock.calls.at(-1)!.slice(0,2)).toEqual(['Lava','Lava could not undo the cancelled import. Reopen the editor and remove the WireGuard configuration.']);
    expect(f.pendingImport()).toBe(f.id);expect(f.app.getSnapshot().snapshot!.vpn!.draft).toBeNull();
    expect(toolbarItems('Right')[0]!.disabled).toBe(false);
    expect(screen.getByRole('button',{name:'Add configuration'})).toBeTruthy();
    // No effect or old consumed dialog callback retries the failed operation.
    f.view.rerender(<VPNChainingScreen/>);act(()=>confirm());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    const retry=await f.cancel();act(()=>retry());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toEqual([
      expect.objectContaining({type:'vpn.cancel',id:f.id,generation:'7',nativeHostID:'vpn-cancel-host'}),
      expect.objectContaining({type:'vpn.cancel',id:f.id,generation:'7',nativeHostID:'vpn-cancel-host'}),
    ]);
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(1);
    expect(f.requests.some(row=>row.type==='vpn.commit'||row.type==='vpn.edit')).toBe(false);
    expect(f.pendingImport()).toBeUndefined();expect(f.app.getSnapshot().snapshot!.vpn!.draft).toBeNull();
    expect(screen.queryByRole('button',{name:'Add configuration'})).toBeNull();
  }finally{finish(JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'}));await f.close();}
});

test.each(['draft','graph','host','grant','read-turn'] as const)('a failed Discard never retries or clears a successor %s',async boundary=>{
  const f=await dirtyParentConfirmation();const held=deferred();
  f.command.mockImplementation(async action=>action.type==='vpn.cancel'?held.promise:null);
  act(()=>f.confirm());await act(async()=>{});
  const fields=f.app.getSnapshot().snapshot!;
  if(boundary==='draft')Object.assign(fields.vpn,{draft:{...f.draft,id:'successor-parent'}});
  if(boundary==='graph')Object.assign(fields,{generation:'8'});
  if(boundary==='host')Object.assign(fields,{nativeHostID:'host-b'});
  if(boundary==='grant')f.setAuthorized(false);
  if(boundary==='read-turn')f.setAuthorized(true,2);
  f.view.rerender(<VPNChainingScreen/>);await act(async()=>{});
  await act(async()=>held.reject(new Error('Exact pending import removal failed.')));
  act(()=>{f.confirm();f.dismiss();});await act(async()=>{});
  expect(f.alerts.mock.calls.filter(([title])=>title==='Lava')).toEqual([]);
  expect(f.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toEqual([[{type:'vpn.cancel',id:f.draft.id}]]);
  expect(f.command.mock.calls.some(([action])=>action.type==='vpn.cancel'&&action.id==='successor-parent')).toBe(false);
});


test('one pending parent Discard retains the draft and blocks every duplicate action until its native outcome',async()=>{
  const f=await dirtyParentConfirmation(),held=deferred();
  f.command.mockImplementation(async action=>action.type==='vpn.cancel'?held.promise:null);
  const staleSave=toolbarItems('Right')[0]!;
  act(()=>{f.confirm();f.confirm();staleSave.onPress();});await act(async()=>{});
  expect(f.command.mock.calls.filter(([action])=>action.type==='vpn.cancel')).toHaveLength(1);
  expect(f.command.mock.calls.some(([action])=>action.type==='vpn.commit')).toBe(false);
  expect(toolbarItems('Left')[0]!.disabled).toBe(true);expect(toolbarItems('Right')[0]!.disabled).toBe(true);
  await act(async()=>held.resolve());
  expect(screen.queryByRole('button',{name:'Add configuration'})).toBeNull();
});


test.each(['pending','failed'] as const)('Android parent unmount never replays an explicitly %s pending-import Discard',async outcome=>{
  const f=await runtimeParentCancellation();let finish!:(value:string)=>void;
  f.cancelReplies.push(new Promise<string>(resolve=>{finish=resolve;}));
  const failure=JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'});
  try{
    const confirm=await f.cancel();act(()=>confirm());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    if(outcome==='failed')await act(async()=>finish(failure));
    await f.view.unmountAsync();await act(async()=>{});
    if(outcome==='pending')await act(async()=>finish(failure));
    await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toEqual([
      expect.objectContaining({type:'vpn.cancel',id:f.id,generation:'7',nativeHostID:'vpn-cancel-host'}),
    ]);
    expect(f.pendingImport()).toBe(f.id);
    expect(f.requests.some(row=>row.type==='vpn.commit'||row.type==='vpn.edit')).toBe(false);
  }finally{finish(failure);await f.close();}
});

test('Android parent unmount still cancels an untouched draft through its actual current host',async()=>{
  const f=await runtimeParentCancellation();
  try{
    expect(f.pendingImport()).toBe(f.id);
    await f.view.unmountAsync();await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toEqual([
      expect.objectContaining({type:'vpn.cancel',id:f.id,generation:'7',nativeHostID:'vpn-cancel-host'}),
    ]);
    expect(f.pendingImport()).toBeUndefined();
  }finally{await f.close();}
});


test.each(['pending','failed'] as const)('exact Android route retirement follows the %s Cancel outcome before one fresh native Begin',async outcome=>{
  const f=await runtimeParentCancellation();const issued={...f.issued()!};let finish!:(value:string)=>void;
  f.cancelReplies.push(new Promise<string>(resolve=>{finish=resolve;}));
  const failure=JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'});
  let next:ReturnType<typeof render>|undefined;
  try{
    const confirm=await f.cancel();act(()=>confirm());await act(async()=>{});
    if(outcome==='failed')await act(async()=>finish(failure));
    await f.view.unmountAsync();await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    if(outcome==='pending')expect(f.retirementRequests).toEqual([]);
    next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    if(outcome==='pending'){
      expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(1);
      await act(async()=>finish(failure));
    }
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...issued}]);
    const begins=f.requests.filter(row=>row.type==='vpn.begin');expect(begins).toHaveLength(2);
    const nextId=(begins[1] as Extract<AppCommand,{type:'vpn.begin'}>).id;
    expect(nextId).not.toBe(f.id);expect(f.cleanupAttempts).toEqual([f.id]);
    expect(f.pendingImport()).toBe(nextId);
    const types=f.requests.map(row=>row.type);
    expect(types.indexOf('vpn.retire')).toBeLessThan(types.lastIndexOf('vpn.begin'));
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
  }finally{finish(failure);if(next)await next.unmountAsync();await f.close();}
});

test('a not-yet-started Cancel cannot be overtaken by route retirement or the next Begin',async()=>{
  const f=await runtimeParentCancellation();let unblock!:(value:string)=>void,finish!:(value:string)=>void;
  f.operationReplies.push(new Promise<string>(resolve=>{unblock=resolve;}));
  f.cancelReplies.push(new Promise<string>(resolve=>{finish=resolve;}));
  const failure=JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'});
  let next:ReturnType<typeof render>|undefined;
  try{
    const blocker=f.app.command({type:'settings.set',key:'look',value:'original'});await act(async()=>{});
    const confirm=await f.cancel();act(()=>confirm());
    await f.view.unmountAsync();await act(async()=>{});
    next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toEqual([]);expect(f.retirementRequests).toEqual([]);
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(1);
    await act(async()=>{unblock(JSON.stringify({status:'completed',value:null}));await blocker;});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);expect(f.retirementRequests).toEqual([]);
    await act(async()=>finish(failure));
    expect(f.requests.map(row=>row.type).slice(-3)).toEqual(['vpn.cancel','vpn.retire','vpn.begin']);
    expect(f.cleanupAttempts).toEqual([f.id]);expect(f.pendingImport()).not.toBe(f.id);
  }finally{unblock(JSON.stringify({status:'completed',value:null}));finish(failure);if(next)await next.unmountAsync();await f.close();}
});


test('native retirement rejects missing, wrong and successor leases without durable cleanup',async()=>{
  const f=await runtimeParentCancellation();
  try{
    const original=f.issued()!;
    for(const receipt of [{...original,retirementLease:''},{...original,retirementLease:'22222222-2222-4222-8222-222222222222'},
      {...original,retirementGeneration:'8'},{...original,nativeHostID:'successor'},{...original,id:'successor'}]){
      await expect(f.app.retireVpnDraft(receipt,Promise.resolve())).rejects.toThrow();
      expect(f.pendingImport()).toBe(f.id);expect(f.cleanupAttempts).toEqual([]);
    }
    expect(f.cancelAttempts.size).toBe(0);expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(0);
  }finally{await f.close();}
});


// V2 native-issued lease controls: only the actual Begin value issues route
// ownership. The port models the closed native registry, not a JS retry.
test('v2 a native admission refusal before Cancel begins still relinquishes only the issued route lease',async()=>{
  const f=await runtimeParentCancellation();let next:ReturnType<typeof render>|undefined;
  try{
    const confirm=await f.cancel();f.cancelDenials.push('unauthorized');act(()=>confirm());await act(async()=>{});
    expect(f.cancelAttempts.size).toBe(0);expect(f.pendingImport()).toBe(f.id);
    expect(f.app.getSnapshot().snapshot!.vpn!.draft!.id).toBe(f.id);
    await f.view.unmountAsync();await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...f.issued()}]);
    expect(f.cleanupAttempts).toEqual([]);expect(f.pendingImport()).toBe(f.id);
    next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.cleanupAttempts).toEqual([f.id]);expect(f.pendingImport()).not.toBe(f.id);
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(2);
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
  }finally{if(next)await next.unmountAsync();await f.close();}
});

test.each(['untouched','entitlement'] as const)('v2 %s exit owns one ordinary refused Cancel and one original leased marker',async exit=>{
  const f=await runtimeParentCancellation();let next:ReturnType<typeof render>|undefined;
  try{
    f.cancelDenials.push('unauthorized');
    if(exit==='untouched')await f.view.unmountAsync();else await act(async()=>f.setNeedsPlus(true));
    await act(async()=>{});
    expect(f.cancelAttempts.size).toBe(0);expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...f.issued()}]);
    expect(f.pendingImport()).toBe(f.id);expect(f.cleanupAttempts).toEqual([]);
    if(exit==='entitlement'){await f.view.unmountAsync();await act(async()=>f.setNeedsPlus(false));}
    next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.cleanupAttempts).toEqual([f.id]);expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
    expect(f.pendingImport()).not.toBe(f.id);
  }finally{if(next)await next.unmountAsync();await f.close();}
});

test('v2 a delayed native Begin delivery after unmount captures its own tuple before one refused Cancel and retirement',async()=>{
  const f=await runtimeParentCancellation({open:false});const held=deferred();let next:ReturnType<typeof render>|undefined;
  f.beginReplies.push(held.promise);f.cancelDenials.push('unauthorized');
  try{
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    const original=f.pendingImport()!;const issued={...f.issued()!};
    await f.view.unmountAsync();await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(0);expect(f.retirementRequests).toEqual([]);
    await act(async()=>held.resolve());
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...issued}]);
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toEqual([expect.objectContaining({type:'vpn.cancel',id:original})]);
    expect(f.cancelAttempts.size).toBe(0);expect(f.pendingImport()).toBe(original);expect(f.cleanupAttempts).toEqual([]);
    next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.cleanupAttempts).toEqual([original]);expect(f.pendingImport()).not.toBe(original);
  }finally{held.resolve();if(next)await next.unmountAsync();await f.close();}
});

test('v2 an already queued new Begin refuses before late-delivery retirement; only a fresh explicit retry recovers',async()=>{
  const f=await runtimeParentCancellation({open:false});const held=deferred();let next:ReturnType<typeof render>|undefined;
  f.beginReplies.push(held.promise);f.cancelDenials.push('unauthorized');
  try{
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});const original=f.pendingImport()!;
    await f.view.unmountAsync();next=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(1);
    await act(async()=>held.resolve());
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(2);
    expect(f.cleanupAttempts).toEqual([]);expect(f.pendingImport()).toBe(original);
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...f.issued()}]);
    const types=f.requests.map(row=>row.type);expect(types.slice(-3)).toEqual(['vpn.begin','vpn.cancel','vpn.retire']);
    // No hidden retry: user explicitly requests a new Begin after the marker.
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    expect(f.requests.filter(row=>row.type==='vpn.begin')).toHaveLength(3);
    expect(f.cleanupAttempts).toEqual([original]);expect(f.pendingImport()).not.toBe(original);
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(1);
  }finally{held.resolve();if(next)await next.unmountAsync();await f.close();}
});

test('v2 snapshot draft replacements cannot replace the native Begin lease or manufacture a successor retirement',async()=>{
  const f=await runtimeParentCancellation();const original={...f.issued()!};
  try{
    await act(async()=>f.replaceDraftReceipt());
    expect(f.app.getSnapshot().snapshot!.vpn!.draft!.retirement?.retirementLease).not.toBe(original.retirementLease);
    f.cancelDenials.push('unauthorized');await f.view.unmountAsync();await act(async()=>{});
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...original}]);
    expect(f.pendingImport()).toBe(original.id);expect(f.cleanupAttempts).toEqual([]);
  }finally{await f.close();}
});

test('v2 a nondefault native-issued graph and host remain exact through retirement',async()=>{
  const f=await runtimeParentCancellation({issuedGeneration:'9',issuedHost:'issued-native-host'});
  try{
    expect(f.issued()).toMatchObject({retirementGeneration:'9',nativeHostID:'issued-native-host'});
    f.cancelDenials.push('unauthorized');await f.view.unmountAsync();await act(async()=>{});
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...f.issued()}]);
    expect(f.pendingImport()).toBe(f.id);expect(f.cleanupAttempts).toEqual([]);
  }finally{await f.close();}
});


test('v2 a stage reply without the lease retains the exact original Begin capability',async()=>{
  const f=await runtimeParentCancellation({rows:[{name:'Profile A',mode:'Split tunnel'},{name:'Profile B',mode:'Split tunnel'}]});const original={...f.issued()!};
  try{
    await act(async()=>{fireEvent.press(screen.getByRole('button',{name:'Swap order'}));});
    expect(f.requests.filter(row=>row.type==='vpn.swap')).toHaveLength(1);
    expect(f.app.getSnapshot().snapshot!.vpn!.draft!.revision).toBe(2);
    expect(f.app.getSnapshot().snapshot!.vpn!.draft!.retirement).toBeUndefined();
    f.cancelDenials.push('unauthorized');await f.view.unmountAsync();await act(async()=>{});
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...original}]);
    expect(f.cleanupAttempts).toEqual([]);
  }finally{await f.close();}
});

test('v2 the Begin result object cannot mutate the separately frozen route receipt after acceptance',async()=>{
  const f=await runtimeParentCancellation({open:false});const command=f.app.command.bind(f.app);let delivered:VPNDraft|undefined;
  jest.spyOn(f.app,'command').mockImplementation(async action=>{
    const value=await command(action);if(action.type==='vpn.begin')delivered=value as VPNDraft;return value as never;
  });
  try{
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});const original={...f.issued()!};
    expect(delivered?.retirement).toEqual(original);
    Object.assign(delivered!.retirement!,{id:'successor',retirementGeneration:'8',nativeHostID:'other',retirementLease:'22222222-2222-4222-8222-222222222222'});
    f.cancelDenials.push('unauthorized');await f.view.unmountAsync();await act(async()=>{});
    expect(f.retirementRequests).toEqual([{schema:1,type:'vpn.retire',...original}]);
    expect(f.pendingImport()).toBe(original.id);expect(f.cleanupAttempts).toEqual([]);
  }finally{await f.close();}
});

test('v2 an iOS parent retains ordinary Cancel cleanup and never sends Android route retirement',async()=>{
  const previous=Platform.OS;Object.defineProperty(Platform,'OS',{configurable:true,value:'ios'});
  const f=setup();const draft:VPNDraft={id:'ios-parent',retirement:{id:'ios-parent',retirementGeneration:'7',nativeHostID:'ios-host',retirementLease:'11111111-1111-4111-8111-111111111111'},revision:1,changed:false,containsFullTunnel:false,rows:[]};
  f.command.mockImplementation(async action=>action.type==='vpn.begin'?draft:null);
  const view=render(<VPNChainingScreen/>,{wrapper:f.wrapper});
  try{
    await act(async()=>{});act(()=>mockSetOptions.mock.calls.at(-1)![0].unstable_headerRightItems({})[0].onPress());await act(async()=>{});
    await view.unmountAsync();await act(async()=>{});
    expect(f.command.mock.calls.filter(([row])=>row.type==='vpn.cancel')).toEqual([[{type:'vpn.cancel',id:draft.id}]]);
    expect(f.command.mock.calls.some(([row])=>row.type==='vpn.retire')).toBe(false);
  }finally{await view.unmountAsync();Object.defineProperty(Platform,'OS',{configurable:true,value:previous});}
});


// Error presentation is an effect of the original admitted parent, independent
// from native cancellation/retirement. Drive the real runtime and AppStore here.
async function heldParentError(action:'vpn.commit'|'vpn.edit'='vpn.commit'){
  const f=await runtimeParentCancellation();const held=deferred();
  const original=cancellationPort.command.getMockImplementation()!;
  cancellationPort.command.mockImplementation(async(raw:string)=>{
    const request=JSON.parse(raw) as AppCommand;
    if(request.type===action){f.requests.push(request);await held.promise;return JSON.stringify({status:'completed',value:null});}
    return original(raw);
  });
  if(action==='vpn.commit')act(()=>toolbarItems('Right')[0]!.onPress());
  else fireEvent.press(screen.getByRole('button',{name:'Add configuration'}));
  await act(async()=>{});
  expect(f.requests.filter(row=>row.type===action)).toHaveLength(1);
  return {...f,held};
}

test.each(['current','source-only','unmount','graph','host','owner','read-turn','route','concealed','renewed'] as const)(
  'a held native VPN failure is presented only by its original current parent: %s',async boundary=>{
    const f=await heldParentError();
    try{
      const fields=f.app.getSnapshot().snapshot!;
      if(boundary==='source-only')Object.assign(fields.security,{sourceRevision:'ordinary-publication'});
      if(boundary==='graph')Object.assign(fields,{generation:'8'});
      if(boundary==='host')Object.assign(fields,{nativeHostID:'successor-host'});
      if(boundary==='owner')Object.assign(fields.security,{ownerRevision:'successor-owner'});
      if(boundary==='read-turn')Object.assign(fields.security,{readRevision:2});
      if(boundary==='route')mockState={index:0,routes:mockState.routes};
      if(boundary==='concealed')emit('background');
      if(boundary==='renewed'){
        const wire=JSON.parse(await cancellationPort.getSnapshotForHost('vpn-cancel-host')) as ProductSnapshot;
        cancellationPort.getSnapshotForHost.mockResolvedValue(JSON.stringify({...wire,revision:wire.revision+1,security:{...wire.security,readRevision:2}}));
        emit('background');emit('active');
        act(()=>DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify({...wire,revision:wire.revision+1,security:{...wire.security,readRevision:2}})));await act(async()=>{});
        fireEvent(screen.getByTestId('screen.scroll',{includeHiddenElements:true}),'layout',{nativeEvent:{layout:{x:0,y:0,width:320,height:640}}});
        await act(async()=>f.app.completePresentationLayout(f.app.getPresentationHydration().epoch));
        await waitFor(()=>expect(f.app.getPresentationHydration().required).toBe(false));
        expect(f.app.getSnapshot().snapshot!.security.readRevision).toBe(2);
      }
      if(boundary==='unmount')await f.view.unmountAsync();else f.view.rerender(<VPNChainingScreen/>);
      await act(async()=>f.held.reject(new Error('The current native VPN operation failed.')));
      const lava=f.alerts.mock.calls.filter(([title])=>title==='Lava');
      if(boundary==='current'||boundary==='source-only')expect(lava).toEqual([['Lava','The current native VPN operation failed.',undefined,undefined]]);
      else expect(lava).toEqual([]);
      expect(f.requests.filter(row=>row.type==='vpn.commit')).toHaveLength(1);
      expect(f.requests.some(row=>row.type==='vpn.begin'&&row.id!==f.id)).toBe(false);
    }finally{f.held.resolve();await f.close();}
  });

test.each(['beforeStart','afterStart'] as const)('actual Store %s detachment never opens a VPN error Alert',async phase=>{
  const f=await runtimeParentCancellation();const held=deferred();
  const original=cancellationPort.command.getMockImplementation()!;
  cancellationPort.command.mockImplementation(async(raw:string)=>{
    const request=JSON.parse(raw) as AppCommand;
    if(request.type==='vpn.commit'){f.requests.push(request);await held.promise;return JSON.stringify({status:'rejected',reason:'stale-host'});}
    return original(raw);
  });
  try{
    if(phase==='beforeStart'){
      const disconnect=f.app.connect();await act(async()=>{});disconnect();
    }
    act(()=>toolbarItems('Right')[0]!.onPress());await act(async()=>{});
    if(phase==='afterStart')await act(async()=>held.resolve());
    expect(f.alerts.mock.calls.filter(([title])=>title==='Lava')).toEqual([]);
    expect(f.requests.filter(row=>row.type==='vpn.commit')).toHaveLength(phase==='beforeStart'?0:1);
  }finally{held.resolve();await f.close();}
});

test.each(['Authentication cancelled.','plus'] as const)('current VPN %s refusal remains quiet without a cancellation replay',async outcome=>{
  const f=await heldParentError('vpn.edit');
  try{
    const error=outcome==='plus'?new (require('../canonical/review/plus-intents').PlusResumePending)():new Error(outcome);
    await act(async()=>f.held.reject(error));
    expect(f.alerts.mock.calls.filter(([title])=>title==='Lava')).toEqual([]);
    expect(f.requests.filter(row=>row.type==='vpn.edit')).toHaveLength(1);
    expect(f.requests.filter(row=>row.type==='vpn.cancel')).toHaveLength(0);
  }finally{f.held.resolve();await f.close();}
});

test('an ordinary current edit error remains visible through the original run caller',async()=>{
  const f=await heldParentError('vpn.edit');
  try{
    await act(async()=>f.held.reject(new Error('The current native edit failed.')));
    expect(f.alerts.mock.calls.filter(([title])=>title==='Lava')).toEqual([['Lava','The current native edit failed.',undefined,undefined]]);
    expect(f.requests.filter(row=>row.type==='vpn.edit')).toHaveLength(1);
  }finally{f.held.resolve();await f.close();}
});

test.each(['current','unmount'] as const)('the setup Begin error callback keeps its own mounted parent: %s',async boundary=>{
  const f=setup();Object.assign(f.app.getSnapshot().snapshot!.vpn,{setup:false});
  Object.assign(f.app.getSnapshot().snapshot!,{generation:'7',nativeHostID:'setup-host'});
  const held=deferred();f.command.mockImplementation(async action=>action.type==='vpn.begin'?held.promise:null);
  const alerts=jest.spyOn(NativeAlert,'alert').mockImplementation(()=>{});
  const view=render(<VPNChainingScreen/>,{wrapper:f.wrapper});await act(async()=>{});
  fireEvent.press(screen.getByTestId('vpn.setup-toggle.label'));await act(async()=>{});
  expect(f.command.mock.calls.filter(([action])=>action.type==='vpn.begin')).toHaveLength(1);
  if(boundary==='unmount')await view.unmountAsync();
  await act(async()=>held.reject(new Error('The current native Begin failed.')));
  const lava=alerts.mock.calls.filter(([title])=>title==='Lava');
  expect(lava).toEqual(boundary==='current'?[['Lava','The current native Begin failed.',undefined,undefined]]:[]);
});
