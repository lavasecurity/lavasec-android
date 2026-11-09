// Focused port of the pinned iOS `ReactNative/tests/review-screens.test.tsx`
// (lavasec-ios-internal @ c1fdb8e5) covering the work-packet W1 review/lifecycle
// changes: #827 Guard tap feedback, #849 retained query rows + gated catalog
// totals, and the Explore pause semantics (#821). The full iOS file spans 3000+
// lines and depends on iOS-only modules; this port keeps only the assertions
// that bind the Android canonical screens, adapting the provider wiring.
import React, {type PropsWithChildren} from 'react';
import {AppState, ScrollView} from 'react-native';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import type {ReviewState} from '../canonical/review/ReviewContext';
import {ReviewContext} from '../canonical/review/ReviewContext';
import {initialSession, type PreviewSession} from '../canonical/review/session';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {AppearanceStore} from '../canonical/review/appearance-store';
import type {AppSnapshot,DNSChoice} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockDispatch = jest.fn();
let mockNavigationState:any;
const mockCommand = jest.fn();
const mockQuery = jest.fn();
let mockRouteParams:Record<string,unknown>={id:'filter-a'};
let mockQueryResult: {value?: unknown; refreshing: boolean; error?: string} = {refreshing: false};

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, setOptions: jest.fn(), getState: () => undefined, addListener: () => () => {}}),
  useIsFocused: () => true,
  useRoute: () => ({key: 'source-route', params: mockRouteParams}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack,dispatch:mockDispatch,getState:()=>mockNavigationState}),
  previewNotice: jest.fn(),
}));
jest.mock('../canonical/review/filter-route', () => ({useFilterRoute: () => ({id: 'filter-a', ready: true})}));
jest.mock('../canonical/app/queries', () => ({useAppQuery: (...args: unknown[]) => {mockQuery(...args); return mockQueryResult;}}));
jest.mock('../canonical/specs/NativeLavaReview', () => ({
  __esModule: true,
  default: {stopDemo: jest.fn(), speakDemo: jest.fn().mockResolvedValue(false), getBlocklistCatalog: () => '[]', getLegalNotices: () => JSON.stringify({disclaimer: '', sections: []}), getGuardAccents: () => JSON.stringify({original: {light: '#BF4000', dark: '#FF8855'}})},
}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent', () => ({__esModule: true, default: require('react-native').View}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),SafeAreaProvider: require('react-native').View, SafeAreaView: require('react-native').View, useSafeAreaInsets: () => ({top: 59, bottom: 34, left: 0, right: 0}), useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844})}));

import {GuardScreen} from '../canonical/review/screens';
import {FiltersScreen, AddBlocklistScreen, ReviewScreen} from '../canonical/review/FilterScreens';
import {DomainListScreen, NetworkScreen} from '../canonical/review/DiagnosticScreens';
import {DNSPickerScreen} from '../canonical/review/DNSScreen';
import {DNSEditorProvider} from '../canonical/review/dns-editor';
import {PlusIntentProvider} from '../canonical/review/plus-intents';
import {configurePresentation,localized} from '../canonical/app/presentation';

beforeEach(() => {
  jest.clearAllMocks();
  mockCommand.mockResolvedValue(null);
  mockQueryResult = {refreshing: false};
  mockRouteParams={id:'filter-a'};
  mockNavigationState={key:'stack',index:1,routes:[{key:'filter',name:'Filter'},{key:'source-route',name:'Review'}]};
});

function providerValue(overrides: Partial<ReviewState> = {}): ReviewState {
  const appearance = new AppearanceStore({
    getSnapshot: async () => ({preference: 'system', revision: 0}),
    setPreference: async () => ({preference: 'system', revision: 1}),
    onSnapshot: () => ({remove() {}}),
  });
  return {
    appearance, look: 'original', setLook: jest.fn(),
    draft: initialPreviewDraft(), setDraft: jest.fn(),
    session: initialSession(), setSession: jest.fn(),
    savedDraft: initialPreviewDraft(), setSavedDraft: jest.fn(),
    ...overrides,
  };
}
function Provider({children, value}: PropsWithChildren<{value?: Partial<ReviewState>}>) {
  return <ReviewContext.Provider value={providerValue(value)}>{children}</ReviewContext.Provider>;
}

// Selected #878 uses the provider and transport labels promised by the search
// field. Endpoint paths must not turn an unrelated provider into a match.
test('DNS catalog search matches providers and transports without endpoint-path matches or reads',()=>{
  mockRouteParams={target:'tier',index:0};
  const choice=(id:string,name:string,transport:string,metadata:string):DNSChoice=>({id,name,transport,metadata,primary:'',secondary:''});
  const live={dns:{choices:[choice('cloudflare','Cloudflare','DoH','https://cloudflare.example.test/dns-query'),
    choice('hagezi','HaGeZi DNS','DoH','https://hagezi.example.test/dns-query'),choice('quad9','Quad9','DoT','DNS over TLS')],tiers:[],tiersContext:'current'},
    limits:{allowsCustomDNS:false}} as unknown as AppSnapshot;
  const view=render(<Provider value={{live,app:{command:mockCommand} as unknown as AppStore}}><PlusIntentProvider><DNSEditorProvider><DNSPickerScreen/></DNSEditorProvider></PlusIntentProvider></Provider>);
  try{
    const input=screen.getByLabelText('Search DNS providers or transports');
    for(let cycle=0;cycle<5;cycle++){
      fireEvent.changeText(input,'u');
      expect(screen.getByRole('button',{name:'Cloudflare'})).toBeOnTheScreen();
      expect(screen.getByRole('button',{name:'Quad9'})).toBeOnTheScreen();
      expect(screen.queryByRole('button',{name:'HaGeZi DNS'})).toBeNull();
      fireEvent.changeText(input,'uu');expect(screen.getByText('No DNS providers found')).toBeOnTheScreen();
      fireEvent.changeText(input,'  dOh  ');expect(screen.getByRole('button',{name:'HaGeZi DNS'})).toBeOnTheScreen();
      expect(screen.queryByRole('button',{name:'Quad9'})).toBeNull();
      fireEvent.changeText(input,'');expect(screen.getByRole('button',{name:'Quad9'})).toBeOnTheScreen();
      expect(screen.getByLabelText('Search DNS providers or transports')).toBe(input);
    }
    expect(mockCommand).not.toHaveBeenCalled();expect(mockQuery).not.toHaveBeenCalled();
  }finally{view.unmount();}
});

test('DNS catalog search also matches the localized transport label without a selection command',()=>{
  mockRouteParams={target:'tier',index:0};configurePresentation({locale:'de',textScales:null});
  const live={dns:{choices:[{id:'device',name:'System resolver',transport:'Device',metadata:'Native resolver',primary:'',secondary:''}],tiers:[],tiersContext:'current'},
    limits:{allowsCustomDNS:false}} as unknown as AppSnapshot;
  const view=render(<Provider value={{live,app:{command:mockCommand} as unknown as AppStore}}><PlusIntentProvider><DNSEditorProvider><DNSPickerScreen/></DNSEditorProvider></PlusIntentProvider></Provider>);
  try{
    const input=screen.getByLabelText(localized('Search DNS providers or transports'));
    fireEvent.changeText(input,'  GERÄT  ');expect(screen.getByRole('button',{name:'System resolver'})).toBeOnTheScreen();
    expect(mockCommand).not.toHaveBeenCalled();expect(mockQuery).not.toHaveBeenCalled();
  }finally{view.unmount();configurePresentation();}
});

test('DNS catalog search includes captured-locale default names and preserves authored identities',()=>{
  mockRouteParams={target:'tier',index:0};configurePresentation({locale:'fr',textScales:null});
  const choice=(id:string,name:string,nameIsDefault:boolean):DNSChoice=>({id,name,nameIsDefault,transport:'IP',metadata:'not-searchable-metadata',primary:'not-searchable-endpoint',secondary:''});
  const live={presentation:{locale:'de',textScales:null},dns:{choices:[choice('device-dns|IP','Device DNS',true),choice('authored|IP','Device DNS',false)],tiers:[],tiersContext:'current'},
    limits:{allowsCustomDNS:false}} as unknown as AppSnapshot;
  const view=render(<Provider value={{live,app:{command:mockCommand} as unknown as AppStore}}><PlusIntentProvider><DNSEditorProvider><DNSPickerScreen/></DNSEditorProvider></PlusIntentProvider></Provider>);
  try{
    const input=screen.getByLabelText(localized('Search DNS providers or transports'));
    expect(screen.getByRole('button',{name:'Geräte-DNS'})).toBeOnTheScreen();
    expect(screen.getByRole('button',{name:'Device DNS'})).toBeOnTheScreen();
    fireEvent.changeText(input,'  GERÄTE  ');
    expect(screen.getByRole('button',{name:'Geräte-DNS'})).toBeOnTheScreen();
    expect(screen.queryByRole('button',{name:'Device DNS'})).toBeNull();
    fireEvent.changeText(input,'Device DNS');
    expect(screen.getByRole('button',{name:'Device DNS'})).toBeOnTheScreen();
    fireEvent.changeText(input,'not-searchable');
    expect(screen.getByText(localized('No DNS providers found'))).toBeOnTheScreen();
    expect(mockCommand).not.toHaveBeenCalled();expect(mockQuery).not.toHaveBeenCalled();
  }finally{view.unmount();configurePresentation();}
});

// #827: a tap is acknowledged in every expression, but only the awake Guard
// shows the grateful animation; the protection state is otherwise unchanged.
test.each(['sleeping', 'waking', 'awake', 'paused', 'retrying', 'concerned', 'grateful'])('Guard acknowledges a tap in the %s expression without replacing its protection state', async mood => {
  jest.useFakeTimers();
  const previousState = AppState.currentState; AppState.currentState = 'active';
  const command = jest.fn().mockResolvedValue(null);
  const app = {command} as unknown as AppStore;
  const live = {protection: {rules: 12, title: 'Protection', subtitle: 'Current protection state', action: 'Turn on', mood}} as unknown as AppSnapshot;
  try {
    render(<Provider value={{app, live}}><GuardScreen /></Provider>);
    await act(async () => {});
    const mascot = screen.getByTestId('guard.mascot.gesture', {includeHiddenElements: true});
    fireEvent(mascot, 'guardianGesture', {nativeEvent: {gesture: 'tap'}});
    expect(command).toHaveBeenCalledWith({type: 'haptic', kind: 'acknowledged', controlID: 'mascot.tap', value: undefined});
    expect(mascot).toHaveProp('mood', mood === 'awake' ? 'grateful' : mood);
    act(() => jest.advanceTimersByTime(1300));
    expect(mascot).toHaveProp('mood', mood);
  } finally { AppState.currentState = previousState; jest.useRealTimers(); }
});

// #856: a changed draft's Review confirmation binds to the exact token the native owner minted
// after `filter.review`, then applies that token; the owner revalidates the reviewed draft/basis.
test('Review confirms with the exact filter.review token on filter.apply', async () => {
  const command = jest.fn(async (input: {type: string}) => input.type === 'filter.review' ? 'review-token' : null);
  const app = {command} as unknown as AppStore;
  const live = {
    session: {...initialSession(), editing: true, filterID: 'filter-a', activeFilterID: 'filter-a'},
    filterEditing: {canSave: true, reviewCanConfirm: true, validation: '', refreshing: false, lists: [], blocked: [], allowed: []},
    filterPreparationPresented: false,
  } as unknown as AppSnapshot;
  render(<Provider value={{app, live}}><ReviewScreen /></Provider>);
  await waitFor(() => expect(command).toHaveBeenCalledWith({type: 'filter.review', id: 'filter-a'}));
  await act(async () => { fireEvent.press(screen.getByRole('button', {name: 'Confirm changes'})); });
  await waitFor(() => expect(command).toHaveBeenCalledWith({type: 'filter.apply', id: 'filter-a', review: 'review-token'}));
});

test('live Review adopts the shared native preparation instead of recapturing a consumed review',async()=>{
  const retirePreparation=jest.fn().mockResolvedValue(undefined);
  const app={command:mockCommand,retirePreparation} as unknown as AppStore;
  const live={
    session:{...initialSession(),editing:true,filterID:'filter-a',activeFilterID:'filter-a'},
    filterPreparationPresented:true,preparation:{id:'native-prepare',filterId:'filter-a',origin:'filters',
      state:'failed',progress:.5,title:"We couldn't update your filter",message:'The filter change could not be committed.',
      busy:false,recovery:{retry:true,editReturn:true,keepCurrent:true,upgrade:null}},
  } as unknown as AppSnapshot;
  const view=render(<Provider value={{app,live}}><ReviewScreen/></Provider>);
  await act(async()=>{});
  expect(screen.getByTestId('preparation.screen')).toBeTruthy();
  expect(screen.queryByRole('button',{name:'Confirm changes'})).toBeNull();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'filter.review'}));
  fireEvent.press(screen.getByTestId('preparation.retry'));await act(async()=>{});
  expect(mockCommand).toHaveBeenCalledWith({type:'preparation.retry',id:'native-prepare'});
  // Native Close may finish after this Review has already left the stack.
  // Its retained fragment must not pop the Filter underneath it.
  mockNavigationState={...mockNavigationState,index:0,routes:[mockNavigationState.routes[0]]};
  view.rerender(<Provider value={{app,live:{...live,filterPreparationPresented:false,preparation:undefined}}}><ReviewScreen/></Provider>);
  await act(async()=>{});
  expect(mockGoBack).not.toHaveBeenCalled();expect(mockDispatch).not.toHaveBeenCalled();
  view.unmount();expect(retirePreparation).toHaveBeenCalledWith('native-prepare');
});

test('active Android apply retains Review for inline preparation instead of dismissing for a UIKit controller',async()=>{
  let finishApply!:(value:null)=>void;
  const command=jest.fn((input:{type:string})=>input.type==='filter.apply'
    ?new Promise<null>(resolve=>{finishApply=resolve;}):Promise.resolve('review-token'));
  const retirePreparation=jest.fn().mockResolvedValue(undefined);
  const app={command,retirePreparation} as unknown as AppStore;
  const live={session:{...initialSession(),editing:true,filterID:'filter-a',activeFilterID:'filter-a'},
    filterEditing:{canSave:true,reviewCanConfirm:true,validation:'',refreshing:false,lists:[],blocked:[],allowed:[]},
    filterPreparationPresented:false} as unknown as AppSnapshot;
  const view=render(<Provider value={{app,live}}><ReviewScreen/></Provider>);
  await waitFor(()=>expect(screen.getByRole('button',{name:'Confirm changes'})).toBeEnabled());
  fireEvent.press(screen.getByRole('button',{name:'Confirm changes'}));
  expect(command).toHaveBeenLastCalledWith({type:'filter.apply',id:'filter-a',review:'review-token'});
  expect(mockGoBack).not.toHaveBeenCalled();expect(mockDispatch).not.toHaveBeenCalled();
  view.rerender(<Provider value={{app,live:{...live,filterPreparationPresented:true,
    preparation:{id:'active-prepare',filterId:'filter-a',origin:'filters',state:'failed',progress:.5,
      title:"We couldn't update your filter",message:'The filter change could not be committed.',busy:false,
      recovery:{retry:true,editReturn:true,keepCurrent:true,upgrade:null}}}}}><ReviewScreen/></Provider>);
  await act(async()=>{});
  expect(screen.getByTestId('preparation.screen')).toBeTruthy();
  expect(screen.getByTestId('preparation.retry')).toBeTruthy();
  expect(screen.queryByRole('button',{name:'Confirm changes'})).toBeNull();
  expect(command.mock.calls.filter(([input])=>input.type==='filter.review')).toHaveLength(1);
  await act(async()=>finishApply(null));
  expect(screen.getByTestId('preparation.screen')).toBeTruthy();
  expect(mockGoBack).not.toHaveBeenCalled();expect(mockDispatch).not.toHaveBeenCalled();
  view.unmount();expect(retirePreparation).toHaveBeenCalledWith('active-prepare');
});

const pickerCatalog = {
  sections: [{title: 'Security', isCustom: false, sources: [
    {id: 'first-list', name: 'First', licenseName: 'MIT', sourceURL: 'https://catalog.example/first'},
    {id: 'second-list', name: 'Second', licenseName: 'MIT', sourceURL: 'https://catalog.example/second'},
    {id: 'third-list', name: 'Third', licenseName: 'MIT', sourceURL: 'https://catalog.example/third'},
  ]}],
  count: 0, budget: 500_000, pending: 0, exceeded: false, summary: '0 of 3 lists selected',
  fraction: 0, indeterminate: true, atOrOverBudget: false,
};
const pickerSession = (): PreviewSession => ({...initialSession(), editing: true, blocklists: ['first-list'], savedBlocklists: ['first-list']});
const pickerLive = () => ({
  session: pickerSession(),
  limits: {maxFilterRules: 500_000, allowsCustomBlocklists: false},
  plus: {enabled: false},
}) as unknown as AppSnapshot;

// #849: retained catalog rows keep editing alive, but a failed read cannot
// establish the budget or authorize Save/Upgrade.
test('failed selection totals keep rows but cannot enable Save or Upgrade', async () => {
  mockQueryResult = {refreshing: false, value: pickerCatalog};
  const app = {command: mockCommand} as unknown as AppStore;
  const view = render(<Provider value={{app, live: pickerLive(), session: pickerSession()}}><AddBlocklistScreen /></Provider>);
  const row = screen.getByRole('button', {name: 'Second, MIT'});
  fireEvent.press(row);
  expect(row.props.accessibilityState.selected).toBe(true);

  mockQueryResult = {refreshing: false, value: {...pickerCatalog, exceeded: true}, error: 'Catalog temporarily unavailable.'};
  view.rerender(<Provider value={{app, live: pickerLive(), session: pickerSession()}}><AddBlocklistScreen /></Provider>);
  expect(screen.getByText('Catalog temporarily unavailable.')).toBeOnTheScreen();
  expect(screen.queryByRole('progressbar')).toBeNull();
  expect(screen.queryByRole('button', {name: 'Upgrade'})).toBeNull();
  expect(screen.getByRole('button', {name: 'Save selection'})).toBeDisabled();
  await act(async () => { fireEvent.press(screen.getByRole('button', {name: 'Save selection'})); });
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'filter.lists'}));
  expect(mockNavigate).not.toHaveBeenCalledWith('Upgrade');

  mockQueryResult = {refreshing: false, value: {...pickerCatalog, summary: 'About 200 of 500K rules'}};
  view.rerender(<Provider value={{app, live: pickerLive(), session: pickerSession()}}><AddBlocklistScreen /></Provider>);
  await waitFor(() => expect(screen.getByText('About 200 of 500K rules')).toBeOnTheScreen());
  expect(screen.queryByText('Catalog temporarily unavailable.')).toBeNull();
  expect(screen.getByRole('button', {name: 'Save selection'})).toBeEnabled();
  await act(async () => { fireEvent.press(screen.getByRole('button', {name: 'Save selection'})); });
  expect(mockCommand).toHaveBeenLastCalledWith({type: 'filter.lists', id: 'filter-a', ids: ['first-list', 'second-list']});
});

test('a successful over-budget read with a zero-plus account offers Upgrade instead of a dead Save', () => {
  mockQueryResult = {refreshing: false, value: {...pickerCatalog, exceeded: true}};
  const app = {command: mockCommand} as unknown as AppStore;
  render(<Provider value={{app, live: pickerLive(), session: pickerSession()}}><AddBlocklistScreen /></Provider>);
  expect(screen.getByRole('button', {name: 'Upgrade'})).toBeEnabled();
  expect(screen.queryByRole('button', {name: 'Save selection'})).toBeNull();
});

// #849: a domain-history event count update must not retract pages already opened.
test('domain history keeps expanded rows and its query limit through native count updates', async () => {
  const original = AppState.currentState;
  Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
  const entries = Array.from({length: 100}, (_, index) => ({id: String(index), domain: `domain-${index}.example`, metadata: 'Allowed'}));
  mockQueryResult = {refreshing: false, value: entries};
  const app = {command: mockCommand} as unknown as AppStore;
  const live = {session: {...initialSession(), filterID: 'filter-a'}, domainHistoryCount: 100} as unknown as AppSnapshot;
  const view = render(<Provider value={{app, live}}><DomainListScreen history /></Provider>);
  try {
    await waitFor(() => expect(screen.getByTestId('domain-menu.29')).toBeOnTheScreen());
    fireEvent.scroll(screen.UNSAFE_getByType(ScrollView), {nativeEvent: {contentOffset: {y: 1000}, layoutMeasurement: {height: 700}, contentSize: {height: 1700}}});
    await waitFor(() => expect(screen.getByTestId('domain-menu.59')).toBeOnTheScreen());
    const row = screen.getByTestId('domain-menu.59');
    view.rerender(<Provider value={{app, live: {...live, domainHistoryCount: 101}}}><DomainListScreen history /></Provider>);
    expect(screen.getByTestId('domain-menu.59')).toBe(row);
    const limits = mockQuery.mock.calls.filter(([input]) => (input as {type?: string}).type === 'domains.query').map(([input]) => (input as {limit: number}).limit);
    // The expanded page survives the count update: the command never returns to 31.
    expect(limits[0]).toBe(31);
    expect(limits.at(-1)).toBe(61);
    expect(limits.filter(limit => limit === 31)).toHaveLength(1);
  } finally {
    view.unmount();
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: original});
  }
});

// #849: an accepted empty network log starts the next populated log at page one;
// arrival/expiry of entries preserves the expanded page. The retained-length
// transition is the discriminator: the pre-#849 effect ran setLimit(30) on
// every count change, so a growing log retracted the user's expanded page.
test('network activity preserves expanded pages across a retained length change and resets only on an accepted empty log', async () => {
  const rows = (count: number) => Array.from({length: count}, (_, index) => ({id: String(index), title: `Event ${index + 1}`, subtitle: 'Connection', metadata: 'Now'}));
  mockQueryResult = {refreshing: false, value: rows(100)};
  const app = {command: mockCommand} as unknown as AppStore;
  const view = render(<Provider value={{app}}><NetworkScreen /></Provider>);
  const end = () => fireEvent.scroll(screen.getByTestId('screen.scroll'), {nativeEvent: {contentOffset: {y: 2500}, layoutMeasurement: {height: 700}, contentSize: {height: 3000}}});
  await waitFor(() => expect(screen.getByText('Event 30')).toBeOnTheScreen());
  end();
  expect(screen.getByText('Event 60')).toBeOnTheScreen();

  // The log grows while the user holds an expanded page. Each new non-zero
  // length must keep Event 60 on screen instead of collapsing to page one.
  mockQueryResult = {refreshing: false, value: rows(101)};
  view.rerender(<Provider value={{app}}><NetworkScreen /></Provider>);
  expect(screen.getByText('Event 60')).toBeOnTheScreen();
  mockQueryResult = {refreshing: false, value: rows(102)};
  view.rerender(<Provider value={{app}}><NetworkScreen /></Provider>);
  expect(screen.getByText('Event 60')).toBeOnTheScreen();

  // Only an accepted empty log returns the next populated log to page one.
  mockQueryResult = {refreshing: false, value: []};
  view.rerender(<Provider value={{app}}><NetworkScreen /></Provider>);
  mockQueryResult = {refreshing: false, value: rows(100)};
  view.rerender(<Provider value={{app}}><NetworkScreen /></Provider>);
  expect(screen.getByText('Event 30')).toBeOnTheScreen();
  expect(screen.queryByText('Event 31')).toBeNull();
});


// Actual Android screen actions, backed by the native command contract.
import {PrivacyScreen} from '../canonical/review/SettingsScreens';
import {FilterOverview} from '../canonical/review/story-scaffold';
import {platformToolbarOptions,toolbarAction} from '../canonical/review/scaffold';
import {Symbol} from '../canonical/review/primitives';

test('Activity-style toolbar retains two glyph targets with accessible labels and no visible labels',()=>{
  const share=jest.fn(),clear=jest.fn();
  const options=platformToolbarOptions({unstable_headerRightItems:()=>[
    toolbarAction('Export','share',share),toolbarAction('Clear all logs','delete',clear),
  ]});
  render(<>{options.headerRight!({canGoBack:false})}</>);
  for(const label of ['Export','Clear all logs']){
    expect(screen.queryByText(label)).toBeNull();
    expect(screen.getByRole('button',{name:label})).toHaveStyle({minWidth:48,minHeight:48});
  }
  expect(screen.UNSAFE_getAllByType(Symbol).map(node=>node.props.name)).toEqual(['square.and.arrow.up','trash']);
  fireEvent.press(screen.getByRole('button',{name:'Export'}));
  fireEvent.press(screen.getByRole('button',{name:'Clear all logs'}));
  expect(share).toHaveBeenCalledTimes(1);expect(clear).toHaveBeenCalledTimes(1);
});

test('RN labeled toolbar identities retain icon and visible copy for Import and Photo',()=>{
  const importAction=jest.fn(),photoAction=jest.fn();
  const options=platformToolbarOptions({unstable_headerRightItems:()=>[
    toolbarAction('Import','import',importAction),
    toolbarAction('Photo','share',photoAction,false,'lava.toolbar.labeled.photo'),
  ]});
  render(<>{options.headerRight!({canGoBack:false})}</>);
  for(const label of ['Import','Photo']){
    expect(screen.getByText(label)).toBeTruthy();
    expect(screen.getByRole('button',{name:label})).toHaveStyle({minWidth:48,minHeight:48,flexDirection:'row'});
  }
  expect(screen.UNSAFE_getAllByType(Symbol).map(node=>node.props.name)).toEqual(['square.and.arrow.down','square.and.arrow.up']);
  fireEvent.press(screen.getByRole('button',{name:'Import'}));
  fireEvent.press(screen.getByRole('button',{name:'Photo'}));
  expect(importAction).toHaveBeenCalledTimes(1);expect(photoAction).toHaveBeenCalledTimes(1);
});

test('Privacy exposes separate diagnostics and full log exports with explicit domain consent',async()=>{
  render(<Provider value={{app:{command:mockCommand} as unknown as AppStore}}><PrivacyScreen/></Provider>);
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Export diagnostics'})));
  expect(mockCommand).toHaveBeenLastCalledWith({type:'logs.export',domains:false});
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Export all logs'})));
  expect(mockCommand).toHaveBeenLastCalledWith({type:'logs.export',domains:true});
});

test('Now filtering reports the active native filter source count including zero',()=>{
  for(const lists of [[],['source-a','source-b','source-c']]){
    const live={session:{...initialSession(),activeFilterID:'balanced'},filters:[{id:'balanced',name:'Balanced',count:'113643',lists,blockedDomainCount:1,allowedExceptionCount:0}]} as unknown as AppSnapshot;
    const view=render(<Provider value={{live,app:{command:mockCommand} as unknown as AppStore}}><FiltersScreen/></Provider>);
    expect(screen.UNSAFE_getByType(FilterOverview).props.counts[0]).toEqual({label:'Blocklists',value:lists.length});
    view.unmount();
  }
});
