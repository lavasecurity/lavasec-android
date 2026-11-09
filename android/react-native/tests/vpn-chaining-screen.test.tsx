import React from 'react';
import {act,fireEvent, render, screen, waitFor,within} from '@testing-library/react-native';

// The Android presentation drives the canonical `vpn.*` commands through the app store and
// returns to React navigation for DNS/Upgrade. Both are mocked at their module boundary so the
// screen's own wiring is what is under test.
const mockNavNavigate = jest.fn();
const mockNavGoBack = jest.fn();
const mockSetOptions = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: mockSetOptions, navigate: jest.fn(), goBack: jest.fn()}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({
    navigate: mockNavNavigate,
    navigateToUpgrade: (params: () => unknown) => { mockNavNavigate('Upgrade', typeof params === 'function' ? params() : params); },
    goBack: mockNavGoBack,
    getState: () => ({routes: [], index: 0}),
    dispatch: jest.fn(),
  }),
  previewNotice: jest.fn(),
}));

import type {AppSnapshot, VPNDraft} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';
import {ReviewContext} from '../canonical/review/ReviewContext';
import {PlusIntentProvider,PlusIntents} from '../canonical/review/plus-intents';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {initialSession} from '../canonical/review/session';
import {VpnChainingScreen} from '../canonical/android/VpnChainingScreen';
import {configurePresentation,localized} from '../canonical/app/presentation';

type Vpn = NonNullable<AppSnapshot['vpn']>;

function projected(overrides: Partial<Vpn> = {}): Vpn {
  return {
    setup: false, enabled: false, canEnable: false, canEdit: true, fallback: false,
    canChangeFallback: true, needsPlus: false, busy: false, restriction: '', error: '',
    unavailable: false, needsRepair: false, generation: '', rows: [], draft: null, ...overrides,
  };
}

const command = jest.fn();
const app = {command} as unknown as AppStore;

function vpnValue(vpn?: Vpn, plus?: {enabled:boolean}) {
  return {
    app,
    live: {vpn,plus} as unknown as AppSnapshot,
    appearance: {} as never,
    look: 'original', setLook: () => {},
    draft: initialPreviewDraft, setDraft: () => {},
    savedDraft: initialPreviewDraft, setSavedDraft: () => {},
    session: initialSession, setSession: () => {},
  };
}
function renderVpn(vpn?: Vpn, plus?: {enabled:boolean}) {
  return render(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(vpn,plus) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>);
}
function renderScreen(vpn?: Vpn) {
  return renderVpn(vpn);
}

beforeEach(() => {
  command.mockReset();
  command.mockResolvedValue(null);
  mockNavNavigate.mockReset();
  mockNavGoBack.mockReset();
  mockSetOptions.mockReset();
});

test('an absent vpn family is an honest unavailable screen, never a blank host', () => {
  renderScreen(undefined);
  expect(screen.getByText('VPN chaining is not available')).toBeTruthy();
  expect(screen.queryByText('Add configuration')).toBeNull();
  expect(screen.queryByText('Chain DNS through my VPN')).toBeNull();
});

test('an unreadable owner surfaces the unavailable state, not a fabricated editor', () => {
  renderScreen(projected({unavailable: true}));
  expect(screen.getByText('VPN chaining is unavailable')).toBeTruthy();
  expect(screen.queryByText('Add configuration')).toBeNull();
});

test('a Free user taps the WireGuard prerequisite into the contextual Plus flow without touching vpn settings', async () => {
  renderScreen(projected({setup:false,needsPlus:true}));
  const toggle=screen.getByLabelText('I have a WireGuard configuration');
  expect(toggle.props.on).toBe(false);
  expect(toggle.props.enabled).not.toBe(false);
  expect(screen.queryByText('WireGuard configuration')).toBeNull();
  expect(screen.queryByText('Add configuration')).toBeNull();
  fireEvent(toggle,'valueChange',true);
  await waitFor(()=>expect(mockNavNavigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'vpn'})));
  expect(command.mock.calls.filter(([input])=>(input as {type:string}).type.startsWith('vpn.'))).toEqual([]);
});

test('an entitled, unconfigured user imports the first rotation with the not-configured generation', async () => {
  const draft: VPNDraft = {id: 'd1', revision: 0, changed: false, containsFullTunnel: false,
    rows: [{name: 'vpn.example.com:51820', mode: 'Split tunnel', isEnabled: true}]};
  command.mockImplementation(async (input: {type: string}) => input.type === 'vpn.begin' ? draft : null);
  renderScreen(projected({setup: false, generation: ''}));
  expect(screen.getByText('Add configuration')).toBeTruthy();
  fireEvent.press(screen.getByTestId('vpn.add-configuration'));
  await waitFor(() => expect(command).toHaveBeenCalledWith(expect.objectContaining({type: 'vpn.begin', generation: ''})));
  // The imported row is shown without any key material crossing.
  expect(await screen.findByText('vpn.example.com:51820')).toBeTruthy();
});

test('a configured user toggles enablement and the DNS fallback through canonical commands', async () => {
  renderScreen(projected({setup: true, enabled: false, canEnable: true, fallback: false, canChangeFallback: true, generation: '42',
    rows: [{name: 'vpn.example.com:51820', mode: 'Full tunnel', isEnabled: true}]}));
  expect(screen.getByText('vpn.example.com:51820')).toBeTruthy();
  expect(screen.getByText('Uses your enabled DNS choices when VPN DNS cannot answer. Known Wi-Fi sign-in pages can use Device DNS when enabled. Full-tunnel VPNs keep DNS inside the VPN.')).toBeOnTheScreen();
  fireEvent(screen.getByLabelText('Use VPN chaining'), 'valueChange', true);
  await waitFor(() => expect(command).toHaveBeenCalledWith({type: 'vpn.toggle', key: 'enabled', value: true}));
  fireEvent(screen.getByLabelText('Use Lava DNS settings as fallback'), 'valueChange', true);
  await waitFor(() => expect(command).toHaveBeenCalledWith({type: 'vpn.toggle', key: 'fallback', value: true}));
});

test('enablement is refused when the owner cannot enable and is not already on', () => {
  renderScreen(projected({setup: true, enabled: false, canEnable: false, canChangeFallback: true,
    rows: [{name: 'vpn.example.com:51820', mode: 'Split tunnel', isEnabled: true}]}));
  // The Android switch host maps `disabled` onto `enabled` (gotcha 2026-09-13).
  expect(screen.getByLabelText('Use VPN chaining').props.enabled).toBe(false);
});

test('full-tunnel fallback is unavailable and explained rather than offered', () => {
  renderScreen(projected({setup: true, canEnable: true, canChangeFallback: false,
    rows: [{name: 'vpn.example.com:51820', mode: 'Full tunnel', isEnabled: true}]}));
  expect(screen.getByText('DNS fallback is unavailable with an active full-tunnel VPN.')).toBeTruthy();
});

async function toolbar(label: string, side: 'headerLeft' | 'headerRight' = 'headerRight') {
  const options = mockSetOptions.mock.calls[mockSetOptions.mock.calls.length - 1][0];
  const host = options[side]({});
  const item = host.props.children.find((child: React.ReactElement<any>) => child.props.accessibilityLabel === label);
  expect(item).toBeTruthy();
  await act(async () => item.props.onPress());
}

test('ordered editor adds the second native row swaps removes and saves through one draft', async () => {
  let draft: VPNDraft = {id: '', revision: 0, changed: false, containsFullTunnel: false,
    rows: [{name: 'Entry', mode: 'Split tunnel', isEnabled: true}]};
  command.mockImplementation(async (input: {type: string;id: string;index: number}) => {
    if(input.type === 'vpn.begin') draft={...draft,id: input.id};
    if(input.type === 'vpn.edit') {
      expect(input.index).toBe(1);
      draft={...draft,revision: draft.revision+1,changed: true,rows: [...draft.rows,{name: 'Exit',mode: 'Split tunnel',isEnabled: true}]};
    }
    if(input.type === 'vpn.swap') draft={...draft,revision: draft.revision+1,changed: true,rows: [...draft.rows].reverse()};
    if(input.type === 'vpn.remove') draft={...draft,revision: draft.revision+1,changed: true,rows: draft.rows.filter((_,i)=>i!==input.index)};
    return input.type === 'vpn.commit' ? null : draft;
  });
  renderScreen(projected({setup: true,enabled: true,generation: '42',rows: draft.rows}));
  await toolbar('Edit');
  fireEvent.press(await screen.findByLabelText('Add configuration'));
  expect(await screen.findByText('Exit')).toBeTruthy();
  fireEvent.press(screen.getByLabelText('Swap order'));
  await waitFor(()=>expect(command).toHaveBeenCalledWith({type: 'vpn.swap',id: draft.id}));
  expect(within(screen.getByTestId('vpn.configuration-row')).getByText('Exit')).toBeTruthy();
  fireEvent.press(screen.getAllByLabelText('Remove').find(node=>node.props.accessibilityValue?.text==='Exit')!);
  await waitFor(()=>expect(command).toHaveBeenCalledWith({type: 'vpn.remove',id: draft.id,index: 0}));
  await waitFor(()=>expect(screen.queryByText('Exit')).toBeNull());
  await toolbar('Save');
  expect(command).toHaveBeenCalledWith({type: 'vpn.commit',id: draft.id});
  expect(command.mock.calls.some(([input])=>'rawConfiguration' in input)).toBe(false);
});

test('per row switch sends exact generation and global off presentation stays off', async () => {
  renderScreen(projected({setup: true,enabled: false,generation: '17',rows: [
    {name: 'Entry',mode: 'Full tunnel',isEnabled: true},{name: 'Exit',mode: 'Split tunnel',isEnabled: true}]}));
  const exit=screen.getByTestId('vpn.row-toggle.1');
  expect(exit.props.on).toBe(false);
  fireEvent(exit,'valueChange',true);
  await waitFor(()=>expect(command).toHaveBeenCalledWith({type: 'vpn.rowToggle',index: 1,value: true,generation: '17'}));
});

test('unchanged pending first import still saves and cancel of unchanged existing selection writes no profiles', async () => {
  command.mockImplementation(async (input: {type: string;id: string})=>input.type === 'vpn.begin'?
    {id: input.id,revision: 0,changed: false,containsFullTunnel: false,rows: [{name: 'Entry',mode: 'Split tunnel',isEnabled: true}]}:null);
  renderScreen(projected({setup: true,generation: '9',rows: [{name: 'Entry',mode: 'Split tunnel',isEnabled: true}]}));
  await toolbar('Edit');
  await toolbar('Cancel editing','headerLeft');
  await waitFor(()=>expect(command).toHaveBeenCalledWith(expect.objectContaining({type: 'vpn.cancel'})));
  expect(command).not.toHaveBeenCalledWith(expect.objectContaining({type: 'vpn.commit'}));
  await toolbar('Edit');
  await toolbar('Save');
  await waitFor(()=>expect(command).toHaveBeenCalledWith(expect.objectContaining({type: 'vpn.commit'})));
});

function toolbarLabels(): string[] {
  const options = mockSetOptions.mock.calls.at(-1)?.[0] as
    {headerLeft?: () => {props:{children?:unknown}}; headerRight?: () => {props:{children?:unknown}}} | undefined;
  if (!options) return [];
  const collect = (host?: {props:{children?:unknown}}) => {
    const children = host?.props?.children;
    const list = Array.isArray(children) ? children : children ? [children] : [];
    return list.map((child: {props?:{accessibilityLabel?:string}})=>child?.props?.accessibilityLabel).filter((label): label is string => !!label);
  };
  return [...collect(options.headerLeft?.()), ...collect(options.headerRight?.())];
}

test('confirming VPN Plus after tapping the prerequisite runs the first-import path, not a setup toggle', async () => {
  const draft: VPNDraft = {id:'d1',revision:0,changed:false,containsFullTunnel:false,
    rows:[{name:'vpn.example.com:51820',mode:'Split tunnel',isEnabled:true}]};
  command.mockImplementation(async (input:{type:string})=>input.type==='vpn.begin'?draft:null);
  const add = jest.spyOn(PlusIntents.prototype, 'add');
  try {
    const view=renderScreen(projected({setup:false,needsPlus:true,generation:''}));
    fireEvent(screen.getByLabelText('I have a WireGuard configuration'),'valueChange',true);
    await waitFor(()=>expect(mockNavNavigate).toHaveBeenCalledWith('Upgrade',expect.objectContaining({reason:'vpn'})));
    // No VPN command is issued before entitlement is confirmed.
    expect(command.mock.calls.filter(([input])=>(input as {type:string}).type.startsWith('vpn.'))).toEqual([]);
    expect(add).toHaveBeenCalledTimes(1);
    const resume = add.mock.calls[0]![0];
    // Entitlement is confirmed by the owner: the live `vpn` fact reports `needsPlus:false`.
    await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:false,needsPlus:false,generation:''})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
    await act(async()=>{await resume();});
    // The supported first-configuration path is the document import, never a setup toggle.
    expect(command).toHaveBeenCalledWith(expect.objectContaining({type:'vpn.begin',generation:''}));
    expect(command.mock.calls.some(([input])=>(input as {type:string}).type==='vpn.toggle')).toBe(false);
    // The imported row is staged without any key material crossing.
    expect(await screen.findByText('vpn.example.com:51820')).toBeTruthy();
  } finally { add.mockRestore(); }
});

test('a real entitlement loss retires an open VPN draft and its retained Save action', async () => {
  const rows=[{name:'Entry',mode:'Split tunnel'}];
  command.mockImplementation(async (input:{type:string;id:string})=>input.type==='vpn.begin'
    ?{id:input.id,revision:0,changed:false,containsFullTunnel:false,rows}:null);
  const live=vpnValue(projected({setup:true,rows}));
  const view=render(<PlusIntentProvider><ReviewContext.Provider value={live as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>);
  await toolbar('Edit');
  const id=command.mock.calls.find(([input])=>(input as {type:string}).type==='vpn.begin')![0].id;
  // The live owner confirms the loss: `vpn.needsPlus` true, not a redacted `plus.enabled`.
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:true,rows,needsPlus:true})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  expect(toolbarLabels()).not.toContain('Save');
  expect(command).toHaveBeenCalledWith({type:'vpn.cancel',id});
  expect(screen.getByLabelText('I have a WireGuard configuration').props.on).toBe(false);
  await act(async()=>{await toolbar('Save').catch(()=>{});});
  expect(command).not.toHaveBeenCalledWith(expect.objectContaining({type:'vpn.commit'}));
});

test('a VPN editor opened before entitlement loss cannot stage after a later renewal', async () => {
  let finish!:(value:unknown)=>void;let id='';
  command.mockImplementation(async (input:{type:string;id?:string})=>{
    if(input.type==='vpn.begin'){id=input.id!;return new Promise(resolve=>{finish=resolve;});}
    return null;
  });
  const rows=[{name:'Entry',mode:'Split tunnel'}];
  const view=renderVpn(projected({setup:true,rows}));
  await toolbar('Edit');
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:true,rows,needsPlus:true})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:true,rows})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  await act(async()=>finish({id,revision:0,changed:false,containsFullTunnel:false,rows}));
  expect(command).toHaveBeenCalledWith({type:'vpn.cancel',id});
  expect(toolbarLabels()).not.toContain('Save');
  expect(command).not.toHaveBeenCalledWith(expect.objectContaining({type:'vpn.commit'}));
});

test('a background redaction (vpn omitted) does not cancel a staged draft', async () => {
  const rows=[{name:'Entry',mode:'Split tunnel'}];
  command.mockImplementation(async (input:{type:string;id:string})=>input.type==='vpn.begin'
    ?{id:input.id,revision:0,changed:false,containsFullTunnel:false,rows}:null);
  const view=renderVpn(projected({setup:true,rows}));
  await toolbar('Edit');
  const id=command.mock.calls.find(([input])=>(input as {type:string}).type==='vpn.begin')![0].id;
  // Leaving the foreground redacts `plus.enabled=false` and omits `vpn`; that is not a loss.
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(undefined,{enabled:false}) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  expect(command).not.toHaveBeenCalledWith({type:'vpn.cancel',id});
  // Returning to the foreground, the staged draft is intact: the editing toolbar (Save) is keyed
  // on the live draft, so its presence proves the draft survived (the projected row alone does not).
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:true,rows})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  expect(toolbarLabels()).toContain('Save');
});

test('a background redaction (vpn omitted) does not fence out an in-flight first import', async () => {
  let finish!:(value:VPNDraft)=>void;let id='';
  command.mockImplementation(async (input:{type:string;id?:string})=>{
    if(input.type==='vpn.begin'){id=input.id!;return new Promise(resolve=>{finish=resolve;});}
    return null;
  });
  const rows=[{name:'Entry',mode:'Split tunnel'}];
  const view=renderVpn(projected({setup:true,rows}));
  await toolbar('Edit');
  expect(command.mock.calls.filter(([input])=>(input as {type:string}).type==='vpn.begin')).toHaveLength(1);
  // Background: plus redacted, vpn family omitted. This must not bump the access epoch.
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(undefined,{enabled:false}) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  await act(async()=>view.rerender(<PlusIntentProvider><ReviewContext.Provider value={vpnValue(projected({setup:true,rows})) as never}><VpnChainingScreen/></ReviewContext.Provider></PlusIntentProvider>));
  expect(command.mock.calls.some(([input])=>(input as {type:string}).type==='vpn.cancel')).toBe(false);
  await act(async()=>finish({id,revision:0,changed:false,containsFullTunnel:false,rows}));
  expect(command).not.toHaveBeenCalledWith({type:'vpn.cancel',id});
  // The imported first rotation is staged (editing toolbar present), not silently dropped.
  expect(toolbarLabels()).toContain('Save');
});

test('an entitled unconfigured prerequisite runs the import path, never the unsupported setup toggle', async () => {
  const draft:VPNDraft={id:'d1',revision:0,changed:false,containsFullTunnel:false,
    rows:[{name:'entry.example:51820',mode:'Split tunnel',isEnabled:true}]};
  command.mockImplementation(async (input:{type:string})=>input.type==='vpn.begin'?draft:null);
  renderScreen(projected({setup:false,needsPlus:false,generation:''}));
  fireEvent(screen.getByLabelText('I have a WireGuard configuration'),'valueChange',true);
  await waitFor(()=>expect(command).toHaveBeenCalledWith(expect.objectContaining({type:'vpn.begin',generation:''})));
  expect(command.mock.calls.some(([input])=>(input as {type:string}).type==='vpn.toggle')).toBe(false);
});

test('a configured prerequisite is on and disabled so setup-off is never sent', () => {
  renderScreen(projected({setup:true,needsPlus:false}));
  const toggle=screen.getByLabelText('I have a WireGuard configuration');
  expect(toggle.props.on).toBe(true);
  // Setup-off is unsupported in the single-rotation model: attempting it must never reach the
  // native owner as the refused `vpn.toggle setup`, with or without the disabled presentation.
  fireEvent(toggle,'valueChange',false);
  expect(command.mock.calls.some(([input])=>(input as {type:string}).type==='vpn.toggle')).toBe(false);
});

afterEach(()=>configurePresentation());

test.each(['Cancel','Guard'])('authored VPN row %s stays verbatim in visible and native switch labels',async name=>{
  configurePresentation({locale:'de',textScales:null});
  expect(localized(name)).not.toBe(name);
  renderScreen(projected({setup:true,enabled:true,canEnable:true,generation:'17',
    rows:[{name,mode:'Split tunnel',isEnabled:true}]}));
  expect(screen.getByText(name)).toBeOnTheScreen();
  const toggle=screen.getByTestId('vpn.row-toggle.0');
  expect(toggle.props.accessibilityLabel).toBe(name);
  expect(screen.getByLabelText(localized('I have a WireGuard configuration'))).toBeOnTheScreen();
  fireEvent(toggle,'valueChange',false);
  await waitFor(()=>expect(command).toHaveBeenCalledWith({type:'vpn.rowToggle',index:0,value:false,generation:'17'}));
});
