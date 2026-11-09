import React, {useEffect,useState} from 'react';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockCommand = jest.fn();
const mockRoute: {params: any} = {params: {id: 'token-1', kind: 'dns'}};
const mockApp = {command: mockCommand};
const mockLive: any = {limits: {allowsCustomDNS: true}, dns: {tiersContext:'tiers-ctx',custom: {context: 'ctx-1'}, customDraftToken: 'token-1', customDraft: {
  id: 'custom-dns', transport: 'DoH', name: '', primary: '', secondary: '', metadata: '',
}}};
let mockFocused = true;
let mockToolbar: any;

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), goBack: mockGoBack, navigate: mockNavigate, getState: () => undefined}),
  useIsFocused: () => mockFocused,
  useRoute: () => mockRoute,
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({
    navigate: mockNavigate, goBack: mockGoBack,
    navigateToUpgrade: (params: () => unknown) => { mockNavigate('Upgrade', params()); },
  }),
}));
jest.mock('../canonical/review/ReviewContext', () => ({
  ...jest.requireActual('../canonical/review/ReviewContext'),
  useReview: () => ({app: mockApp, live: mockLive}),
}));
jest.mock('../canonical/review/scaffold', () => ({
  ...jest.requireActual('../canonical/review/scaffold'),
  useToolbar: (options: any) => { mockToolbar = options; },
}));

import {CustomDNSEntryScreen} from '../canonical/android/CustomDNSEntry';
import {DNSEditorProvider,useDNSEditor,sameDNS,selection} from '../canonical/review/dns-editor';
import {PlusIntentProvider,usePlusIntents,type PlusIntents} from '../canonical/review/plus-intents';
import {DNSPickerScreen} from '../canonical/review/DNSScreen';
import {deferred,installLifecycleMock} from './interruptionHarness';
let lifecycle:ReturnType<typeof installLifecycleMock>;
let intentsRef: PlusIntents; let intentId: string | undefined;
const pickerTier = {id:'device-dns|IP',name:'Saved device name',primary:'',secondary:'',transport:'IP',metadata:'Device',isEnabled:true};

function SeedEditor(){
  const {setDraft,beginCustomPicker}=useDNSEditor();const [ready,setReady]=useState(false);
  useEffect(()=>{setDraft({tiers:[],context:'tiers-ctx'});beginCustomPicker('token-1','tiers-ctx');setReady(true);},[]);
  return ready?<CustomDNSEntryScreen/>:null;
}
function Fixture(){return <DNSEditorProvider><SeedEditor/></DNSEditorProvider>;}

function CaptureIntents(){intentsRef=usePlusIntents();return null;}
function SeedPickerDraft(){const {setDraft}=useDNSEditor();useEffect(()=>{setDraft({tiers:[pickerTier],context:'tiers-ctx'});},[]);return null;}
function PickerFixture(){return <PlusIntentProvider><CaptureIntents/><DNSEditorProvider><SeedPickerDraft/><DNSPickerScreen/></DNSEditorProvider></PlusIntentProvider>;}
afterEach(()=>jest.restoreAllMocks());

beforeEach(() => {
  jest.clearAllMocks();
  lifecycle=installLifecycleMock();
  mockFocused=true;intentId=undefined;mockToolbar=undefined;
  mockNavigate.mockImplementation((_name:string,params:any)=>{intentId=params?.intent;});
  mockCommand.mockImplementation((request:{type:string;name?:string;primary?:string;secondary?:string})=>Promise.resolve(request.type==='dns.customStage'?{id:'custom-dns|DoH',transport:'DoH',metadata:'',name:request.name,primary:request.primary,secondary:request.secondary}:'token-1'));
  mockRoute.params.id = 'token-1';
  mockLive.limits={allowsCustomDNS:true};
  mockLive.dns.tiersContext='tiers-ctx';mockLive.dns.custom.context='ctx-1';
  mockLive.dns.customDraftToken = 'token-1';
  mockLive.dns.customDraft = {id:'custom-dns',transport:'DoH',name:'',primary:'',secondary:'',metadata:''};
});

test('collects the identity and stages it without committing the saved ladder', async () => {
  render(<Fixture />);
  // The picker already opened this token; entry must preserve its native draft.
  expect(mockCommand).not.toHaveBeenCalledWith({type: 'dns.customDraft'});
  fireEvent.changeText(screen.getByTestId('custom.dns.name'), ' Home ');
  fireEvent.changeText(screen.getByTestId('custom.dns.primary'), ' https://dns.example/query ');
  fireEvent.changeText(screen.getByTestId('custom.dns.secondary'), 'https://dns2.example/query');
  fireEvent.press(screen.getByRole('button', {name: 'Save resolver'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({
    type: 'dns.customStage', id:'token-1', context: 'ctx-1', name: 'Home', primary: 'https://dns.example/query', secondary: 'https://dns2.example/query',
  }));
  await waitFor(() => expect(mockGoBack).toHaveBeenCalled());
});

test('requires an address but admits the frozen optional name', async () => {
  render(<Fixture />);
  fireEvent.press(screen.getByRole('button', {name:'Save resolver'}));
  expect(await screen.findByText('Enter the resolver address.')).toBeTruthy();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
  fireEvent.changeText(screen.getByTestId('custom.dns.primary'),'https://dns.example/query');
  fireEvent.press(screen.getByRole('button',{name:'Save resolver'}));
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'dns.customStage',id:'token-1',context:'ctx-1',name:'',primary:'https://dns.example/query',secondary:''}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.custom'}));
});

test('surfaces a refused owner save and preserves the open editor', async () => {
  mockCommand.mockImplementation((request: {type: string}) => request.type === 'dns.customStage'
    ? Promise.reject(new Error('Custom DNS is not available on this device yet.'))
    : Promise.resolve('token-1'));
  render(<Fixture />);
  fireEvent.changeText(screen.getByTestId('custom.dns.name'), 'Home');
  fireEvent.changeText(screen.getByTestId('custom.dns.primary'), 'https://dns.example/query');
  fireEvent.press(screen.getByRole('button', {name: 'Save resolver'}));
  expect(await screen.findByText('Custom DNS is not available on this device yet.')).toBeTruthy();
  expect(mockGoBack).not.toHaveBeenCalled();
});

test('dismisses the draft session on teardown', async () => {
  const view = render(<Fixture />);
  expect(mockCommand).not.toHaveBeenCalledWith({type: 'dns.customDraft'});
  view.unmount();
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'customEntry.dismiss', id: 'token-1'}));
});

test('direct entry opens one native draft and hydrates only its returned token', async () => {
  mockRoute.params.id = '';
  render(<Fixture />);
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type:'dns.customDraft'}));
  await screen.findByTestId('custom.dns.primary');
  expect(mockCommand.mock.calls.filter(([command]) => command.type === 'dns.customDraft')).toHaveLength(1);
});

test.each(['unmount','background','source'] as const)('late direct-entry open after %s dismisses its exact token',async(interruption)=>{
  mockRoute.params.id='';const opened=deferred<string>();
  mockCommand.mockImplementation((request:{type:string})=>request.type==='dns.customDraft'?opened.promise:Promise.resolve(null));
  const view=render(<Fixture/>);
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'dns.customDraft'}));
  if(interruption==='unmount')view.unmount();
  else if(interruption==='background'){lifecycle.emit('background');lifecycle.emit('active');}
  else {mockLive.dns.tiersContext='changed-source';view.rerender(<Fixture/>);}
  await act(async()=>opened.resolve('late-token'));
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.dismiss',id:'late-token'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.custom'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
  expect(mockGoBack).not.toHaveBeenCalled();
});

test.each(['https://dns.example:8443/query?owner=persisted', 'dns.example:8853'])(
  'rehydrates the native persisted primary %s with a generic label and empty secondary', primary => {
    mockLive.dns.customDraft = {id:'custom-dns',transport:primary.startsWith('https:')?'DoH':'DoT',name:'Custom DNS',primary,secondary:'',metadata:''};
    render(<Fixture />);
    expect(screen.getByDisplayValue('Custom DNS')).toBeTruthy();
    expect(screen.getByDisplayValue(primary)).toBeTruthy();
    expect(screen.getByTestId('custom.dns.secondary').props.defaultValue).toBe('');
    expect(mockCommand).not.toHaveBeenCalledWith({type:'dns.customDraft'});
  },
);

test('preserves picker fields and user edits across later native snapshots', async () => {
  mockLive.dns.customDraft = {id:'custom-dns',transport:'DoT',name:'Office DNS',primary:'tls://first.example:853',secondary:'tls://second.example:8853',metadata:''};
  const view = render(<Fixture />);
  expect(screen.getByDisplayValue('Office DNS')).toBeTruthy();
  expect(screen.getByDisplayValue('tls://first.example:853')).toBeTruthy();
  expect(screen.getByDisplayValue('tls://second.example:8853')).toBeTruthy();
  fireEvent.changeText(screen.getByTestId('custom.dns.name'), '日本語 DNS');
  mockLive.dns.customDraft = {...mockLive.dns.customDraft,name:'Incoming snapshot',primary:'tls://different.example:853'};
  view.rerender(<Fixture />);
  fireEvent.press(screen.getByRole('button',{name:'Save resolver'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type:'dns.customStage',id:'token-1',context:'ctx-1',name:'日本語 DNS',primary:'tls://first.example:853',secondary:'tls://second.example:8853'}));
});

test('refuses a stale native draft token before saving old editor fields', async () => {
  mockLive.dns.customDraft = {...mockLive.dns.customDraft,name:'Custom DNS',primary:'dns.example:853'};
  const view = render(<Fixture />);
  mockLive.dns.customDraftToken = 'other-graph-token';
  view.rerender(<Fixture />);
  fireEvent.press(screen.getByRole('button',{name:'Save resolver'}));
  expect(await screen.findByText('This DNS editor has changed. Close it and open it again.')).toBeTruthy();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
});

test('the tier editor transmits a disabled row and treats it as the same resolver', () => {
  const enabled = {id:'mullvad|DoH',name:'Mullvad',primary:'',secondary:'',transport:'DoH',metadata:''};
  const disabled = {...enabled,isEnabled:false};
  // A disabled row still travels on the wire (so it is retained in place); an enabled row omits
  // the flag, matching the canonical iOS `DNSResolutionSelection` default.
  expect(selection(enabled)).not.toHaveProperty('isEnabled');
  expect(selection(disabled)).toMatchObject({id:'mullvad|DoH',isEnabled:false});
  // Enablement never changes resolver identity, so a disabled custom row is still a duplicate.
  expect(sameDNS(disabled,enabled)).toBe(true);
});

test('protocol-specific custom rows compare endpoints and keep raw stamp case',()=>{
  const custom={id:'custom-dns|DoQ',name:'',primary:'sdns://AbCd',secondary:'sdns://Secondary',transport:'DoQ',metadata:''};
  expect(sameDNS(custom,{...custom,name:'Renamed',isEnabled:false})).toBe(true);
  expect(sameDNS(custom,{...custom,primary:'sdns://aBcD'})).toBe(false);
  expect(sameDNS(custom,{...custom,secondary:'sdns://Different'})).toBe(false);
});

// The contextual Plus return for "Add custom DNS" runs while this picker is blurred behind
// Upgrade. Its `dns.customDraft` can settle after the user left that route; the late token must
// be retired (native ownership and local picker state) rather than adopted into a dead picker.
describe('custom-DNS upgrade resumption',()=>{
  function pickerLive(){
    mockRoute.params={target:'tier',index:0};
    mockLive.limits={allowsCustomDNS:false};
    mockLive.dns={editable:true,tiers:[pickerTier],tiersContext:'tiers-ctx',choices:[pickerTier],
      custom:{context:'ctx-1'},customDraftToken:'',customDraft:null};
  }
  function pressAddCustomDNS(){
    const item=mockToolbar?.unstable_headerRightItems?.().find((button:any)=>button.label==='Add custom DNS');
    expect(item).toBeDefined();act(()=>item.onPress());
  }
  async function openUpgradeEntry(view:ReturnType<typeof render>){
    await act(async()=>{});
    pressAddCustomDNS();
    expect(intentId).toBeDefined();
    // Navigating to Upgrade blurs the picker; the contextual return starts behind it.
    mockFocused=false;view.rerender(<PickerFixture/>);
  }
  async function assertPickerRecovered(view:ReturnType<typeof render>){
    mockFocused=true;view.rerender(<PickerFixture/>);
    // Save Selection is not blocked by a stale token: choosing a resolver enables it.
    fireEvent.press(screen.getByText('Saved device name'));
    const save=screen.getByRole('button',{name:'Save selection'});
    expect(save.props.accessibilityState.disabled).toBe(false);
    fireEvent.press(save);
    await act(async()=>{});
    expect(mockGoBack).toHaveBeenCalled();
    // A fresh contextual entry still registers its own return work.
    mockNavigate.mockClear();
    pressAddCustomDNS();
    expect(mockNavigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'customDNS',intent:expect.any(String)}));
  }

  test.each(['back','background','context'] as const)('a late upgrade resume after %s retires the draft and leaves the picker usable',async(interruption)=>{
    pickerLive();
    const opened=deferred<string>();
    mockCommand.mockImplementation((request:{type:string})=>request.type==='dns.customDraft'?opened.promise:Promise.resolve(null));
    const view=render(<PickerFixture/>);
    await openUpgradeEntry(view);
    const pending=(async()=>{const resume=intentsRef.take(intentId!);await resume!();})();
    if(interruption==='back'){mockFocused=true;view.rerender(<PickerFixture/>);}
    else if(interruption==='background'){lifecycle.emit('background');lifecycle.emit('active');}
    else {mockLive.dns.tiersContext='changed-context';view.rerender(<PickerFixture/>);}
    await act(async()=>{opened.resolve('late-token');});
    await pending;
    // The native draft is dismissed; no stage/durable write and no adoption.
    expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.dismiss',id:'late-token'});
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.custom'}));
    await assertPickerRecovered(view);
  });

  test('an abandoned upgrade resume retires the locally-owned picker token on focus regain',async()=>{
    pickerLive();
    const opened=deferred<string>();
    mockCommand.mockImplementation((request:{type:string})=>{
      if(request.type==='dns.customDraft')return opened.promise;
      if(request.type==='customEntry.dismiss'){mockLive.dns.customDraftToken='';return Promise.resolve(null);}
      return Promise.resolve(null);
    });
    const view=render(<PickerFixture/>);
    await openUpgradeEntry(view);
    const resume=intentsRef.take(intentId!);
    let destination:unknown;
    // Resolve while the picker is still blurred: the resume's own guard passes and it installs the
    // local picker token, exactly as production does when UpgradeScreen then abandons the result.
    await act(async()=>{opened.resolve('abandoned-token');destination=await resume!();});
    expect(destination).toEqual({name:'CustomEntry',params:{id:'abandoned-token',kind:'dns'}});
    // UpgradeScreen is no longer current, so it retires only the NATIVE custom-entry draft and
    // leaves the local picker token installed. On focus regain the picker must reconcile that token
    // against the live native owner and retire it, or Save Selection stays disabled.
    mockLive.dns.customDraftToken='';
    mockFocused=true;view.rerender(<PickerFixture/>);
    await act(async()=>{});
    await assertPickerRecovered(view);
  });

  test('a late upgrade resume after unmount dismisses its exact native token',async()=>{
    pickerLive();
    const opened=deferred<string>();
    mockCommand.mockImplementation((request:{type:string})=>request.type==='dns.customDraft'?opened.promise:Promise.resolve(null));
    const view=render(<PickerFixture/>);
    await openUpgradeEntry(view);
    const pending=(async()=>{const resume=intentsRef.take(intentId!);await resume!();})();
    view.unmount();
    await act(async()=>{opened.resolve('late-token');});
    await pending;
    expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.dismiss',id:'late-token'});
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.custom'}));
    expect(mockGoBack).not.toHaveBeenCalled();
  });

  test('a genuine verified return opens the native draft once and is never retired',async()=>{
    pickerLive();
    mockCommand.mockImplementation((request:{type:string})=>request.type==='dns.customDraft'?Promise.resolve('live-token'):Promise.resolve(null));
    const view=render(<PickerFixture/>);
    await openUpgradeEntry(view);
    const resume=intentsRef.take(intentId!);
    let destination:unknown;
    await act(async()=>{destination=await resume!();});
    expect(destination).toEqual({name:'CustomEntry',params:{id:'live-token',kind:'dns'}});
    expect(mockCommand.mock.calls.filter(([command]:any[])=>command.type==='dns.customDraft')).toHaveLength(1);
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'customEntry.dismiss'}));
    // The registry consumed the intent once; a second take finds nothing to resume.
    expect(intentsRef.take(intentId!)).toBeUndefined();
  });
});
