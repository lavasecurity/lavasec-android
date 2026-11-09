import React, {createContext,useState} from 'react';
import {Platform,View} from 'react-native';
import {act,fireEvent,render,screen,waitFor} from '@testing-library/react-native';
import type {AppCommand,DNSChoice} from '../canonical/app/contract';

type Route={name:'DNS'|'DNSPicker'|'CustomEntry';params?:Record<string,unknown>;focused?:boolean};
type Header={unstable_headerRightItems?:()=>{label:string;onPress:()=>void;disabled:boolean}[];
  unstable_headerLeftItems?:()=>{label:string;onPress:()=>void;disabled:boolean}[]};
const mockRouteContext=createContext<Route>({name:'DNS'});
const mockHeaders:Partial<Record<Route['name'],Header>>={};
const mockNavigate=jest.fn();const mockGoBack=jest.fn();const mockCommand=jest.fn();
const mockApp={command:mockCommand};
let mockLive:any;
jest.mock('@react-navigation/native',()=>({
  useNavigation:()=>({setOptions:jest.fn(),goBack:mockGoBack,navigate:mockNavigate,getState:()=>undefined}),
  useRoute:()=>require('react').useContext(mockRouteContext),
  useIsFocused:()=>require('react').useContext(mockRouteContext).focused,
  usePreventRemove:jest.fn(),
}));
jest.mock('react-native-safe-area-context',()=>({
  SafeAreaInsetsContext:require('react').createContext({top:47,left:0,right:0,bottom:34}),
  SafeAreaProvider:({children}:{children:React.ReactNode})=>children,
  useSafeAreaFrame:()=>({x:0,y:0,width:390,height:844}),
  useSafeAreaInsets:()=>({top:47,left:0,right:0,bottom:34}),
}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({navigate:mockNavigate,goBack:mockGoBack})}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({app:mockApp,live:mockLive}),useOptionalReview:()=>({app:mockApp,live:mockLive}),useRouteBodyConcealed:()=>false}));
jest.mock('../canonical/review/scaffold',()=>({
  ...jest.requireActual('../canonical/review/scaffold'),
  useToolbar:(options:Header)=>{const route=require('react').useContext(mockRouteContext);mockHeaders[route.name as Route['name']]=options;},
}));
import {DNSEditorProvider,selection} from '../canonical/review/dns-editor';
import {DNSScreen,DNSPickerScreen} from '../canonical/review/DNSScreen';
import {CustomDNSEntryScreen} from '../canonical/android/CustomDNSEntry';
import {LavaActionButton} from '../canonical/src';
import {deferred,installLifecycleMock} from './interruptionHarness';

const first:DNSChoice={id:'device-dns|IP',name:'Saved device name',primary:'',secondary:'',transport:'IP',metadata:'Device',isEnabled:true};
const second:DNSChoice={id:'quad9-unfiltered|DoT',name:'Retained Quad9 name',primary:'',secondary:'',transport:'DoT',metadata:'Quad9',isEnabled:false};
// Literal primary/secondary emitted by the frozen iOS stamp backup encoder.
const rawPrimary='sdns://BAAAAAAAAAAACzkuOS45Ljk6ODUzAA1kbnMucXVhZDkubmV0jzE0OS4xMTIuMTEyLjExMgsyNjIwOmZlOjpmZQ';
const rawSecondary='sdns://BAAAAAAAAAAAETE5Mi4xNjguMS41Mzo4ODUzABxzZWNvbmRhcnkucmVzb2x2ZXIudGVzdDo4ODUzjDE5Mi4xNjguMS41NAhmZDAwOjo1Mw';
let lifecycle:ReturnType<typeof installLifecycleMock>;
let durableRows:ReturnType<typeof selection>[];
const originalPlatform=Platform.OS;
beforeEach(()=>{
  jest.clearAllMocks();Platform.OS='android';lifecycle=installLifecycleMock();
  durableRows=[selection(first),selection(second)];
  mockLive={limits:{allowsCustomDNS:true},dns:{editable:true,tiers:[first,second],tiersContext:'source-1',choices:[first,second],
    custom:{context:'custom-source-1'},customDraftToken:'',customDraft:null}};
  let sequence=0;
  mockCommand.mockImplementation(async(command:AppCommand)=>{
    if(command.type==='dns.customDraft'){
      const id=`token-${++sequence}`;mockLive.dns.customDraftToken=id;
      mockLive.dns.customDraft={id:'custom-dns|DoH',name:'',primary:'',secondary:'',transport:'DoH',metadata:'Seed only'};
      return id;
    }
    if(command.type==='dns.customStage')return {id:'custom-dns|DoQ',name:command.name,primary:command.primary,secondary:command.secondary,transport:'DoQ',metadata:'Validated choice'};
    if(command.type==='dns.tiers'){durableRows=command.tiers;return null;}
    if(command.type==='customEntry.dismiss'&&mockLive.dns.customDraftToken===command.id){mockLive.dns.customDraftToken='';mockLive.dns.customDraft=null;}
    return null;
  });
});
afterEach(()=>{Platform.OS=originalPlatform;jest.restoreAllMocks();});

function Stack(){
  const [routes,setRoutes]=useState<Route[]>([{name:'DNS'}]);
  mockNavigate.mockImplementation((name:Route['name'],params:Record<string,unknown>)=>setRoutes(rows=>[...rows,{name,params}]));
  mockGoBack.mockImplementation(()=>setRoutes(rows=>rows.slice(0,-1)));
  return <DNSEditorProvider>{routes.map((route,index)=><mockRouteContext.Provider key={index} value={{...route,focused:index===routes.length-1}}>
    <View style={{display:index===routes.length-1?'flex':'none'}}>
      {route.name==='DNS'?<DNSScreen/>:route.name==='DNSPicker'?<DNSPickerScreen/>:<CustomDNSEntryScreen/>}
    </View>
  </mockRouteContext.Provider>)}</DNSEditorProvider>;
}
async function header(route:Route['name'],label:string){
  const options=mockHeaders[route];const item=[...(options?.unstable_headerRightItems?.()??[]),...(options?.unstable_headerLeftItems?.()??[])].find(button=>button.label===label);
  expect(item).toBeDefined();expect(item!.disabled).toBe(false);await act(async()=>item!.onPress());
}
async function open(index:number){
  await header('DNS','Edit');fireEvent.press(screen.getByText(index===0?first.name:second.name));
  await header('DNSPicker','Add custom DNS');await screen.findByTestId('custom.dns.primary');
}
function enterRaw(){
  fireEvent.changeText(screen.getByTestId('custom.dns.primary'),rawPrimary);
  fireEvent.changeText(screen.getByTestId('custom.dns.secondary'),rawSecondary);
}

test.each([0,1])('custom choice for slot %i stays local until the entire ladder Save',async(index)=>{
  render(<Stack/>);await open(index);
  expect(durableRows).toEqual([selection(first),selection(second)]);
  enterRaw();fireEvent.press(screen.getByRole('button',{name:'Save resolver'}));
  await waitFor(()=>{
    expect(screen.getByText('Custom DNS')).toBeTruthy();
    expect(screen.getByRole('button',{name:'Save selection'}).props.accessibilityState.disabled).toBe(false);
  });
  expect(mockCommand).toHaveBeenCalledWith({type:'dns.customStage',id:'token-1',context:'custom-source-1',name:'',primary:rawPrimary,secondary:rawSecondary});
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.custom'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
  expect(durableRows).toEqual([selection(first),selection(second)]);
  fireEvent.press(screen.getByRole('button',{name:'Save selection'}));
  await waitFor(()=>expect(screen.getByText('Custom DNS')).toBeTruthy());
  expect(durableRows).toEqual([selection(first),selection(second)]);
  await header('DNS','Save');
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers',context:'source-1'})));
  expect(durableRows[1-index]).toEqual(selection(index===0?second:first));
  expect(durableRows[index]).toEqual({id:'custom-dns|DoQ',name:'',primary:rawPrimary,secondary:rawSecondary,...(index===1?{isEnabled:false}:{})});
});

test('cancelled seed does not select a custom resolver or create a durable write',async()=>{
  render(<Stack/>);await open(1);enterRaw();await header('CustomEntry','Close');
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save selection'}).props.accessibilityState.disabled).toBe(false));
  fireEvent.press(screen.getByRole('button',{name:'Save selection'}));await header('DNS','Save');
  expect(durableRows).toEqual([selection(first),selection(second)]);
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
  expect(mockCommand).toHaveBeenCalledWith({type:'customEntry.dismiss',id:'token-1'});
});

test('a retained parent Save callback cannot dismiss the active custom editor or publish its old selection',async()=>{
  render(<Stack/>);await header('DNS','Edit');fireEvent.press(screen.getByText(first.name));
  const selectionActions=screen.UNSAFE_getAllByType(LavaActionButton).filter(node=>node.props.title==='Save Selection');
  expect(selectionActions).toHaveLength(1);
  const staleSave=selectionActions[0]!.props.onPress;
  await header('DNSPicker','Add custom DNS');await screen.findByTestId('custom.dns.primary');
  await act(async()=>staleSave());
  expect(screen.getByTestId('custom.dns.primary')).toBeTruthy();
  expect(mockGoBack).not.toHaveBeenCalled();
  expect(durableRows).toEqual([selection(first),selection(second)]);
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
  await header('CustomEntry','Close');
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save selection'}).props.accessibilityState.disabled).toBe(false));
  fireEvent.press(screen.getByRole('button',{name:'Save selection'}));await header('DNS','Save');
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
});

test.each(['back','background','source'] as const)('pending stage interrupted by %s cannot complete into another picker',async(interruption)=>{
  const reply=deferred<DNSChoice>();const normal=mockCommand.getMockImplementation()!;
  mockCommand.mockImplementation((command:AppCommand)=>command.type==='dns.customStage'?reply.promise:normal(command));
  const view=render(<Stack/>);await open(0);enterRaw();
  fireEvent.press(screen.getByRole('button',{name:'Save resolver'}));
  await waitFor(()=>expect(mockCommand).toHaveBeenCalledWith(expect.objectContaining({type:'dns.customStage'})));
  if(interruption==='back')await header('CustomEntry','Close');
  else if(interruption==='background'){lifecycle.emit('background');lifecycle.emit('active');}
  else {mockLive.dns.tiersContext='changed-source';mockLive.dns.custom.context='changed-custom-source';view.rerender(<Stack/>);}
  await act(async()=>reply.resolve({id:'custom-dns|DoQ',name:'Late choice',primary:rawPrimary,secondary:rawSecondary,transport:'DoQ',metadata:''}));
  expect(durableRows).toEqual([selection(first),selection(second)]);
  if(interruption!=='back')await header('CustomEntry','Close');
  expect(screen.queryByText('Late choice')).toBeNull();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'dns.tiers'}));
  // The completion is tagged to the retired token; opening a new picker cannot consume it.
  if(interruption!=='source'){
    await header('DNSPicker','Add custom DNS');await screen.findByTestId('custom.dns.primary');
    await header('CustomEntry','Close');expect(screen.queryByText('Late choice')).toBeNull();
  }
});
