// Ported from the pinned iOS `ReactNative/tests/navigation-scaffold.test.tsx`
// (lavasec-ios-internal @ c1fdb8e5), adapting the iOS-only floating-tab and
// cross-fade assertions to the Android canonical navigation-scaffold surface.
import {act,renderHook,waitFor} from '@testing-library/react-native';
import {AccessibilityInfo} from 'react-native';
import {ordinaryPushPresentation,useReducedMotionPreference} from '../canonical/review/navigation-scaffold';

afterEach(()=>jest.restoreAllMocks());

test('ordinary push uses the Android default transition while preserving explicit cross-fade',()=>{
  expect(ordinaryPushPresentation(false)).toEqual({animation:'default',animationMatchesGesture:false,fullScreenGestureEnabled:false});
  expect(ordinaryPushPresentation(true)).toEqual({animation:'fade',animationMatchesGesture:true,fullScreenGestureEnabled:false});
  for(const reduced of [false,true]){
    const options=ordinaryPushPresentation(reduced);
    expect(options.presentation).toBeUndefined();
    expect(options.gestureEnabled).toBeUndefined();
    expect(options.fullScreenGestureEnabled).toBe(false);
    expect(options.headerShown).toBeUndefined();
    expect(options.contentStyle).toBeUndefined();
  }
});

test('a late initial accessibility read cannot replace a newer preference',async()=>{
  let finish!:(enabled:boolean)=>void;let receive!:(enabled:boolean)=>void;
  jest.spyOn(AccessibilityInfo,'addEventListener').mockImplementation((_event,listener)=>{receive=listener;return{remove:jest.fn()};});
  jest.spyOn(AccessibilityInfo,'isReduceMotionEnabled').mockReturnValue(new Promise(resolve=>{finish=resolve;}));
  const {result}=renderHook(()=>useReducedMotionPreference());
  act(()=>receive(true));
  await act(async()=>finish(false));
  expect(result.current).toBe(true);
});

test('an unavailable accessibility preference keeps automatic motion disabled',async()=>{
  jest.spyOn(AccessibilityInfo,'isReduceMotionEnabled').mockRejectedValue(new Error('Unavailable'));
  const {result}=renderHook(()=>useReducedMotionPreference());
  await act(async()=>{});
  expect(result.current).toBe(true);
});
