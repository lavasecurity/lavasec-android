import React from 'react';
import {AppState,View} from 'react-native';
import {act,fireEvent,render,screen} from '@testing-library/react-native';
import type {AppSnapshot} from '../canonical/app/contract';
import {PreparationProgressMotion} from '../canonical/src/flows/preparation/motion';

const mockSend=jest.fn().mockResolvedValue(undefined),mockRetire=jest.fn().mockResolvedValue(undefined);
const mockApp={command:mockSend,retirePreparation:mockRetire,subscribe:()=>()=>{},
  getSnapshot:()=>({snapshot:{preparation:mockPreparation}}),
  getPresentationAuthority:()=>mockAuthority,getPresentationHydration:()=>({epoch:1,required:false})};
let mockPreparation:AppSnapshot['preparation'];
let mockAuthority=true;
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true}));
jest.mock('../canonical/review/ReviewContext',()=>({
  useReview:()=>({app:mockApp,look:'original',live:{preparation:mockPreparation}}),
  useOptionalReview:()=>undefined,
}));
jest.mock('../canonical/review/navigation-scaffold',()=>({useReducedMotionPreference:()=>true}));
jest.mock('../canonical/review/primitives',()=>({Symbol:require('react-native').View}));
// Native/GPU artwork leaf is tested by the shared Guardian suite; retain the actual
// shared preparation controller and its fill/completion contract here.
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
import {AndroidPreparation} from '../canonical/review/PreparationScreen.android';

const snapshot=(patch:Partial<NonNullable<AppSnapshot['preparation']>>={}):NonNullable<AppSnapshot['preparation']>=>({
  id:'native-attempt',filterId:'filter',origin:'filters',state:'preparing',progress:0,
  title:'Building the filter',message:'',busy:false,
  recovery:{retry:false,editReturn:false,keepCurrent:false,upgrade:null},...patch,
});
beforeEach(()=>{jest.clearAllMocks();mockAuthority=true;mockPreparation=snapshot();Object.defineProperty(AppState,'currentState',{configurable:true,value:'active'});jest.useFakeTimers();});
afterEach(()=>jest.useRealTimers());

test('shared screen sends recovery for the exact native attempt and unmount retires its latest identity',async()=>{
  mockPreparation=snapshot({state:'failed',progress:.25,title:"We couldn't update your filter",message:'The filter change could not be committed.',
    recovery:{retry:true,editReturn:true,keepCurrent:true,upgrade:null}});
  const view=render(<AndroidPreparation filterId="filter"/>);
  fireEvent.press(screen.getByTestId('preparation.retry'));
  await act(async()=>{});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.retry',id:'native-attempt'});
  mockPreparation=snapshot({id:'retry-attempt'});view.rerender(<AndroidPreparation filterId="filter"/>);
  view.unmount();
  expect(mockRetire).toHaveBeenCalledWith('retry-attempt');
  expect(mockRetire).not.toHaveBeenCalledWith('native-attempt');
});

test('a covered or different filter cannot render native preparation metadata',()=>{
  mockAuthority=false;
  const view=render(<AndroidPreparation filterId="filter"/>);
  expect(screen.queryByTestId('preparation.screen')).toBeNull();
  mockAuthority=true;view.rerender(<AndroidPreparation filterId="different"/>);
  expect(screen.queryByText('Building the filter')).toBeNull();
  expect(mockSend).not.toHaveBeenCalled();
});

test('only a measured current viewport admits a native recovery renderer',async()=>{
  const view=render(<AndroidPreparation filterId="filter"/>);
  expect(mockSend).not.toHaveBeenCalledWith(expect.objectContaining({type:'preparation.enter'}));
  fireEvent(screen.getByTestId('preparation.native-viewport'),'layout',{nativeEvent:{layout:{width:360,height:640}}});
  await act(async()=>{});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.enter',id:'native-attempt'});
  mockSend.mockClear();mockPreparation=snapshot({id:'successor'});view.rerender(<AndroidPreparation filterId="filter"/>);
  expect(mockSend).not.toHaveBeenCalledWith(expect.objectContaining({type:'preparation.enter'}));
  fireEvent(screen.getByTestId('preparation.native-viewport'),'layout',{nativeEvent:{layout:{width:360,height:640}}});
  await act(async()=>{});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.enter',id:'successor'});
});

test('native success paints a determinate fill before acknowledgement even under reduced motion',async()=>{
  mockPreparation=snapshot({state:'success',progress:1,title:'Success'});
  render(<AndroidPreparation filterId="filter"/>);
  fireEvent(screen.getByTestId('preparation.screen'),'layout',{nativeEvent:{layout:{width:360,height:640}}});
  await act(async()=>{});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.rendered',id:'native-attempt'});
  expect(mockSend).not.toHaveBeenCalledWith({type:'preparation.presented',id:'native-attempt'});
  expect(screen.getByTestId('preparation.progress.fill').props.style[1].width).toBe('75%');
});

test('elapsed time alone never makes a native preparing operation successful',()=>{
  const motion=new PreparationProgressMotion();
  motion.update(snapshot(),0);motion.update(snapshot({progress:.75}),10);
  expect(motion.frame(560)).toEqual({progress:.75,glyph:false,complete:false});
  expect(motion.frame(1_000_000).complete).toBe(false);
  motion.update(snapshot({state:'success',progress:1}),1_000_000);
  expect(motion.frame(1_000_549).glyph).toBe(false);
  expect(motion.frame(1_000_550).glyph).toBe(true);
  expect(motion.frame(1_001_199).complete).toBe(false);
  expect(motion.frame(1_001_200).complete).toBe(true);
});

test('background time cannot consume the success hold or a successor attempt',()=>{
  const motion=new PreparationProgressMotion();
  motion.update(snapshot({state:'success',progress:1}),0);
  motion.frame(0);motion.frame(550);motion.pause(600);motion.resume(20_000);
  expect(motion.frame(20_000).complete).toBe(false);
  expect(motion.frame(20_600).complete).toBe(true);
  motion.update(snapshot({id:'replacement'}),20_600);
  expect(motion.frame(30_000)).toEqual({progress:0,glyph:false,complete:false});
});


test('a successful successor runs its own sweep and hold before completion acknowledgement',async()=>{
  mockPreparation=snapshot({state:'success',progress:1,title:'Success'});
  const view=render(<AndroidPreparation filterId="filter"/>);
  fireEvent(screen.getByTestId('preparation.screen'),'layout',{nativeEvent:{layout:{width:360,height:640}}});
  await act(async()=>{jest.advanceTimersByTime(2_000);});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.presented',id:'native-attempt'});
  mockSend.mockClear();mockPreparation=snapshot({id:'successful-successor',state:'success',progress:1,title:'Success'});
  view.rerender(<AndroidPreparation filterId="filter"/>);
  await act(async()=>{});
  expect(mockSend).not.toHaveBeenCalledWith({type:'preparation.presented',id:'successful-successor'});
  expect(screen.getByTestId('preparation.progress.fill').props.style[1].width).toBe('75%');
  await act(async()=>{jest.advanceTimersByTime(500);});
  expect(mockSend).not.toHaveBeenCalledWith({type:'preparation.presented',id:'successful-successor'});
  await act(async()=>{jest.advanceTimersByTime(1_500);});
  expect(mockSend).toHaveBeenCalledWith({type:'preparation.presented',id:'successful-successor'});
});
