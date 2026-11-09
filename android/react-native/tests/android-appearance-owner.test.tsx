import React from 'react';
import {Appearance} from 'react-native';
import {act,cleanup,render} from '@testing-library/react-native';

let mockListener:((value:{preference:string;revision:number})=>void)|undefined;
const mockSetPreference=jest.fn();
const mockGetSnapshot=jest.fn();
const mockRemove=jest.fn();
jest.mock('../canonical/specs/NativeLavaAppearance',()=>({__esModule:true,default:{
  getSnapshot:()=>mockGetSnapshot(),setPreference:mockSetPreference,
  onSnapshot:(listener:typeof mockListener)=>{mockListener=listener;return {remove:mockRemove};},
}}));
jest.mock('../canonical/specs/NativeLavaApp',()=>({__esModule:true,default:undefined}));
jest.mock('../canonical/review/screens',()=>({}));
jest.mock('../canonical/android/FilterIdentityForm',()=>({FilterIdentityFormHost:()=>null}));
jest.mock('@react-navigation/native',()=>({
  DarkTheme:{colors:{}},DefaultTheme:{colors:{}},
  NavigationContainer:({children}:{children:React.ReactNode})=>children,
  createNavigationContainerRef:()=>({getRootState:()=>undefined}),
  useNavigation:()=>({}),useRoute:()=>({}),useIsFocused:()=>true,
}));
jest.mock('@react-navigation/native-stack',()=>({createNativeStackNavigator:()=>({Navigator:()=>null,Screen:()=>null})}));
jest.mock('@react-navigation/bottom-tabs',()=>({createBottomTabNavigator:()=>({Navigator:()=>null,Screen:()=>null})}));
jest.mock('react-native-safe-area-context',()=>({
  SafeAreaProvider:({children}:{children:React.ReactNode})=>children,
  useSafeAreaInsets:()=>({top:0,bottom:0,left:0,right:0}),
}));

import {LavaUIReview} from '../canonical/review/LavaUIReview.android';

beforeEach(()=>{
  mockListener=undefined;jest.clearAllMocks();
});
afterEach(()=>{cleanup();jest.restoreAllMocks();});

test('stored and live owner-confirmed appearance use the Android adapter without another preference write',async()=>{
  mockGetSnapshot.mockResolvedValue({preference:'dark',revision:1});
  const mode=jest.spyOn(Appearance,'setColorScheme').mockImplementation(()=>{});
  const screen=render(<LavaUIReview initialAppearance="dark"/>);
  try {
    await act(async()=>{});
    expect(mode.mock.calls).toEqual([['dark']]);
    await act(async()=>mockListener?.({preference:'dark',revision:2}));
    expect(mode.mock.calls).toEqual([['dark']]);
    await act(async()=>mockListener?.({preference:'light',revision:3}));
    await act(async()=>mockListener?.({preference:'system',revision:4}));
    expect(mode.mock.calls).toEqual([['dark'],['light'],['unspecified']]);
    // An out-of-order native snapshot must not revert the confirmed Android configuration.
    await act(async()=>mockListener?.({preference:'dark',revision:3}));
    expect(mode).toHaveBeenCalledTimes(3);
    expect(mockSetPreference).not.toHaveBeenCalled();
  } finally { screen.unmount(); }
  expect(mockRemove).toHaveBeenCalledTimes(1);
});

test('absence of an authoritative preference does not force a guessed Android mode',async()=>{
  mockGetSnapshot.mockReturnValue(new Promise(()=>{}));
  const mode=jest.spyOn(Appearance,'setColorScheme').mockImplementation(()=>{});
  const screen=render(<LavaUIReview/>);
  try {
    expect(mode).not.toHaveBeenCalled();
    await act(async()=>mockListener?.({preference:'system',revision:1}));
    expect(mode).toHaveBeenCalledWith('unspecified');
  } finally { screen.unmount(); }
});
