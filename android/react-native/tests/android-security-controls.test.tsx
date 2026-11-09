import React from 'react';
import {Alert} from 'react-native';
import {act,fireEvent,render} from '@testing-library/react-native';
import {ReviewContext,type ReviewState} from '../canonical/review/ReviewContext';
import {localized} from '../canonical/app/presentation';
import {initialSession} from '../canonical/review/session';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {AppearanceStore} from '../canonical/review/appearance-store';
import type {AppSnapshot} from '../canonical/app/contract';
import {AppCommandDetachedError,type AppStore} from '../canonical/app/store';
import NativeSwitch from '../canonical/specs/LavaSwitchNativeComponent';

jest.mock('@react-navigation/native',()=>({useNavigation:()=>({setOptions:jest.fn(),navigate:jest.fn(),goBack:jest.fn(),addListener:()=>()=>{}}),useRoute:()=>({key:'security',name:'Security'}),useIsFocused:()=>true,usePreventRemove:jest.fn()}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({navigate:jest.fn(),goBack:jest.fn()}),previewNotice:jest.fn()}));
jest.mock('../canonical/review/primitives',()=>({...jest.requireActual('../canonical/review/primitives'),Screen:require('react-native').View}));
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{stopDemo:jest.fn(),getBlocklistCatalog:()=> '[]',getLegalNotices:()=>JSON.stringify({disclaimer:'',sections:[]}),getGuardAccents:()=>JSON.stringify({original:{light:'#BF4000',dark:'#FF8855'}})}}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('react-native-safe-area-context',()=>({SafeAreaInsetsContext:require('react').createContext({top:0,bottom:0,left:0,right:0}),useSafeAreaInsets:()=>({top:0,bottom:0,left:0,right:0}),useSafeAreaFrame:()=>({x:0,y:0,width:390,height:844})}));
import {SecurityScreen} from '../canonical/review/SettingsScreens';

function state(command:jest.Mock,setSession:jest.Mock,security:Partial<AppSnapshot['security']>={}):ReviewState {
  const session={...initialSession(),passcode:true,biometrics:true};
  const appearance=new AppearanceStore({getSnapshot:async()=>({preference:'system',revision:0}),setPreference:async()=>({preference:'system',revision:1}),onSnapshot:()=>({remove(){}})});
  return {appearance,look:'original',setLook:jest.fn(),session,setSession,draft:initialPreviewDraft(),setDraft:jest.fn(),savedDraft:initialPreviewDraft(),setSavedDraft:jest.fn(),app:{command} as unknown as AppStore,
    live:{security:{unavailable:false,hasAuthenticationMethod:true,updatingSurface:false,showBiometrics:true,canEnableBiometrics:true,biometricTitle:'Fingerprint',status:'',...security}} as AppSnapshot};
}

test('Security credential switches retain native-confirmed values through held authentication and cancellation',async()=>{
  let finish!: ()=>void;
  const command=jest.fn(()=>new Promise<void>(resolve=>{finish=resolve;}));
  let cancel!: (error:Error)=>void;
  const setSession=jest.fn((_value:ReviewState['session'])=>new Promise<void>((_resolve,reject)=>{cancel=reject;}));
  const view=render(<ReviewContext.Provider value={state(command,setSession)}><SecurityScreen/></ReviewContext.Provider>);
  fireEvent(view.getByLabelText('Passcode'),'valueChange',false);
  expect(command).toHaveBeenCalledWith({type:'native.flow',flow:'passcode'});
  expect(view.getByLabelText('Passcode').props.on).toBe(true);
  expect(view.getByLabelText('Passcode').props.enabled).toBe(false);
  await act(async()=>{finish();});
  fireEvent(view.getByLabelText('Fingerprint'),'valueChange',false);
  expect(setSession).toHaveBeenCalledTimes(1);
  expect(setSession.mock.calls[0]![0].biometrics).toBe(false);
  expect(view.getByLabelText('Fingerprint').props.on).toBe(true);
  expect(view.getByLabelText('Fingerprint').props.enabled).toBe(false);
  await act(async()=>{cancel(new Error('Authentication cancelled.'));});
  expect(view.getByLabelText('Fingerprint').props.on).toBe(true);
  expect(view.getByLabelText('Fingerprint').props.enabled).toBe(true);
});

test('stored-on unavailable biometrics can request guarded disable but cannot be newly enabled',()=>{
  const command=jest.fn();const setSession=jest.fn((_value:ReviewState['session'])=>{});
  const value=state(command,setSession,{canEnableBiometrics:false});
  const view=render(<ReviewContext.Provider value={value}><SecurityScreen/></ReviewContext.Provider>);
  expect(view.getByLabelText('Fingerprint').props.enabled).toBe(true);
  fireEvent(view.getByLabelText('Fingerprint'),'valueChange',false);
  expect(setSession.mock.calls[0]![0].biometrics).toBe(false);
  view.rerender(<ReviewContext.Provider value={{...value,session:{...value.session,biometrics:false}}}><SecurityScreen/></ReviewContext.Provider>);
  expect(view.getByLabelText('Fingerprint').props.enabled).toBe(false);
});

test('a protected Security choice owns one native attempt and siblings stay pending until cancellation',async()=>{
  let cancel!: (error:Error)=>void;
  const command=jest.fn(()=>new Promise<void>((_resolve,reject)=>{cancel=reject;}));
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  const value=state(command,jest.fn());
  const titles=Object.keys(value.session.protectedActions);
  const view=render(<ReviewContext.Provider value={value}><SecurityScreen/></ReviewContext.Provider>);
  try {
    fireEvent(view.getByLabelText(localized(titles[0]!)),'valueChange',true);
    fireEvent(view.getByLabelText(localized(titles[1]!)),'valueChange',true);
    expect(command).toHaveBeenCalledTimes(1);
    for(const title of titles)expect(view.getByLabelText(localized(title)).props.enabled).toBe(false);
    for(const row of view.UNSAFE_getAllByType(NativeSwitch).slice(2)){expect(row.props.pending).toBe(true);expect(row.props.disabled).toBe(false);}
    await act(async()=>{cancel(new Error('Authentication cancelled.'));});
    expect(alert).not.toHaveBeenCalled();
    for(const title of titles)expect(view.getByLabelText(localized(title)).props.enabled).toBe(true);
  }finally{alert.mockRestore();}
});


test('retired Security commands stay quiet while an ordinary current control refusal remains visible',async()=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  const ordinary=new Error('controlled refusal');
  const command=jest.fn().mockRejectedValueOnce(new AppCommandDetachedError('afterStart')).mockRejectedValueOnce(ordinary);
  const value=state(command,jest.fn());const title=Object.keys(value.session.protectedActions)[0]!;
  const view=render(<ReviewContext.Provider value={value}><SecurityScreen/></ReviewContext.Provider>);
  try {
    await act(async()=>{fireEvent(view.getByLabelText(localized(title)),'valueChange',true);});
    expect(alert).not.toHaveBeenCalled();
    await act(async()=>{fireEvent(view.getByLabelText(localized(title)),'valueChange',true);});
    expect(command).toHaveBeenCalledTimes(2);
    expect(alert.mock.calls[0]!.slice(0,2)).toEqual(['Lava',ordinary.message]);
  }finally{alert.mockRestore();}
});
