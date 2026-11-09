import React from 'react';
import {act,fireEvent,render,screen,waitFor} from '@testing-library/react-native';

const mockNavigate=jest.fn();const mockGoBack=jest.fn();const mockNative=jest.fn();
const mockSetOptions=jest.fn();const mockNavigation={setOptions:mockSetOptions,goBack:mockGoBack};
const mockCommand=jest.fn((input:any)=>input.flow==='importBegin'?Promise.resolve({visitID:'visit-1'}):input.flow==='importCancel'?Promise.resolve(undefined):mockNative(input));
const mockApp={command:mockCommand};
const mockLive={generation:'1',nativeHostID:'host-1',session:{activeFilterID:'active'},blocklistNames:{ads:'Ads'},blocklistMetadata:{},
  filters:[{id:'active',name:'Current',count:'2 rules',frozen:false},{id:'other',name:'Other filter',count:'3 rules',frozen:false}]};
const mockUpgrade=jest.fn();
jest.mock('@react-navigation/native',()=>({useNavigation:()=>mockNavigation,useIsFocused:()=>true,useRoute:()=>({params:undefined}),usePreventRemove:jest.fn()}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({navigate:mockNavigate,goBack:mockGoBack}),previewNotice:jest.fn()}));
jest.mock('../canonical/review/plus-entry',()=>({usePlusEntry:()=>mockUpgrade}));
jest.mock('../canonical/review/ReviewContext',()=>({...jest.requireActual('../canonical/review/ReviewContext'),useReview:()=>({app:mockApp,live:mockLive})}));
// Controller tests exercise the real shared stage contents without retaining outgoing animation trees.
jest.mock('../canonical/src/flows/StagedFlowTransition',()=>({StagedFlowTransition:({children}:any)=>children}));
import {ImportScreen} from '../canonical/review/FilterScreens';

const reviewed=(extra:Record<string,unknown>={})=>({reviewID:'review-1',blocklistIds:['ads'],customBlocklists:[{id:'custom-1',name:'My curated rules'}],blockedDomains:['blocked.example'],allowedDomains:['allowed.example'],
  canConfirm:true,dropped:[],unknownSourceIds:[],replacementGroups:[],...extra});
beforeEach(()=>{jest.clearAllMocks();mockNative.mockReset();});
async function start(){const view=render(<ImportScreen/>);await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith({type:'native.flow',flow:'importBegin'}));await act(async()=>{});return view;}
async function enterCode(){fireEvent.press(screen.getByRole('button',{name:'Enter a code'}));await screen.findByTestId('import.stage.enterCode');fireEvent(screen.getByLabelText('Setup code'),'change',{nativeEvent:{text:'  LF1-shared  '}});fireEvent.press(screen.getByRole('button',{name:'Continue'}));}

test('native review exposes exact contents and allowed exceptions before any publication',async()=>{
  mockNative.mockResolvedValue(reviewed());await start();await enterCode();
  await screen.findByTestId('import.stage.confirm');
  expect(screen.getByText('blocked.example')).toBeTruthy();expect(screen.getByText('allowed.example')).toBeTruthy();
  expect(screen.getByText('My curated rules')).toBeTruthy();
  expect(mockNative).toHaveBeenCalledWith({type:'native.flow',flow:'importCode',visitID:'visit-1',code:'LF1-shared'});
  expect(mockNative).not.toHaveBeenCalledWith(expect.objectContaining({flow:'importApply'}));
});
test('add shows applying until native publication completes, then requires Done',async()=>{
  let finish!:()=>void;mockNative.mockResolvedValueOnce(reviewed()).mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve;}));
  await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  fireEvent.press(screen.getByRole('button',{name:'Add as a new filter'}));
  await screen.findByTestId('import.stage.applying');expect(mockGoBack).not.toHaveBeenCalled();
  await act(async()=>finish());await screen.findByTestId('import.stage.completed');
  expect(mockNative).toHaveBeenLastCalledWith({type:'native.flow',flow:'importApply',visitID:'visit-1',reviewID:'review-1'});
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Done'})));expect(mockGoBack).toHaveBeenCalledTimes(1);
});
test('replacement requires the selected target review and consumes its exact receipt',async()=>{
  mockNative.mockResolvedValueOnce(reviewed()).mockResolvedValueOnce(reviewed({reviewID:'replacement-2',replacementGroups:[{title:'Blocked domains',removed:['old.example'],added:['blocked.example']}]})).mockResolvedValueOnce(undefined);
  await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  fireEvent.press(screen.getByRole('button',{name:'Replace a filter instead'}));await screen.findByTestId('import.stage.chooseReplace');
  fireEvent.press(screen.getByText('Other filter'));await screen.findByTestId('import.stage.reviewReplacement');
  expect(screen.getByText('old.example')).toBeTruthy();
  expect(mockNative).toHaveBeenLastCalledWith({type:'native.flow',flow:'importTarget',visitID:'visit-1',reviewID:'review-1',target:'other'});
  fireEvent.press(screen.getByRole('button',{name:'Replace a filter'}));await screen.findByTestId('import.stage.completed');
  expect(mockNative).toHaveBeenLastCalledWith({type:'native.flow',flow:'importApply',visitID:'visit-1',reviewID:'replacement-2',target:'other',confirmed:true});
});
test('a nonimportable review cannot issue apply',async()=>{
  mockNative.mockResolvedValueOnce(reviewed({canConfirm:false,blocklistIds:[],blockedDomains:[],allowedDomains:null}));
  await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  fireEvent.press(screen.getByRole('button',{name:'Nothing to import'}));
  expect(mockNative).not.toHaveBeenCalledWith(expect.objectContaining({flow:'importApply'}));
});
test('Plus return obtains a new native review before applying the newly eligible contents',async()=>{
  mockNative.mockResolvedValueOnce(reviewed({dropped:[{kind:'REQUIRES_UPGRADE',label:'More rules'}]}))
    .mockResolvedValueOnce(reviewed({reviewID:'upgraded-2',blockedDomains:['newly-eligible.example']})).mockResolvedValueOnce(undefined);
  await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Use Lava Plus for full import'})));
  expect(mockUpgrade).toHaveBeenCalledWith('fullImport',expect.any(Function));
  await act(async()=>mockUpgrade.mock.calls[0][1](()=>true));
  expect(mockNative).toHaveBeenLastCalledWith({type:'native.flow',flow:'importTarget',visitID:'visit-1',reviewID:'review-1'});
  expect(screen.getByText('newly-eligible.example')).toBeTruthy();
  fireEvent.press(screen.getByRole('button',{name:'Add as a new filter'}));await screen.findByTestId('import.stage.completed');
  expect(mockNative).toHaveBeenLastCalledWith({type:'native.flow',flow:'importApply',visitID:'visit-1',reviewID:'upgraded-2'});
});
test('a retired Plus continuation cannot reopen the import review',async()=>{
  mockNative.mockResolvedValueOnce(reviewed({dropped:[{kind:'REQUIRES_UPGRADE',label:'More rules'}]}));
  const view=await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Use Lava Plus for full import'})));
  const resume=mockUpgrade.mock.calls[0][1];view.unmount();
  await act(async()=>resume(()=>true));
  expect(mockNative).not.toHaveBeenCalledWith(expect.objectContaining({flow:'importTarget'}));
});
test('apply refusal consumes the review and returns to editable input without false success',async()=>{
  mockNative.mockResolvedValueOnce(reviewed()).mockRejectedValueOnce(new Error('The filter changed since it was reviewed. Review the import again.'));
  await start();await enterCode();await screen.findByTestId('import.stage.confirm');
  fireEvent.press(screen.getByRole('button',{name:'Add as a new filter'}));
  await screen.findByText('The filter changed since it was reviewed. Review the import again.');
  expect(screen.getByTestId('import.stage.enterCode')).toBeTruthy();expect(mockGoBack).not.toHaveBeenCalled();
});
test('code refusal stays editable and does not decode or claim valid contents',async()=>{
  mockNative.mockRejectedValueOnce(new Error('That does not look like a Lava setup code.'));await start();await enterCode();
  await screen.findByText('That does not look like a Lava setup code.');expect(screen.queryByTestId('import.stage.confirm')).toBeNull();
});
test('the actual Android header re-enables Back after a code refusal without another stage or native snapshot',async()=>{
  let refuse!:(error:Error)=>void;
  mockNative.mockImplementationOnce(()=>new Promise((_,reject)=>{refuse=reject;}));
  await start();await enterCode();
  const back=()=>mockSetOptions.mock.calls.at(-1)![0].headerLeft({}).props.children[0];
  await waitFor(()=>expect(back().props.disabled).toBe(true));
  await act(async()=>refuse(new Error('That does not look like a Lava setup code.')));
  await screen.findByText('That does not look like a Lava setup code.');
  expect(back().props.disabled).toBe(false);
  expect(back().props.accessibilityLabel).toBe('Back');
  await act(async()=>back().props.onPress());
  expect(screen.getByTestId('import.stage.chooseMethod')).toBeTruthy();
  expect(mockNative).toHaveBeenCalledTimes(1);
});
test('camera acquisition uses the shared scan stage and preserves native refusal',async()=>{
  let refuse!:(error:Error)=>void;mockNative.mockImplementationOnce(()=>new Promise((_,reject)=>{refuse=reject;}));
  await start();fireEvent.press(screen.getByRole('button',{name:'Scan a QR code'}));await screen.findByTestId('import.stage.scanCode');
  await waitFor(()=>expect(mockNative).toHaveBeenCalledWith({type:'native.flow',flow:'importScan',visitID:'visit-1'}));
  await act(async()=>refuse(new Error('Camera access is off')));await screen.findByText('Camera access is off');
  expect(screen.getByTestId('import.stage.chooseMethod')).toBeTruthy();
});
test('denied camera permission keeps the recovery stage and opens system settings',async()=>{
  mockNative.mockRejectedValueOnce(Object.assign(new Error('Lava needs camera or photo permission to import. You can enter a setup code instead.'),{code:'share-permission-denied'}));
  await start();fireEvent.press(screen.getByRole('button',{name:'Scan a QR code'}));
  const settings=await screen.findByRole('button',{name:'Open the Settings app'});
  expect(screen.getByTestId('import.stage.scanCode')).toBeTruthy();
  await act(async()=>fireEvent.press(settings));
  expect(mockCommand).toHaveBeenCalledWith({type:'system.open',target:'settings'});
});
test('closing while a photo picker awaits retires the exact native visit and ignores its late result',async()=>{
  let finish!:(value:unknown)=>void;mockNative.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
  const view=await start();fireEvent.press(screen.getByRole('button',{name:'Import from photo'}));
  await waitFor(()=>expect(mockNative).toHaveBeenCalledWith({type:'native.flow',flow:'import',visitID:'visit-1'}));
  view.unmount();expect(mockCommand).toHaveBeenCalledWith({type:'native.flow',flow:'importCancel',visitID:'visit-1'});
  await act(async()=>finish(reviewed()));expect(mockGoBack).not.toHaveBeenCalled();
  expect(mockNative).not.toHaveBeenCalledWith(expect.objectContaining({flow:'importApply'}));
});

// A real native import visit must finish admission before the visible method can be pressed.
test('method controls stay disabled until native visit admission completes',async()=>{
  let admit!:(value:{visitID:string})=>void;
  mockCommand.mockImplementationOnce(()=>new Promise(resolve=>{admit=resolve;}));
  render(<ImportScreen/>);
  const method=screen.getByRole('button',{name:'Enter a code'});
  expect(method.props.accessibilityState.disabled).toBe(true);
  fireEvent.press(method);expect(screen.queryByTestId('import.stage.enterCode')).toBeNull();
  await act(async()=>admit({visitID:'admitted'}));
  expect(screen.getByRole('button',{name:'Enter a code'}).props.accessibilityState.disabled).toBe(false);
  fireEvent.press(screen.getByRole('button',{name:'Enter a code'}));
  await screen.findByTestId('import.stage.enterCode');
});
