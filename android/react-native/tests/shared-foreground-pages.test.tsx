import {act,fireEvent,render,screen,waitFor} from '@testing-library/react-native';
import {useLayoutEffect} from 'react';
import {AppState,Platform,ScrollView,StyleSheet,TextInput,View} from 'react-native';
import type {AppCommand,FeedbackState,AppSnapshot,ForegroundFlow} from '../canonical/app/contract';
import {Alert} from '../canonical/app/presentation';

const mockUpgrade=jest.fn();
const mockGoBack=jest.fn(),mockCommand=jest.fn(),mockNavigation={setOptions:jest.fn(),goBack:mockGoBack,navigate:jest.fn()};
let mockMayInteract=true,mockPresentationAuthority=true,mockToolbarReview=false,mockFocused=true;
let mockRoute={params:{id:'visit',kind:'dns' as 'dns'|'blocklist'}};
let mockLive:Partial<AppSnapshot>;
const mockApp={command:mockCommand,subscribe:()=>()=>{},getReadEpoch:()=>1,getPresentationAuthority:()=>mockPresentationAuthority,getSnapshot:()=>({snapshot:mockLive}),getPresentationHydration:()=>({epoch:1})};
let mockReviewApp:typeof mockApp|AppStore=mockApp;
jest.mock('@react-navigation/elements',()=>({HeaderHeightContext:require('react').createContext(0),useHeaderHeight:()=>0}));
jest.mock('@react-navigation/native',()=>({useRoute:()=>mockRoute,useIsFocused:()=>mockFocused,usePreventRemove:jest.fn(),useNavigation:()=>mockNavigation}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>mockNavigation}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({app:mockReviewApp,live:mockLive}),useOptionalReview:()=>mockToolbarReview?({app:mockReviewApp,live:mockLive}):undefined,useRouteBodyConcealed:()=>false}));
jest.mock('../canonical/review/scaffold',()=>({...jest.requireActual('../canonical/review/scaffold'),useSheetHeaderInset:()=>0}));
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
jest.mock('../canonical/review/plus-entry',()=>({usePlusEntry:()=>mockUpgrade}));
jest.mock('../canonical/app/read-cache',()=>({...jest.requireActual('../canonical/app/read-cache'),mayInteractWithPresentation:()=>mockMayInteract,domainReviewPrivacyScope:()=>String(mockLive.security?.readRevision??0)}));
jest.mock('../canonical/app/use-presentation-readiness',()=>({usePresentationAuthority:()=>mockPresentationAuthority,usePresentationReadiness:()=>true,usePresentationNativeLayout:()=>()=>{}}),{virtual:true});
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
import {CustomEntryScreen} from '../canonical/review/CustomEntryScreen';
import {ForegroundFlowScreen} from '../canonical/review/ForegroundFlowScreen';
import {LibraryScreen} from '../canonical/review/FilterScreens';
import {AppCommandDetachedError,AppStore} from '../canonical/app/store';
import NativeReview from '../canonical/specs/NativeLavaReview';
import {FeedbackFlow} from '../canonical/review/FeedbackFlow';
import {AndroidPresentationInteractionContext,useAndroidPresentationReady,type AndroidInteractionReceipt} from '../canonical/review/presentation-owner.android';
import {ListRow} from '../canonical/review/scaffold';
import {VPNEditorFlow} from '../canonical/review/VPNEditorFlow';
import {FormField} from '../canonical/review/form-scaffold';
import {DNSEditorProvider,useDNSEditor} from '../canonical/review/dns-editor';
import Input from '../canonical/specs/LavaTextFieldNativeComponent';
const nativeEdit=(node:Parameters<typeof fireEvent>[0],text:string)=>fireEvent(node,'change',{nativeEvent:{text}});
const choice={id:'custom-dns|reviewed',name:'Retained DNS',primary:'https://resolver.example/dns-query',secondary:'',transport:'DoH',metadata:'Reviewed resolver'};
let dnsPicker:ReturnType<typeof useDNSEditor>;
function DNSPickerSetup(){dnsPicker=useDNSEditor();useLayoutEffect(()=>{dnsPicker.setDraft({tiers:[],context:'parent-ladder'});dnsPicker.beginCustomPicker('visit','parent-ladder');},[]);return null;}
const entryTree=()=> <DNSEditorProvider retirementKey="native-owner"><DNSPickerSetup/><CustomEntryScreen/></DNSEditorProvider>;
const deferred=<T,>()=>{let resolve!:(result:T)=>void;const promise=new Promise<T>(done=>{resolve=done;});return {promise,resolve};};
const feedback:FeedbackState={topic:'suggestion',site:'',details:'',email:'',diagnostics:false,step:1,furthest:1,revision:'1',review:'',normalizedSite:'',normalizedDetails:'',normalizedEmail:'',count:0,canContinue:true,dirty:false,busy:false,prepared:false,sent:false,error:'',receipt:'',copied:false,topics:[{id:'suggestion',title:'I have a suggestion'}]};
const flow=(kind:ForegroundFlow['kind']):ForegroundFlow=>({id:'visit',kind,name:'',emoji:'🌿',dismissAttempt:0,canCreate:true,templates:[],feedback,vpnEditor:{name:'',nameResetRevision:0,dirty:false,hasContent:false,concealed:false,reading:false,canEdit:true,canSave:false,error:''}});
beforeEach(()=>{jest.clearAllMocks();mockReviewApp=mockApp;mockToolbarReview=false;mockFocused=true;mockMayInteract=true;mockPresentationAuthority=true;Object.defineProperty(AppState,'currentState',{configurable:true,value:'active'});mockRoute={params:{id:'visit',kind:'dns'}};mockLive={security:{readRevision:1} as AppSnapshot['security'],customEntry:{id:'visit',kind:'dns',name:'Retained DNS',primary:'tls://dns.example',secondary:'',allowed:true,overBudget:false}};mockCommand.mockImplementation((request:AppCommand)=>Promise.resolve(request.type==='customEntry.save'?choice:undefined));jest.spyOn(AppState,'addEventListener').mockReturnValue({remove:jest.fn()});});
afterEach(()=>jest.restoreAllMocks());

test('shared DNS form sends current visit and submitted addresses through the native command',async()=>{
  render(entryTree());nativeEdit(screen.getByLabelText('Primary DNS'),'https://resolver.example/dns-query');
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.save',id:'visit',name:'Retained DNS',primary:'https://resolver.example/dns-query',secondary:''});
  expect(mockGoBack).toHaveBeenCalledTimes(1);
  expect(dnsPicker.customPickerDraft?.choice).toEqual(choice);
  expect(dnsPicker.draft.tiers).toEqual([]);
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
});

test('custom-entry concealment preserves a local draft but denies writes until the same native visit returns',async()=>{
  render(entryTree());nativeEdit(screen.getByLabelText('Primary DNS'),'tls://unsaved.example');
  mockMayInteract=false;mockLive={...mockLive,customEntry:null};screen.rerender(entryTree());
  expect(screen.getByTestId('custom-entry-privacy-cover')).toBeTruthy();
  const retained=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Primary DNS')!;
  expect(retained.props.value).toBe('tls://unsaved.example');retained.props.onChangeText('tls://should-not-write.example');
  expect(screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Primary DNS')!.props.value).toBe('tls://unsaved.example');
  mockMayInteract=true;mockLive={...mockLive,customEntry:{id:'visit',kind:'dns',name:'Retained DNS',primary:'tls://dns.example',secondary:'',allowed:true,overBudget:false}};
  screen.rerender(entryTree());expect(screen.queryByTestId('custom-entry-privacy-cover')).toBeNull();
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  expect(mockCommand).toHaveBeenCalledWith(expect.objectContaining({type:'customEntry.save',primary:'tls://unsaved.example'}));
});

test('shared blocklist composition captures URL in its current owner and rejects over-budget Save',async()=>{
  mockRoute.params.kind='blocklist';mockLive.customEntry={id:'visit',kind:'blocklist',name:'',primary:'',secondary:'',allowed:true,overBudget:false};
  render(<CustomEntryScreen/>);nativeEdit(screen.getByLabelText('Blocklist URL'),' https://example.com/list.txt ');
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:/Add blocklist/i})));
  expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.save',id:'visit',name:'',url:'https://example.com/list.txt'});
  mockLive.customEntry={...mockLive.customEntry,overBudget:true};screen.rerender(<CustomEntryScreen/>);
  expect(screen.getByRole('button',{name:/Add blocklist/i}).props.accessibilityState.disabled).toBe(true);
});

test('custom DNS result cannot publish into a replaced parent picker context',async()=>{
  const pending=deferred<typeof choice>();mockCommand.mockReturnValue(pending.promise);
  render(entryTree());await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  await act(async()=>{dnsPicker.setDraft({tiers:[],context:'replacement-ladder'});dnsPicker.beginCustomPicker('replacement','replacement-ladder');});
  await act(async()=>pending.resolve(choice));
  expect(dnsPicker.customPickerDraft).toEqual({token:'replacement',context:'replacement-ladder'});
  expect(mockGoBack).not.toHaveBeenCalled();
  expect(screen.getByText('The DNS selection changed. Return to DNS and try again.')).toBeTruthy();
});

test('shared foreground dispatch mounts Feedback and owner-only confidential VPN composition',async()=>{
  mockLive.foregroundFlow=flow('feedback');render(<ForegroundFlowScreen/>);
  expect(screen.getByTestId('feedback.flow')).toBeTruthy();expect(screen.getByLabelText('Details')).toBeTruthy();
  mockLive.foregroundFlow=flow('vpnConfiguration');screen.rerender(<ForegroundFlowScreen/>);
  const config=screen.UNSAFE_getAllByType(Input).find(input=>input.props.kind==='wireGuard')!;
  expect(config.props.ownerID).toBe('visit');expect(config.props.value).toBeUndefined();expect(config.props.defaultValue).toBeUndefined();expect(config.props.onChange).toBeUndefined();
  expect(screen.getByText("After saving, your private key stays in this device's protected storage and isn't shown again.")).toBeTruthy();
  await act(async()=>{});
});

test('Feedback Review waits for composition blur and drains the latest field rather than stale queued text',async()=>{
  const first=deferred<FeedbackState>();mockCommand.mockImplementation((request:AppCommand)=>request.type==='feedback.change'?first.promise:Promise.resolve(undefined));
  render(<FeedbackFlow id="visit" state={feedback} dismissAttempt={0}/>);
  const details=screen.getByLabelText('Details');fireEvent(details,'focusChange',{nativeEvent:{focused:true}});nativeEdit(details,'First text');nativeEdit(details,'Newest text');
  expect(mockCommand.mock.calls.filter(([request])=>request.type==='feedback.change')).toEqual([[{type:'feedback.change',id:'visit',field:'details',value:'First text'}]]);
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Review'})));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'feedback.step'}));
  mockCommand.mockImplementation((request:AppCommand)=>Promise.resolve(request.type==='feedback.change'?{...feedback,details:request.value}:undefined));
  await act(async()=>{first.resolve({...feedback,details:'First text'});fireEvent(details,'focusChange',{nativeEvent:{focused:false}});});
  expect(mockCommand).toHaveBeenCalledWith({type:'feedback.change',id:'visit',field:'details',value:'Newest text'});
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'feedback.step',id:'visit',next:true}));
});

test('VPN editor rejects stale name editing when the current native owner changes',async()=>{
  const editor=flow('vpnConfiguration');mockLive.foregroundFlow=editor;
  render(<VPNEditorFlow id="visit" state={editor.vpnEditor!} dismissAttempt={0}/>);await act(async()=>{});mockCommand.mockClear();
  mockLive.foregroundFlow={...editor,id:'replacement'};
  nativeEdit(screen.getByLabelText('Name'),'Should not enter replacement');
  expect(mockCommand).not.toHaveBeenCalled();
});


type HeaderCancel={props:{accessibilityLabel:string;accessibilityState:{disabled:boolean};disabled:boolean;onPress:()=>void}};
function currentVpnCancel():HeaderCancel{
  const options=mockNavigation.setOptions.mock.calls.filter(([options])=>typeof options.headerLeft==='function').at(-1)?.[0] as {headerLeft:()=>{props:{children:HeaderCancel[]}}};
  const cancel=options.headerLeft().props.children[0]!;
  expect(cancel.props.accessibilityLabel).toBe('Cancel');
  return cancel;
}

test('Android VPN Save acknowledgement keeps Cancel inert during native publication and restores it for an editable retry',async()=>{
  jest.replaceProperty(Platform,'OS','android');
  const alerts=jest.spyOn(Alert,'alert');const acknowledged=deferred<void>();
  const editable={...flow('vpnConfiguration'),vpnEditor:{...flow('vpnConfiguration').vpnEditor!,name:'Office',dirty:true,hasContent:true,canSave:true}};
  mockLive.foregroundFlow=editable;
  mockCommand.mockImplementation((request:AppCommand)=>request.type==='vpnEditor.save'?acknowledged.promise:Promise.resolve(undefined));
  const tree=()=>{const current=mockLive.foregroundFlow!;return <VPNEditorFlow key={current.id} id={current.id} state={current.vpnEditor!} dismissAttempt={0}/>;};
  render(tree());await act(async()=>{});
  const queuedCancel=currentVpnCancel();expect(queuedCancel.props.disabled).toBe(false);
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  expect(mockCommand).toHaveBeenCalledWith({type:'vpnEditor.save',id:'visit'});
  await act(async()=>{
    mockLive={...mockLive,foregroundFlow:{...editable,vpnEditor:{...editable.vpnEditor,canEdit:false,canSave:false,hasContent:false}}};
    screen.rerender(tree());acknowledged.resolve();
  });
  // Local saving has settled, while the exact native selection remains readonly.
  expect(screen.getByRole('button',{name:'Save'}).props.accessibilityState.disabled).toBe(true);
  const retainedCancel=currentVpnCancel();
  expect(retainedCancel.props.disabled).toBe(true);expect(retainedCancel.props.accessibilityState.disabled).toBe(true);
  mockCommand.mockClear();
  await act(async()=>{queuedCancel.props.onPress();retainedCancel.props.onPress();});
  expect(mockCommand).not.toHaveBeenCalled();expect(alerts).not.toHaveBeenCalled();
  // Publication refusal can open a fresh native retry; only that current owner admits Cancel.
  mockLive={...mockLive,foregroundFlow:{...editable,id:'retry-visit',vpnEditor:{...editable.vpnEditor,dirty:false,hasContent:false,canEdit:true,canSave:false}}};
  screen.rerender(tree());await act(async()=>{});mockCommand.mockClear();
  const retryCancel=currentVpnCancel();expect(retryCancel.props.disabled).toBe(false);
  await act(async()=>retryCancel.props.onPress());
  expect(mockCommand.mock.calls).toEqual([[{type:'foreground.dismiss',id:'retry-visit'}]]);
  expect(alerts).not.toHaveBeenCalled();
});

test('iOS keeps its native Cancel admission when configuration editing is temporarily unavailable',async()=>{
  jest.replaceProperty(Platform,'OS','ios');
  const editor={...flow('vpnConfiguration'),vpnEditor:{...flow('vpnConfiguration').vpnEditor!,canEdit:false,reading:true}};
  mockLive.foregroundFlow=editor;
  render(<VPNEditorFlow id="visit" state={editor.vpnEditor} dismissAttempt={0}/>);await act(async()=>{});mockCommand.mockClear();
  const options=mockNavigation.setOptions.mock.calls.filter(([options])=>typeof options.unstable_headerLeftItems==='function').at(-1)?.[0] as {unstable_headerLeftItems:()=>Array<{disabled:boolean;onPress:()=>void}>};
  const cancel=options.unstable_headerLeftItems()[0]!;expect(cancel.disabled).toBe(false);
  await act(async()=>cancel.onPress());
  expect(mockCommand.mock.calls).toEqual([[{type:'foreground.dismiss',id:'visit'}]]);
});


// Installed Fabric ViewShadowNode::initialize() materializes a View for these screen props.
// Comparing the actual reader's effective native parent catches a layout-only body becoming a
// parent during native-stack removal; an ordinary React instance identity assertion cannot.
function foregroundNativeParent(node: ReturnType<typeof screen.UNSAFE_getByType>) {
  for (let parent = node.parent; parent; parent = parent.parent) {
    if (typeof parent.type !== 'string' || !['View', 'RCTView'].includes(parent.type)) continue;
    const props = parent.props;
    const style = StyleSheet.flatten(props.style) ?? {};
    if (props.collapsable === false || props.pointerEvents === 'none' || props.pointerEvents === 'box-only'
      || props.accessible || props.accessibilityElementsHidden
      || props.importantForAccessibility && props.importantForAccessibility !== 'auto'
      || style.opacity !== undefined && style.opacity !== 1 || style.backgroundColor
      || props.onLayout || props.testID) return parent;
  }
  throw new Error('The reader has no native screen parent.');
}

test('Android license reader keeps the same native body owner through concealment, reauthorization and Close', async () => {
  jest.replaceProperty(Platform, 'OS', 'android');
  const licenseFlow = {...flow('licenses'), notices: 'Complete, selectable license text'};
  mockLive = {...mockLive, foregroundFlow: licenseFlow};
  const tree = () => <View collapsable={false}><ForegroundFlowScreen /></View>;
  render(tree());
  const reader = screen.UNSAFE_getByType(ScrollView);
  const body = foregroundNativeParent(reader);
  expect(screen.getByText(licenseFlow.notices).props.selectable).toBe(true);
  // No read grant: keep the original owner and native scroll instance concealed and inert.
  mockMayInteract = false; mockPresentationAuthority = false;
  mockLive = {...mockLive, foregroundFlow: null, security: {...mockLive.security, readRevision: 2} as AppSnapshot['security']};
  mockCommand.mockClear();
  screen.rerender(tree());
  expect(screen.UNSAFE_getByType(ScrollView) === reader).toBe(true);
  expect(foregroundNativeParent(reader) === body).toBe(true);
  expect(body.props.pointerEvents).toBe('none');
  expect(body.props.accessibilityElementsHidden).toBe(true);
  expect(StyleSheet.flatten(body.props.style).opacity).toBe(0);
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'foreground.enter'}));
  // The exact visit may resume after current native authority returns.
  mockMayInteract = true; mockPresentationAuthority = true;
  mockLive = {...mockLive, foregroundFlow: licenseFlow};
  screen.rerender(tree());
  expect(foregroundNativeParent(reader) === body).toBe(true);
  expect(body.props.pointerEvents).toBe('auto');
  expect(StyleSheet.flatten(body.props.style).opacity).toBe(1);
  // An owned dismissal preserves the painted outgoing frame but removes input/AX authority.
  mockLive = {...mockLive, foregroundFlow: null, foregroundClosing: ['visit']};
  screen.rerender(tree());
  expect(screen.UNSAFE_getByType(ScrollView) === reader).toBe(true);
  expect(foregroundNativeParent(reader) === body).toBe(true);
  expect(StyleSheet.flatten(body.props.style).opacity).toBe(1);
  expect(body.props.pointerEvents).toBe('none');
  expect(body.props.accessibilityElementsHidden).toBe(true);
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'foreground.enter'}));
  await act(async () => {});
});

test('iOS retains its default license-reader wrapper flattening', async () => {
  jest.replaceProperty(Platform, 'OS', 'ios');
  mockLive.foregroundFlow = {...flow('licenses'), notices: 'iOS native scroll hierarchy'};
  render(<View collapsable={false}><ForegroundFlowScreen /></View>);
  const reader = screen.UNSAFE_getByType(ScrollView);
  // Only the fixture's native screen exists above the direct scroll path on UIKit.
  expect(foregroundNativeParent(reader).props.pointerEvents).toBeUndefined();
  await act(async () => {});
});

function currentLibraryClose(){
  const options=mockNavigation.setOptions.mock.calls.filter(([options])=>typeof options.headerLeft==='function').at(-1)?.[0] as {headerLeft:()=>{props:{children:Array<{props:{onPress:()=>void}}>}}};
  return options.headerLeft().props.children[0]!.props.onPress;
}

test('same native-stack route with a replacement library visit installs its Close and retires the old callback',async()=>{
  jest.replaceProperty(Platform,'OS','android');mockToolbarReview=true;
  mockLive.foregroundFlow={...flow('createFilter'),id:'visit'};
  render(<ForegroundFlowScreen/>);await act(async()=>{});
  const oldClose=currentLibraryClose();
  // NAVIGATE can update params while preserving the current native-stack route key.
  // No host, graph, read epoch or other form value changed to mask owner identity.
  mockRoute={params:{id:'replacement',kind:'dns'}};
  mockLive={...mockLive,foregroundFlow:{...flow('createFilter'),id:'replacement'}};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  mockCommand.mockClear();
  await act(async()=>oldClose());
  expect(mockCommand).not.toHaveBeenCalled();
  await act(async()=>currentLibraryClose()());
  expect(mockCommand.mock.calls).toEqual([[{type:'foreground.dismiss',id:'replacement'}]]);
});

test('same route replacement renders the new library kind rather than retained old-kind state',async()=>{
  jest.replaceProperty(Platform,'OS','android');mockToolbarReview=true;
  mockLive.foregroundFlow={...flow('licenses'),notices:'Original native license reader'};
  render(<ForegroundFlowScreen/>);await act(async()=>{});
  mockRoute={params:{id:'replacement',kind:'dns'}};
  mockLive={...mockLive,foregroundFlow:{...flow('createFilter'),id:'replacement'}};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  expect(screen.getByRole('button',{name:'Create'})).toBeTruthy();
  expect(screen.queryByText('Original native license reader')).toBeNull();
});

test('one library owner retains its unsaved identity through concealment while a new owner starts fresh',async()=>{
  jest.replaceProperty(Platform,'OS','android');mockToolbarReview=true;
  mockCommand.mockImplementation((request:AppCommand)=>Promise.resolve(request.type==='foreground.identity'?{valid:true,dirty:false,message:''}:undefined));
  const rename={...flow('renameFilter'),name:'Original name'};
  mockLive.foregroundFlow=rename;
  render(<ForegroundFlowScreen/>);await act(async()=>{});
  nativeEdit(screen.getByLabelText('Name'),'Unsaved original draft');await act(async()=>{});
  const original=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Name')!;
  mockMayInteract=false;mockPresentationAuthority=false;mockLive={...mockLive,foregroundFlow:null};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  const concealed=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Name')!;
  expect(concealed).toBe(original);expect(concealed.props.value).toBe('Unsaved original draft');
  mockMayInteract=true;mockPresentationAuthority=true;mockLive={...mockLive,foregroundFlow:rename};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  expect(screen.getByLabelText('Name').props.value).toBe('Unsaved original draft');
  mockRoute={params:{id:'replacement',kind:'dns'}};
  mockLive={...mockLive,foregroundFlow:{...rename,id:'replacement',name:'Replacement name'}};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  expect(screen.getByLabelText('Name').props.value).toBe('Replacement name');
  expect(screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Name')).not.toBe(original);
});

function libraryFixture(){
  mockToolbarReview=true;
  mockLive={...mockLive,generation:'7',nativeHostID:'original-host',filters:[],
    libraryEditing:{active:true,hasChanges:false,deletions:[]},
    limits:{maxAllowedDomains:25,maxBlockedDomains:25,maxFilterRules:100,maxFilters:3,allowsCustomBlocklists:true,allowsCustomDNS:true},
    plus:{enabled:true,busy:false,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'',offers:[]},
    session:{activeFilterID:'active'} as AppSnapshot['session']};
}
function rejectedRequest(){
  let reject!:(error:Error)=>void;
  const promise=new Promise<void>((_resolve,fail)=>{reject=fail;});
  return {promise,reject};
}

test.each(['detached','ordinary'] as const)('unmounted Library cannot alert its successor from a late %s failure',async kind=>{
  libraryFixture();const old=rejectedRequest();const alerts=jest.spyOn(Alert,'alert');
  mockCommand.mockReturnValue(old.promise);
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(mockCommand).toHaveBeenCalledWith({type:'library.form',form:'create'});
  screen.unmount();mockCommand.mockResolvedValue(false);render(<LibraryScreen/>);
  await act(async()=>old.reject(kind==='detached'?new AppCommandDetachedError('afterStart'):new Error('Original retired operation failed')));
  expect(alerts).not.toHaveBeenCalled();
  mockCommand.mockClear();await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(mockCommand.mock.calls).toEqual([[{type:'library.form',form:'create'}]]);
});

test('retired graph completion cannot clear a successor Library flight or present its error',async()=>{
  libraryFixture();const old=rejectedRequest(),replacement=rejectedRequest();const alerts=jest.spyOn(Alert,'alert');
  mockCommand.mockReturnValueOnce(old.promise).mockReturnValue(replacement.promise);
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  mockLive={...mockLive,generation:'8'};screen.rerender(<LibraryScreen/>);await act(async()=>{});
  await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(mockCommand.mock.calls.filter(([request])=>request.type==='library.form')).toHaveLength(2);
  await act(async()=>old.reject(new Error('Original graph operation failed')));
  expect(alerts).not.toHaveBeenCalled();
  await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(mockCommand.mock.calls.filter(([request])=>request.type==='library.form')).toHaveLength(2);
  await act(async()=>replacement.reject(new Error('Current operation refused')));
  expect(alerts).toHaveBeenCalledTimes(1);
  expect(alerts).toHaveBeenCalledWith('Lava','Current operation refused');
});

test('current Library errors stay visible and release only their own busy slot',async()=>{
  libraryFixture();const alerts=jest.spyOn(Alert,'alert');
  mockCommand.mockRejectedValue(new Error('Current operation refused'));
  render(<LibraryScreen/>);
  await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(alerts).toHaveBeenCalledTimes(1);expect(alerts).toHaveBeenCalledWith('Lava','Current operation refused');
  await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  expect(mockCommand.mock.calls.filter(([request])=>request.type==='library.form')).toHaveLength(2);
  expect(alerts).toHaveBeenCalledTimes(2);
});

test.each(['feedback','vpnConfiguration'] as const)('%s owner replacement resets only its local public draft and binds native leaves to the new owner',async kind=>{
  jest.replaceProperty(Platform,'OS','android');mockToolbarReview=true;
  const first=kind==='feedback'?{...flow(kind),feedback:{...feedback,details:'Original feedback'}}
    :{...flow(kind),vpnEditor:{...flow(kind).vpnEditor!,name:'Original VPN'}};
  const label=kind==='feedback'?'Details':'Name';
  mockLive.foregroundFlow=first;render(<ForegroundFlowScreen/>);await act(async()=>{});
  nativeEdit(screen.getByLabelText(label),'Unsaved original text');await act(async()=>{});
  const original=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title===label)!;
  expect(original.props.value).toBe('Unsaved original text');
  mockMayInteract=false;mockPresentationAuthority=false;mockLive={...mockLive,foregroundFlow:null};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  const concealed=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title===label)!;
  expect(concealed).toBe(original);expect(concealed.props.value).toBe('Unsaved original text');
  mockMayInteract=true;mockPresentationAuthority=true;mockLive={...mockLive,foregroundFlow:first};
  screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  expect(screen.getByLabelText(label).props.value).toBe('Unsaved original text');
  mockRoute={params:{id:'replacement',kind:'dns'}};
  const replacement=kind==='feedback'?{...first,id:'replacement',feedback:{...feedback,details:'Replacement feedback'}}
    :{...first,id:'replacement',vpnEditor:{...flow('vpnConfiguration').vpnEditor!,name:'Replacement VPN'}};
  mockLive={...mockLive,foregroundFlow:replacement};screen.rerender(<ForegroundFlowScreen/>);await act(async()=>{});
  expect(screen.getByLabelText(label).props.value).toBe(kind==='feedback'?'Replacement feedback':'Replacement VPN');
  expect(screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title===label)).not.toBe(original);
  if(kind==='vpnConfiguration'){
    const confidential=screen.UNSAFE_getAllByType(Input).find(input=>input.props.kind==='wireGuard')!;
    expect(confidential.props.ownerID).toBe('replacement');
    expect(confidential.props.value).toBeUndefined();expect(confidential.props.defaultValue).toBeUndefined();
    expect(confidential.props.onChange).toBeUndefined();
  }
});

function connectedLibraryStore(nativeForm:(index:number)=>Promise<string>){
  const snapshot={...mockLive,schema:1,fullApp:true,revision:1,available:true,foreground:true,
    backgroundPrivacyCoverRequired:false,security:{...mockLive.security,unavailable:false}} as AppSnapshot;
  let revision=1,forms=0;
  const reply=(result:unknown,fields:Partial<AppSnapshot>={})=>JSON.stringify({snapshot:{...snapshot,...fields,revision:++revision},result});
  const port={getSnapshot:jest.fn(async()=>JSON.stringify(snapshot)),onSnapshot:()=>({remove:()=>{}}),
    command:jest.fn((raw:string)=>JSON.parse(raw).type==='library.form'?nativeForm(forms++):Promise.resolve(reply(true)))};
  const store=new AppStore(port,snapshot);const disconnect=store.connect();mockReviewApp=store;
  return {store,port,reply,disconnect};
}

test('a successful retired Library form retires only its exact native draft and cannot cancel or navigate its live same-app successor',async()=>{
  libraryFixture();const old=deferred<string>(),next=deferred<string>();
  const {store,port,reply,disconnect}=connectedLibraryStore(index=>index===0?old.promise:next.promise);
  const dispatch=jest.spyOn(store,'command');
  try{
    render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
    expect(port.command.mock.calls).toHaveLength(1);
    screen.unmount();render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
    // One real AppStore connection serializes the two forms; B's flight is already queued.
    expect(dispatch.mock.calls.filter(([request])=>request.type==='library.form')).toHaveLength(2);
    await act(async()=>old.resolve(reply('original-created',{filterSession:{id:'original-created',lease:'original-lease'}})));
    expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw)).filter(request=>request.type==='filter.cancel')).toEqual([{type:'filter.cancel',id:'original-created',retirementGeneration:'7',retirementLease:'original-lease'}]);
    expect(store.getSnapshot().snapshot).toMatchObject({generation:'7',nativeHostID:'original-host'});
    expect(dispatch).not.toHaveBeenCalledWith({type:'library.cancel'});
    expect(mockNavigation.navigate).not.toHaveBeenCalled();
    expect(port.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='library.form')).toHaveLength(2);
    await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
    expect(dispatch.mock.calls.filter(([request])=>request.type==='library.form')).toHaveLength(2);
    await act(async()=>next.resolve(reply('current-created')));
    expect(dispatch.mock.calls.filter(([request])=>request.type==='library.cancel')).toEqual([[{type:'library.cancel'}]]);
    expect(mockNavigation.navigate.mock.calls).toEqual([['Filter',{id:'current-created'}]]);
    expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw)).filter(request=>request.type==='filter.cancel')).toHaveLength(1);
  }finally{screen.unmount();old.resolve(reply(false));next.resolve(reply(false));disconnect();await act(async()=>{});}
});


test.each([
  ['missing lease',{filterSession:{id:'original-created'}}],
  ['successor draft',{filterSession:{id:'successor-created',lease:'successor-lease'}}],
  ['successor graph',{generation:'8',filterSession:{id:'original-created',lease:'new-graph-lease'}}],
] as const)('a retired Library Create with %s cannot borrow an unowned retirement receipt',async (_reason,fields)=>{
  libraryFixture();const old=deferred<string>();
  const {store,port,reply,disconnect}=connectedLibraryStore(()=>old.promise);
  const retirement=jest.spyOn(store,'retirePlusDestination');
  try{
    render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
    screen.unmount();render(<LibraryScreen/>);
    await act(async()=>old.resolve(reply('original-created',fields)));
    expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw)).filter(request=>request.type==='filter.cancel')).toEqual([]);
    expect(retirement).not.toHaveBeenCalled();
    expect(store.getSnapshot().snapshot).toMatchObject(fields);
    expect(mockNavigation.navigate).not.toHaveBeenCalled();
  }finally{screen.unmount();old.resolve(reply(false));disconnect();await act(async()=>{});}
});

test.each(['view','share'] as const)('retired native filter-action %s cannot dispatch or navigate its successor',async action=>{
  libraryFixture();mockLive={...mockLive,libraryEditing:{active:false,hasChanges:false,deletions:[]},
    filters:[{id:'inactive',name:'Inactive filter',count:'0',lists:[],frozen:false,empty:true,shareable:true,shareSummary:''}]};
  const choice=deferred<'view'|'share'>();jest.spyOn(NativeReview,'chooseFilterAction').mockReturnValue(choice.promise);
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Inactive filter')));
  expect(NativeReview.chooseFilterAction).toHaveBeenCalledTimes(1);
  mockLive={...mockLive,generation:'8'};screen.rerender(<LibraryScreen/>);await act(async()=>{});mockCommand.mockClear();
  await act(async()=>choice.resolve(action));
  expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
});

test('retired Restore and Discard confirmations cannot change a replacement Library',async()=>{
  libraryFixture();const alerts=jest.spyOn(Alert,'alert');
  render(<LibraryScreen/>);
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Restore default filters'})));
  const restore=alerts.mock.calls.at(-1)![2]!.find(button=>button.text==='Restore')!.onPress!;
  // The captured edit-mode callback shows the real source-owned Discard confirmation.
  mockLive={...mockLive,libraryEditing:{active:true,hasChanges:true,deletions:[]}};
  screen.rerender(<LibraryScreen/>);await act(async()=>currentLibraryClose()());
  const discard=alerts.mock.calls.at(-1)![2]!.find(button=>button.text==='Discard')!.onPress!;
  mockLive={...mockLive,generation:'8'};screen.rerender(<LibraryScreen/>);await act(async()=>{});mockCommand.mockClear();
  await act(async()=>{restore();discard();});
  expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
});

test('already-running Plus create returns its native destination for exact retirement without cancelling the replacement Library',async()=>{
  libraryFixture();mockLive={...mockLive,plus:{...mockLive.plus!,enabled:false},limits:{...mockLive.limits!,maxFilters:0}};
  const create=deferred<string>();mockCommand.mockReturnValue(create.promise);
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  const resume=mockUpgrade.mock.calls.at(-1)![1] as ()=>Promise<unknown>;
  let result!:Promise<unknown>;await act(async()=>{result=resume();});
  expect(mockCommand).toHaveBeenCalledWith({type:'library.form',form:'create'});
  screen.unmount();render(<LibraryScreen/>);mockCommand.mockClear();
  await act(async()=>create.resolve('owned-created'));
  await expect(result).resolves.toEqual({name:'Filter',params:{id:'owned-created'}});
  expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
});

test('retired Plus resume refuses a new native command before it starts',async()=>{
  libraryFixture();mockLive={...mockLive,plus:{...mockLive.plus!,enabled:false},limits:{...mockLive.limits!,maxFilters:0}};
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  const resume=mockUpgrade.mock.calls.at(-1)![1] as ()=>Promise<unknown>;
  mockLive={...mockLive,generation:'8'};screen.rerender(<LibraryScreen/>);await act(async()=>{});mockCommand.mockClear();
  await act(async()=>{await resume();});
  expect(mockCommand).not.toHaveBeenCalled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
});

test('current Library Plus create can resume while unfocused and returns its owned destination',async()=>{
  libraryFixture();mockLive={...mockLive,plus:{...mockLive.plus!,enabled:false},limits:{...mockLive.limits!,maxFilters:0}};
  render(<LibraryScreen/>);await act(async()=>fireEvent.press(screen.getByText('Add a filter')));
  const resume=mockUpgrade.mock.calls.at(-1)![1] as ()=>Promise<unknown>;
  mockFocused=false;screen.rerender(<LibraryScreen/>);await act(async()=>{});
  mockCommand.mockResolvedValue('current-plus-created');
  let result:unknown;await act(async()=>{result=await resume();});
  expect(result).toEqual({name:'Filter',params:{id:'current-plus-created'}});
  expect(mockCommand.mock.calls).toEqual([[{type:'library.form',form:'create'}],[{type:'library.cancel'}]]);
  expect(mockNavigation.navigate).not.toHaveBeenCalled();
});


test('Library Add waits for the cancelled native form result after its owner disappears before opening another form',async()=>{
  libraryFixture();const first=deferred<string>(),second=deferred<string>();
  const initial={...mockLive,schema:1,fullApp:true,revision:1,available:true,foreground:true,
    backgroundPrivacyCoverRequired:false,security:{...mockLive.security,unavailable:false}} as AppSnapshot;
  let revision=1,forms=0;
  const listeners=new Set<(value:string)=>void>();
  const snapshot=(fields:Partial<AppSnapshot>={})=>({...initial,...fields,revision:++revision});
  const reply=(result:unknown)=>JSON.stringify({snapshot:snapshot({foregroundFlow:null}),result});
  const native={getSnapshot:jest.fn(async()=>JSON.stringify(initial)),
    onSnapshot:(listener:(value:string)=>void)=>{listeners.add(listener);return {remove:()=>listeners.delete(listener)};},
    command:jest.fn((raw:string)=>JSON.parse(raw).type==='library.form'?(forms++===0?first.promise:second.promise):Promise.resolve(reply(true)))};
  const store=new AppStore(native,initial),disconnect=store.connect();mockReviewApp=store;
  const emit=(fields:Partial<AppSnapshot>)=>{
    const current=snapshot(fields);for(const listener of listeners)listener(JSON.stringify(current));
    mockLive=store.getSnapshot().snapshot!;screen.rerender(<LibraryScreen/>);
  };
  const add=()=>screen.getByRole('button',{name:'Add a filter'});
  try{
    render(<LibraryScreen/>);expect(add()).toBeEnabled();
    await act(async()=>fireEvent.press(add()));
    expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([{type:'library.form',form:'create'}]);
    await act(async()=>emit({foregroundFlow:{...flow('createFilter'),id:'cancelled-form'}}));
    expect(store.getSnapshot().snapshot?.foregroundFlow?.id).toBe('cancelled-form');
    // Native Close erases its owner before the source form Promise and Library flight settle.
    await act(async()=>emit({foregroundFlow:null,foregroundClosing:['cancelled-form']}));
    expect(store.getSnapshot().snapshot?.foregroundFlow).toBeNull();
    expect(add()).toBeDisabled();
    await act(async()=>fireEvent.press(add()));
    expect(native.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='library.form')).toHaveLength(1);
    await act(async()=>first.resolve(reply(false)));
    expect(add()).toBeEnabled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
    await act(async()=>fireEvent.press(add()));
    expect(native.command.mock.calls.filter(([raw])=>JSON.parse(raw).type==='library.form')).toHaveLength(2);
    expect(add()).toBeDisabled();
    await act(async()=>second.resolve(reply(false)));
    expect(add()).toBeEnabled();expect(mockNavigation.navigate).not.toHaveBeenCalled();
    expect(store.getSnapshot().snapshot?.libraryEditing).toMatchObject({active:true,hasChanges:false});
  }finally{screen.unmount();first.resolve(reply(false));second.resolve(reply(false));disconnect();await act(async()=>{});}
});


const readyFeedbackTree=(receipt:boolean|AndroidInteractionReceipt,state:FeedbackState=feedback)=><AndroidPresentationInteractionContext.Provider value={receipt}><FeedbackFlow id="visit" state={state} dismissAttempt={0}/></AndroidPresentationInteractionContext.Provider>;
const feedbackTopics=()=>mockCommand.mock.calls.filter(([request]:[AppCommand])=>request.type==='feedback.topic');
test('Android Feedback advertises disabled controls until native Ready and never replays an earlier topic or Continue',async()=>{
  jest.replaceProperty(Platform,'OS','android');
  const topicState={...feedback,step:0,furthest:0};
  mockCommand.mockImplementation((request:AppCommand)=>Promise.resolve(request.type==='feedback.change'?{...topicState,[request.field]:request.value}:undefined));
  const view=render(readyFeedbackTree(false,topicState));
  try{
    const row=screen.UNSAFE_getByType(ListRow),oldPress=row.props.onPress;
    expect(row.props.disabled).toBe(true);
    expect(screen.getByRole('button',{name:'Continue'})).toBeDisabled();
    await act(async()=>{oldPress();fireEvent.press(screen.getByRole('button',{name:'Continue'}));});
    expect(feedbackTopics()).toHaveLength(0);expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'feedback.step'}));
    const receipt={app:mockReviewApp as AppStore,key:'actual-native-ready-1'};
    view.rerender(readyFeedbackTree(receipt,topicState));await act(async()=>{});
    expect(feedbackTopics()).toHaveLength(0);expect(screen.UNSAFE_getByType(ListRow).props.disabled).toBe(false);
    await act(async()=>fireEvent.press(screen.getByText('I have a suggestion')));
    expect(feedbackTopics()).toEqual([[{type:'feedback.topic',id:'visit',topic:'suggestion'}]]);
    await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Continue'})));
    expect(mockCommand).toHaveBeenCalledWith({type:'feedback.step',id:'visit',next:true});
    view.rerender(readyFeedbackTree(false,feedback));
    expect(screen.UNSAFE_getAllByType(FormField).every(field=>field.props.editable===false)).toBe(true);
    const field=screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Details')!;
    const before=field.props.value,changes=mockCommand.mock.calls.filter(([request]:[AppCommand])=>request.type==='feedback.change').length;await act(async()=>field.props.onChangeText('An obsolete field callback'));
    expect(screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Details')!.props.value).toBe(before);
    expect(mockCommand.mock.calls.filter(([request]:[AppCommand])=>request.type==='feedback.change')).toHaveLength(changes);
  }finally{view.unmount();}
});

test.each(['receipt','graph','revision','background','unmounted'] as const)('a delayed Feedback error cannot alert after its native Ready %s retires',async boundary=>{
  jest.replaceProperty(Platform,'OS','android');
  const old=rejectedRequest(),alerts=jest.spyOn(Alert,'alert');
  mockCommand.mockImplementation((request:AppCommand)=>request.type==='feedback.topic'?old.promise:Promise.resolve(undefined));
  const state={...feedback,step:0,furthest:0};
  mockLive={...mockLive,generation:'graph-1',nativeHostID:'host-1'};
  const receipt={app:mockReviewApp as AppStore,key:'actual-ready-1'};
  const view=render(readyFeedbackTree(receipt,state));
  try{
    await act(async()=>fireEvent.press(screen.getByText('I have a suggestion')));expect(feedbackTopics()).toHaveLength(1);
    if(boundary==='unmounted')view.unmount();
    else if(boundary==='background')Object.defineProperty(AppState,'currentState',{configurable:true,value:'background'});
    else{
      if(boundary==='graph')mockLive={...mockLive,generation:'graph-2'};
      if(boundary==='revision')mockLive={...mockLive,security:{...mockLive.security,readRevision:2} as AppSnapshot['security']};
      view.rerender(readyFeedbackTree({app:mockReviewApp as AppStore,key:'actual-ready-2'},state));
    }
    await act(async()=>old.reject(new Error('A retired topic request failed.')));
    expect(alerts).not.toHaveBeenCalled();expect(feedbackTopics()).toHaveLength(1);
  }finally{if(boundary!=='unmounted')view.unmount();old.reject(new Error('fixture cleanup'));}
});

test('a current native-ready Feedback error remains visible and isolated or iOS adapters keep their existing admission',async()=>{
  jest.replaceProperty(Platform,'OS','android');const alerts=jest.spyOn(Alert,'alert');
  mockCommand.mockImplementation((request:AppCommand)=>request.type==='feedback.topic'?Promise.reject(new Error('Current native error')):Promise.resolve(undefined));
  const state={...feedback,step:0,furthest:0};
  const view=render(readyFeedbackTree(true,state));
  await act(async()=>fireEvent.press(screen.getByText('I have a suggestion')));
  expect(alerts).toHaveBeenCalledWith('Lava','Current native error');
  jest.replaceProperty(Platform,'OS','ios');view.rerender(readyFeedbackTree(false,state));
  expect(screen.UNSAFE_getByType(ListRow).props.disabled).toBe(false);
  view.unmount();
});


test('a dirty native Back waits for the same Feedback owner’s actual held Ready, then asks once without discarding',async()=>{
  jest.replaceProperty(Platform,'OS','android');
  const alerts=jest.spyOn(Alert,'alert'),ack=deferred<string>();
  const frames=new Map<number,(time:number)=>void>();let nextFrame=0;
  jest.spyOn(globalThis,'requestAnimationFrame').mockImplementation(callback=>{frames.set(++nextFrame,callback);return nextFrame;});
  jest.spyOn(globalThis,'cancelAnimationFrame').mockImplementation(key=>{if(key!=null)frames.delete(key);});
  const dirty={...feedback,dirty:true,details:'Unsent native draft'};
  mockLive={schema:1,fullApp:true,revision:1,generation:'7',nativeHostID:'original-host',foreground:true,available:true,
    security:{unavailable:false,recoveryRequired:false,protectedSurfaces:[],authenticatedSurfaces:[],ownerRevision:'7',readRevision:1,sourceRevision:'source',displayClearRevision:'clear',showBiometrics:false,canEnableBiometrics:false,biometricTitle:'Biometrics',status:''},
    foregroundFlow:{...flow('feedback'),feedback:dirty}};
  const reply=()=>JSON.stringify({snapshot:mockLive,result:null});
  const native={getSnapshot:jest.fn(async()=>JSON.stringify(mockLive)),onSnapshot:()=>({remove(){}}),
    command:jest.fn((raw:string)=>JSON.parse(raw).type==='navigation.presentationReady'?ack.promise:Promise.resolve(reply()))};
  const store=new AppStore(native),disconnect=store.connect();mockReviewApp=store;
  await act(async()=>store.refresh());
  const Frame=({attempt}:{attempt:number})=>{
    const receipt=useAndroidPresentationReady({serial:1,routeKey:'feedback-route',ownerID:'foreground:visit',surface:'appSettings',accepted:true},true,true);
    return <AndroidPresentationInteractionContext.Provider value={receipt}><FeedbackFlow id="visit" state={dirty} dismissAttempt={attempt}/></AndroidPresentationInteractionContext.Provider>;
  };
  const flush=async()=>{for(let turn=0;turn<8;turn++){
    await act(async()=>{await Promise.resolve();});if(!frames.size)break;
    const callbacks=[...frames.values()];frames.clear();await act(async()=>{callbacks.forEach(callback=>callback(turn*16));});
  }};
  const view=render(<Frame attempt={0}/>);
  const dismissals=()=>native.command.mock.calls.map(([raw])=>JSON.parse(raw) as AppCommand).filter(command=>command.type==='foreground.dismiss');
  try{
    await flush();expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw).type).filter(type=>type==='navigation.presentationReady')).toHaveLength(1);
    expect(screen.UNSAFE_getAllByType(FormField).every(field=>!field.props.editable)).toBe(true);
    view.rerender(<Frame attempt={1}/>);await flush();
    expect(alerts).not.toHaveBeenCalled();expect(dismissals()).toEqual([]);
    await act(async()=>ack.resolve(reply()));await flush();
    expect(alerts).toHaveBeenCalledTimes(1);expect(alerts).toHaveBeenCalledWith('Discard feedback?','Your feedback draft will be removed.',expect.any(Array));
    expect(dismissals()).toEqual([]);
    view.rerender(<Frame attempt={1}/>);await flush();expect(alerts).toHaveBeenCalledTimes(1);
    const buttons=alerts.mock.calls[0]![2]!;await act(async()=>buttons.find(button=>button.text==='Discard')!.onPress!());
    expect(dismissals()).toEqual([{type:'foreground.dismiss',id:'visit'}]);
  }finally{view.unmount();ack.resolve(reply());disconnect();}
});


const dismissalFeedbackTree=(receipt:boolean|AndroidInteractionReceipt,attempt:number,state:FeedbackState={...feedback,dirty:true,details:'Unsent native draft'},id='visit')=><AndroidPresentationInteractionContext.Provider value={receipt}><FeedbackFlow id={id} state={state} dismissAttempt={attempt}/></AndroidPresentationInteractionContext.Provider>;
test.each(['app','owner','graph','host'] as const)('pending Feedback Back cannot adopt a replacement %s when Ready returns',async boundary=>{
  jest.replaceProperty(Platform,'OS','android');const alerts=jest.spyOn(Alert,'alert');
  mockLive={...mockLive,generation:'7',nativeHostID:'original-host',foregroundFlow:flow('feedback')};
  const view=render(dismissalFeedbackTree(false,0));
  try{
    view.rerender(dismissalFeedbackTree(false,1));await act(async()=>{});expect(alerts).not.toHaveBeenCalled();
    let id='visit';
    if(boundary==='app')mockReviewApp={...mockApp};
    else if(boundary==='owner'){id='replacement';mockLive={...mockLive,foregroundFlow:{...flow('feedback'),id}};}
    else if(boundary==='graph')mockLive={...mockLive,generation:'8'};
    else mockLive={...mockLive,nativeHostID:'replacement-host'};
    const receipt={app:mockReviewApp as AppStore,key:'replacement-ready'};
    view.rerender(dismissalFeedbackTree(receipt,1,undefined,id));await act(async()=>{});
    expect(alerts).not.toHaveBeenCalled();expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'foreground.dismiss'}));
    view.rerender(dismissalFeedbackTree(receipt,0,undefined,id));await act(async()=>{}); // A new owner’s counter reset is not Back.
    expect(alerts).not.toHaveBeenCalled();
    view.rerender(dismissalFeedbackTree(receipt,1,undefined,id));await act(async()=>{});
    expect(alerts).toHaveBeenCalledTimes(1);expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'foreground.dismiss'}));
  }finally{view.unmount();}
});

test('pending same-owner Feedback Back waits through a privacy pause and renewed read revision without replaying a mutation',async()=>{
  jest.replaceProperty(Platform,'OS','android');const alerts=jest.spyOn(Alert,'alert');
  mockLive={...mockLive,generation:'7',nativeHostID:'original-host'};
  const view=render(dismissalFeedbackTree(false,0));
  try{
    AppState.currentState='background';mockMayInteract=false;mockPresentationAuthority=false;
    view.rerender(dismissalFeedbackTree(false,1));await act(async()=>{});
    mockLive={...mockLive,security:{...mockLive.security,readRevision:2} as AppSnapshot['security']};
    view.rerender(dismissalFeedbackTree(false,1));await act(async()=>{});expect(alerts).not.toHaveBeenCalled();
    AppState.currentState='active';mockMayInteract=true;mockPresentationAuthority=true;
    view.rerender(dismissalFeedbackTree({app:mockReviewApp as AppStore,key:'renewed-ready'},1));await act(async()=>{});
    expect(alerts).toHaveBeenCalledTimes(1);expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'foreground.dismiss'}));
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'feedback.submit'}));
  }finally{view.unmount();}
});

test('Feedback discard confirmation keeps its exact Ready receipt and cannot borrow a later grant',async()=>{
  jest.replaceProperty(Platform,'OS','android');const alerts=jest.spyOn(Alert,'alert');
  mockLive={...mockLive,generation:'7',nativeHostID:'original-host'};
  const receipt={app:mockReviewApp as AppStore,key:'original-ready'},view=render(dismissalFeedbackTree(receipt,0));
  try{
    view.rerender(dismissalFeedbackTree(receipt,1));await act(async()=>{});
    const oldDiscard=alerts.mock.calls[0]![2]!.find(button=>button.text==='Discard')!.onPress!;
    view.rerender(dismissalFeedbackTree(false,1));await act(async()=>oldDiscard());
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'foreground.dismiss'}));
    mockLive={...mockLive,security:{...mockLive.security,readRevision:2} as AppSnapshot['security']};
    const fresh={app:mockReviewApp as AppStore,key:'fresh-ready'};
    view.rerender(dismissalFeedbackTree(fresh,1));await act(async()=>oldDiscard());
    expect(alerts).toHaveBeenCalledTimes(1);expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'foreground.dismiss'}));
    view.rerender(dismissalFeedbackTree(fresh,2));await act(async()=>{});
    const currentDiscard=alerts.mock.calls[1]![2]!.find(button=>button.text==='Discard')!.onPress!;
    await act(async()=>currentDiscard());
    expect(mockCommand.mock.calls.filter(([request])=>request.type==='foreground.dismiss')).toEqual([[{type:'foreground.dismiss',id:'visit'}]]);
  }finally{view.unmount();}
});

test('a clean native Feedback Back remains an exact read-free cancellation before Ready',async()=>{
  jest.replaceProperty(Platform,'OS','android');const alerts=jest.spyOn(Alert,'alert');
  const view=render(dismissalFeedbackTree(false,0,feedback));
  try{
    view.rerender(dismissalFeedbackTree(false,1,feedback));await act(async()=>{});
    expect(mockCommand.mock.calls.filter(([request])=>request.type==='foreground.dismiss')).toEqual([[{type:'foreground.dismiss',id:'visit'}]]);
    expect(alerts).not.toHaveBeenCalled();
    view.rerender(dismissalFeedbackTree(true,1,feedback));await act(async()=>{});
    expect(mockCommand.mock.calls.filter(([request])=>request.type==='foreground.dismiss')).toHaveLength(1);
  }finally{view.unmount();}
});


import {DeviceEventEmitter,NativeModules} from 'react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
const mockCustomEntryPort={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=mockCustomEntryPort;
const {androidRuntimeForHost}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');

async function customEntryRetirementFixture(generation:string|null='7',initialToken='visit',kind:'dns'|'blocklist'='dns') {
  const entry={id:initialToken,kind,name:'Retained DNS',primary:'tls://dns.example',secondary:'',allowed:true,overBudget:false};
  let packet:ProductSnapshot={...productFixture,nativeHostID:'custom-source',revision:80,generation,customEntry:entry};
  let currentHost='custom-source',ownedToken:string|null=initialToken;
  mockCustomEntryPort.getSnapshot.mockReset().mockRejectedValue(new Error('An unbound read is forbidden.'));
  mockCustomEntryPort.getSnapshotForHost.mockReset().mockImplementation(async(id:string)=>{
    if(id!==currentHost)throw Object.assign(new Error('stale-host'),{code:'stale-host'});
    return JSON.stringify(packet);
  });
  mockCustomEntryPort.command.mockReset().mockImplementation(async(payload:string)=>{
    const request=JSON.parse(payload) as {schema?:number;type:string;id?:string;generation?:string;retirementGeneration?:string;nativeHostID?:string};
    if(request.schema===1&&request.type==='customEntry.dismiss'&&request.retirementGeneration==='7') {
      if(request.id===ownedToken)ownedToken=null;
      return JSON.stringify({status:'completed',projectionFailure:request.nativeHostID===currentHost?'unavailable':'stale-host'});
    }
    if(request.nativeHostID!==currentHost)return JSON.stringify({status:'rejected',reason:'stale-host'});
    return JSON.stringify({status:'completed',snapshot:{...packet,revision:packet.revision+1}});
  });
  const app=new AppStore(androidRuntimeForHost('custom-source')),stop=app.connect();
  await app.refresh();mockReviewApp=app;mockLive=app.getSnapshot().snapshot!;
  const view=render(entryTree());
  return {app,stop,view,token:()=>ownedToken,
    retireHost:()=>{currentHost='custom-successor';},
    replaceToken:(id:string)=>{ownedToken=id;},
    publish:(value:Partial<ProductSnapshot>)=>{
      packet={...packet,...value,revision:packet.revision+1};
      DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(packet));
      mockLive=app.getSnapshot().snapshot!;view.rerender(entryTree());
    }};
}
const customEntryRequests=()=>mockCustomEntryPort.command.mock.calls.map(([payload])=>JSON.parse(payload as string));

test.each(['dns','blocklist'] as const)('Android custom-entry %s teardown retires its exact token after the native host and real AppStore disconnect before React unmount',async(kind)=>{
  mockRoute.params.kind=kind;const f=await customEntryRetirementFixture('7','visit',kind);
  const reads=mockCustomEntryPort.getSnapshotForHost.mock.calls.length;
  f.retireHost();f.stop();
  await act(async()=>{f.view.unmount();});
  expect(customEntryRequests()).toEqual([{schema:1,type:'customEntry.dismiss',id:'visit',retirementGeneration:'7',generation:'7',nativeHostID:'custom-source'}]);
  expect(f.token()).toBeNull();
  expect(mockCustomEntryPort.getSnapshotForHost).toHaveBeenCalledTimes(reads);
  expect(mockCustomEntryPort.getSnapshot).not.toHaveBeenCalled();
});

test('Android custom-entry live cleanup uses the same exact local token capability without authorization or a new read',async()=>{
  const f=await customEntryRetirementFixture();
  const reads=mockCustomEntryPort.getSnapshotForHost.mock.calls.length;
  try {await act(async()=>f.view.unmount());
    expect(f.token()).toBeNull();expect(customEntryRequests()).toEqual([expect.objectContaining({type:'customEntry.dismiss',id:'visit',retirementGeneration:'7'})]);
    expect(mockCustomEntryPort.getSnapshotForHost).toHaveBeenCalledTimes(reads);
  }finally{f.stop();}
});

test('Android custom-entry privacy concealment retains the form and draft until an actual unmount, whose teardown needs no private grant',async()=>{
  const f=await customEntryRetirementFixture();
  try {
    nativeEdit(screen.getByLabelText('Primary DNS'),'tls://unfinished.example');
    await act(async()=>{
      Object.defineProperty(AppState,'currentState',{configurable:true,value:'background'});mockMayInteract=false;
      f.publish({foreground:false,available:false,customEntry:null});
      mockLive={...mockLive,customEntry:null};f.view.rerender(entryTree());
    });
    expect(screen.getByTestId('custom-entry-privacy-cover')).toBeTruthy();
    expect(screen.UNSAFE_getAllByType(FormField).find(field=>field.props.title==='Primary DNS')!.props.value).toBe('tls://unfinished.example');
    expect(customEntryRequests()).toEqual([]);expect(f.token()).toBe('visit');
    await act(async()=>f.view.unmount());
    expect(customEntryRequests()).toEqual([expect.objectContaining({id:'visit',retirementGeneration:'7'})]);expect(f.token()).toBeNull();
  }finally{f.stop();}
});

test.each(['generation','token','kind'] as const)('Android custom-entry cleanup never adopts a successor %s from its same-app snapshot',async(boundary)=>{
  const f=await customEntryRetirementFixture();
  try {
    await act(async()=>{
      mockFocused=false;const entry=mockLive.customEntry!;
      f.publish(boundary==='generation'?{generation:'8'}:{customEntry:{...entry,...(boundary==='token'?{id:'successor-token'}:{kind:'blocklist' as const})}});
      f.replaceToken('successor-token');
    });
    await act(async()=>f.view.unmount());
    expect(customEntryRequests()).toEqual([]);expect(f.token()).toBe('successor-token');
  }finally{f.stop();}
});

test('Android custom-entry cleanup fails closed when its originating graph was unavailable',async()=>{
  const f=await customEntryRetirementFixture(null);
  try {await act(async()=>f.view.unmount());expect(customEntryRequests()).toEqual([]);expect(f.token()).toBe('visit');}
  finally{f.stop();}
});

test('Android custom-entry cold metadata captures only its first matching committed source and preserves a redacted unmount receipt',async()=>{
  const f=await customEntryRetirementFixture(null);
  try {
    await act(async()=>f.publish({generation:'7'}));
    f.retireHost();f.stop();
    await act(async()=>f.view.unmount());
    expect(customEntryRequests()).toEqual([expect.objectContaining({id:'visit',generation:'7',retirementGeneration:'7'})]);expect(f.token()).toBeNull();
  }finally{f.stop();}
});

test('Android custom-entry pending retirement settles once and cannot cancel a fresh token installed before its reply',async()=>{
  const f=await customEntryRetirementFixture(),reply=deferred<string>();
  mockCustomEntryPort.command.mockImplementationOnce(()=>reply.promise);
  try {
    await act(async()=>f.view.unmount());
    expect(customEntryRequests()).toHaveLength(1);f.replaceToken('successor-token');
    await act(async()=>reply.resolve(JSON.stringify({status:'completed',projectionFailure:'stale-host'})));
    expect(customEntryRequests()).toHaveLength(1);expect(f.token()).toBe('successor-token');
  }finally{f.stop();}
});

test('custom-entry iOS teardown retains the original ordinary dismiss contract',async()=>{
  const descriptor=Object.getOwnPropertyDescriptor(Platform,'OS')!;
  try {
    Object.defineProperty(Platform,'OS',{configurable:true,value:'ios'});
    Object.defineProperty(mockNavigation,'addListener',{configurable:true,value:()=>()=>{}});
    const view=render(entryTree());await act(async()=>view.unmount());
    expect(mockCommand).toHaveBeenCalledTimes(1);expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.dismiss',id:'visit'});
  }finally{Object.defineProperty(Platform,'OS',descriptor);Reflect.deleteProperty(mockNavigation,'addListener');}
});


test('Android custom-entry cannot capture a different native token as its teardown source',async()=>{
  mockFocused=false;const f=await customEntryRetirementFixture('7','foreign-token');
  try {await act(async()=>f.view.unmount());expect(customEntryRequests()).toEqual([]);expect(f.token()).toBe('foreign-token');}
  finally{f.stop();}
});

test('Android custom-entry app replacement retires only the old opaque token, never the new host’s draft',async()=>{
  const f=await customEntryRetirementFixture();f.retireHost();f.replaceToken('successor-token');
  await act(async()=>f.publish({nativeHostID:'custom-successor',customEntry:{...mockLive.customEntry!,id:'successor-token'}}));
  const next=new AppStore(androidRuntimeForHost('custom-successor')),stopNext=next.connect();await next.refresh();
  try {
    await act(async()=>{mockReviewApp=next;mockLive=next.getSnapshot().snapshot!;mockRoute={params:{id:'successor-token',kind:'dns'}};f.view.rerender(entryTree());});
    expect(customEntryRequests()).toEqual([expect.objectContaining({id:'visit',nativeHostID:'custom-source',retirementGeneration:'7'})]);
    expect(f.token()).toBe('successor-token');
    await act(async()=>f.view.unmount());
    expect(customEntryRequests()).toHaveLength(2);expect(customEntryRequests()[1]).toMatchObject({id:'successor-token',nativeHostID:'custom-successor',retirementGeneration:'7'});expect(f.token()).toBeNull();
  }finally{f.stop();stopNext();}
});


// Android child command failures belong to the captured native editor visit,
// independently of a later frame becoming interactive on the same AppStore.
function childVpnEditorFixture(canSave=false){
  const editor=flow('vpnConfiguration');
  mockLive={...mockLive,generation:'7',nativeHostID:'child-host',
    security:{...mockLive.security,readRevision:1,ownerRevision:'child-owner',sourceRevision:'source-1'} as AppSnapshot['security'],
    foregroundFlow:{...editor,vpnEditor:{...editor.vpnEditor!,canSave}}};
}
function childVpnPending<T>(){
  let resolve!:(value:T)=>void,reject!:(error:unknown)=>void;
  const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};
}
const childVpnTree=(failed?:jest.Mock)=>{
  const editor=mockLive.foregroundFlow!;
  return <VPNEditorFlow id={editor.id} state={editor.vpnEditor!} dismissAttempt={0} onEntryFailed={failed}/>;
};
test.each(['beforeStart','afterStart'] as const)('Android child VPN Enter never presents typed detached %s as a current failure',async phase=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  const error=new AppCommandDetachedError(phase),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');
  mockCommand.mockRejectedValueOnce(error);
  render(childVpnTree(failed));await act(async()=>{});
  expect(mockCommand.mock.calls).toEqual([[{type:'vpnEditor.enter',id:'visit'}]]);
  expect(failed).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();
});
test.each(['id','app','generation','host','readRevision','owner'] as const)('Android child VPN Enter refuses an ordinary failure from the retired %s owner',async boundary=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  const pending=childVpnPending<void>(),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');
  mockCommand.mockReturnValueOnce(pending.promise);const view=render(childVpnTree(failed));await act(async()=>{});
  if(boundary==='id')mockLive={...mockLive,foregroundFlow:{...mockLive.foregroundFlow!,id:'successor'}};
  if(boundary==='app')mockReviewApp={...mockApp};
  if(boundary==='generation')mockLive={...mockLive,generation:'8'};
  if(boundary==='host')mockLive={...mockLive,nativeHostID:'successor-host'};
  if(boundary==='readRevision')mockLive={...mockLive,security:{...mockLive.security,readRevision:2} as AppSnapshot['security']};
  if(boundary==='owner')mockLive={...mockLive,security:{...mockLive.security,ownerRevision:'successor-owner'} as AppSnapshot['security']};
  view.rerender(childVpnTree());await act(async()=>pending.reject(new Error('Retired editor failed.')));
  expect(failed).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();
});
test('Android child VPN Enter cannot notify its parent after unmount',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  const pending=childVpnPending<void>(),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');mockCommand.mockReturnValueOnce(pending.promise);
  const view=render(childVpnTree(failed));await act(async()=>{});view.unmount();
  await act(async()=>pending.reject(new Error('Unmounted editor failed.')));
  expect(failed).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();
});
test('Android child VPN current readonly Enter refusal still reaches its parent and ordinary error alert',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  mockLive.foregroundFlow!.vpnEditor!.canEdit=false;
  const failure=new Error('Current editor authorization failed.'),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');mockCommand.mockRejectedValueOnce(failure);
  render(childVpnTree(failed));await act(async()=>{});
  expect(failed).toHaveBeenCalledTimes(1);expect(failed).toHaveBeenCalledWith(failure);
  expect(alert).toHaveBeenCalledWith('Lava',failure.message);
});
test('Android child VPN current authentication cancellation keeps entry settlement but native Alert stays silent',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  const failure=new Error('Authentication cancelled.'),failed=jest.fn(),nativeAlert=jest.spyOn(require('react-native').Alert,'alert');mockCommand.mockRejectedValueOnce(failure);
  render(childVpnTree(failed));await act(async()=>{});
  expect(failed).toHaveBeenCalledWith(failure);expect(nativeAlert).not.toHaveBeenCalled();
});
test('Android child VPN file chooser detached completion never creates an alert',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();const alert=jest.spyOn(Alert,'alert');
  render(childVpnTree());await act(async()=>{});mockCommand.mockClear();mockCommand.mockRejectedValueOnce(new AppCommandDetachedError('afterStart'));
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Choose file'})));
  expect(mockCommand.mock.calls).toEqual([[{type:'vpnEditor.file',id:'visit'}]]);expect(alert).not.toHaveBeenCalled();
});
test('Android child VPN current Save error survives ordinary source ACK and readonly publication',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture(true);const pending=childVpnPending<void>(),alert=jest.spyOn(Alert,'alert');
  mockCommand.mockImplementation((command:AppCommand)=>command.type==='vpnEditor.save'?pending.promise:Promise.resolve());
  const view=render(childVpnTree());await act(async()=>{});mockCommand.mockClear();await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  expect(mockCommand.mock.calls).toEqual([[{type:'vpnEditor.name',id:'visit',name:''}],[{type:'vpnEditor.save',id:'visit'}]]);
  mockLive={...mockLive,security:{...mockLive.security,sourceRevision:'source-2'} as AppSnapshot['security'],foregroundFlow:{...mockLive.foregroundFlow!,vpnEditor:{...mockLive.foregroundFlow!.vpnEditor!,canEdit:false,canSave:false}}};
  view.rerender(childVpnTree());await act(async()=>pending.reject(new Error('Current save failed.')));
  expect(alert).toHaveBeenCalledTimes(1);expect(alert).toHaveBeenCalledWith('Lava','Current save failed.');
});
test('Android child VPN Save cannot present its old error after a renewed security grant',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture(true);const pending=childVpnPending<void>(),alert=jest.spyOn(Alert,'alert');
  mockCommand.mockImplementation((command:AppCommand)=>command.type==='vpnEditor.save'?pending.promise:Promise.resolve());
  const view=render(childVpnTree());await act(async()=>{});await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
  mockLive={...mockLive,security:{...mockLive.security,readRevision:2} as AppSnapshot['security']};view.rerender(childVpnTree());
  await act(async()=>pending.reject(new Error('Old grant save failed.')));expect(alert).not.toHaveBeenCalled();
});
test('Android child VPN real AppStore detached Enter remains silent after its command connection is replaced',async()=>{
  jest.replaceProperty(Platform,'OS','android');childVpnEditorFixture();
  const pending=childVpnPending<string>(),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');
  const initial={...mockLive,schema:1,fullApp:true,revision:1,foreground:true,available:true,backgroundPrivacyCoverRequired:false,
    security:{...mockLive.security,unavailable:false,recoveryRequired:false,protectedSurfaces:[]} } as AppSnapshot;
  const native={getSnapshot:jest.fn(async()=>JSON.stringify(initial)),onSnapshot:()=>({remove:()=>{}}),command:jest.fn((_raw:string)=>pending.promise)};
  const enter={type:'vpnEditor.enter',id:'visit'} as const;
  const store=new AppStore(native,initial),dispatch=jest.spyOn(store,'command'),stop=store.connect();mockReviewApp=store;
  const view=render(childVpnTree(failed));await act(async()=>{});
  expect(dispatch).toHaveBeenCalledTimes(1);
  const oldEnter=dispatch.mock.results[0]!.value as Promise<unknown>;
  expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([enter]);
  expect(store.getPresentationAuthority()).toBe(true);
  stop();
  // This fixture replaces a command connection; it never revokes presentation authority through AppState.
  expect(store.getPresentationAuthority()).toBe(true);
  await expect(store.command(enter)).rejects.toMatchObject({name:'AppCommandDetachedError',message:'The app screen closed before this action started.'});
  expect(native.command).toHaveBeenCalledTimes(1);
  const stopFresh=store.connect();
  try{
    await act(async()=>{await store.refresh();mockLive=store.getSnapshot().snapshot!;view.rerender(childVpnTree());});
    expect(store.getPresentationAuthority()).toBe(true);
    const detached=expect(oldEnter).rejects.toMatchObject({name:'AppCommandDetachedError',message:'The app screen closed while this action completed.'});
    await act(async()=>{pending.reject(new Error('Old native connection failed.'));await detached;});
    expect(failed).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();expect(native.command).toHaveBeenCalledTimes(1);
    native.command.mockResolvedValueOnce(JSON.stringify({snapshot:initial,result:'fresh-enter'}));
    await expect(store.command<string>(enter)).resolves.toBe('fresh-enter');
    expect(native.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([enter,enter]);
    expect(failed).not.toHaveBeenCalled();expect(alert).not.toHaveBeenCalled();
  }finally{view.unmount();stopFresh();}
});
test('iOS child VPN retains its original detached Enter callback and alert behavior',async()=>{
  jest.replaceProperty(Platform,'OS','ios');childVpnEditorFixture();
  const failure=new AppCommandDetachedError('afterStart'),failed=jest.fn(),alert=jest.spyOn(Alert,'alert');mockCommand.mockRejectedValueOnce(failure);
  render(childVpnTree(failed));await act(async()=>{});
  expect(failed).toHaveBeenCalledWith(failure);expect(alert).toHaveBeenCalledWith('Lava',failure.message);
});


test('DNS accepted Save delivered after child unmount cannot mutate its still-mounted shipping parent picker',async()=>{
  const f=await customEntryRetirementFixture(),reply=deferred<string>();
  mockCustomEntryPort.command.mockImplementationOnce(()=>reply.promise);
  const parent=(child:boolean)=><DNSEditorProvider retirementKey="native-owner"><DNSPickerSetup/>{child&&<CustomEntryScreen/>}</DNSEditorProvider>;
  try {
    await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Save'})));
    expect(customEntryRequests().filter(request=>request.type==='customEntry.save')).toHaveLength(1);
    // Only the child route retires; its real local picker provider and parent context stay mounted.
    await act(async()=>f.view.rerender(parent(false)));
    expect(f.token()).toBeNull();
    expect(customEntryRequests().filter(request=>request.type==='customEntry.dismiss')).toEqual([
      expect.objectContaining({id:'visit',retirementGeneration:'7',nativeHostID:'custom-source'})]);
    expect(dnsPicker.customPickerDraft).toEqual({token:'visit',context:'parent-ladder'});
    const staged={...productFixture,nativeHostID:'custom-source',revision:81,generation:'7',customEntry:null};
    await act(async()=>reply.resolve(JSON.stringify({status:'completed',value:choice,snapshot:staged})));
    expect(dnsPicker.customPickerDraft).toEqual({token:'visit',context:'parent-ladder'});
    expect(dnsPicker.draft.tiers).toEqual([]);
    expect(mockGoBack).not.toHaveBeenCalled();
    expect(customEntryRequests().filter(request=>request.type==='dns.tiers')).toEqual([]);
    expect(customEntryRequests().filter(request=>request.type==='customEntry.dismiss')).toHaveLength(1);
  } finally {reply.resolve(JSON.stringify({status:'completed',value:choice,snapshot:{...productFixture,nativeHostID:'custom-source',revision:82,generation:'7',customEntry:null}}));f.stop();await act(async()=>f.view.unmount());}
});
