import React from 'react';
import {act,render,screen} from '@testing-library/react-native';
import {Alert,AppState,Text,UIManager,View,type GestureResponderEvent} from 'react-native';
import {ReviewContext,type ReviewState} from '../canonical/review/ReviewContext';
import {initialSession} from '../canonical/review/session';
import type {AppSnapshot} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';

jest.mock('@react-navigation/native',()=>({useNavigation:()=>({navigate:jest.fn(),addListener:()=>()=>{}}),useIsFocused:()=>true}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({navigate:jest.fn()}),previewNotice:jest.fn()}));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{getGuardAccents:()=>JSON.stringify({original:{light:'#BF4000',dark:'#FF8855'}})}}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/review/navigation-scaffold',()=>({...jest.requireActual('../canonical/review/navigation-scaffold'),useReducedMotionPreference:()=>true}));
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
import {GuardScreen} from '../canonical/review/screens';
import {LavaActionButton} from '../canonical/src';
import AndroidContextMenu from '../canonical/specs/LavaContextMenuNativeComponent.android';

// The rendered RN Pressable uses the installed Pressability responder callbacks.
// These contacts drive its timer/state machine; they never invoke onLongPress directly.
const contact=()=>({persist:jest.fn(),currentTarget:41,target:41,nativeEvent:{identifier:0,pageX:20,pageY:20,locationX:20,locationY:20,timestamp:0,touches:[{identifier:0,pageX:20,pageY:20}]}} as unknown as GestureResponderEvent);
const begin=(node:ReturnType<typeof screen.getByTestId>)=>{
  const event=contact();expect(node.props.onStartShouldSetResponder()).toBe(true);
  act(()=>node.props.onResponderGrant(event));return event;
};
const release=(node:ReturnType<typeof screen.getByTestId>,event:GestureResponderEvent)=>act(()=>node.props.onResponderRelease(event));
const hold=()=>act(()=>jest.advanceTimersByTime(600));
const actions=[{id:'5',title:'Pause for 5 minutes',symbol:'pause.circle'}];
let previousState:typeof AppState.currentState;
beforeEach(()=>{jest.spyOn(UIManager,'measure').mockImplementation((_tag,callback)=>callback(0,0,100,100,0,0));previousState=AppState.currentState;AppState.currentState='active';jest.useFakeTimers();jest.spyOn(Alert,'alert').mockImplementation(()=>{});});
afterEach(()=>{AppState.currentState=previousState;jest.useRealTimers();jest.restoreAllMocks();});
function guard(disabled=false,canPause=true){
  let admitted=true;
  const command=jest.fn(async(_input:unknown)=>null);
  const live={protection:{title:'Protection On',subtitle:'Current protection',action:'Turn Off',mood:'awake',canPause,pauseOptions:[{minutes:5,title:'Pause for 5 minutes'}],disabled,configuring:false}} as unknown as AppSnapshot;
  const app={command,getSnapshot:()=>({snapshot:live}),getPresentationAuthority:()=>admitted,getPresentationHydration:()=>({required:false})} as unknown as AppStore;
  const value={app,live,session:initialSession(),look:'original'} as unknown as ReviewState;
  const view=render(<ReviewContext.Provider value={value}><GuardScreen/></ReviewContext.Provider>);
  const button=screen.getByRole('button',{name:'Turn Off'});
  return {view,button,command,revoke:()=>{admitted=false;}};
}
function option(index=0){return jest.mocked(Alert.alert).mock.calls.at(-1)![2]![index]!;}

test.each(['cancel','select'] as const)('actual Guard hold opens its menu and release never toggles protection: %s',async outcome=>{
  const t=guard();const event=begin(t.button);hold();
  release(t.button,event);expect(t.command).not.toHaveBeenCalledWith({type:'protection.toggle'});
  expect(Alert.alert).toHaveBeenCalledTimes(1);
  if(outcome==='select')await act(async()=>option().onPress?.());
  else await act(async()=>option(1).onPress?.());
  expect(t.command.mock.calls).toEqual(outcome==='select'?[[{type:'protection.pause',minutes:5}]]:[]);
});

test('a deliberate short Guard tap still toggles once without opening the menu',async()=>{
  const t=guard();const event=begin(t.button);act(()=>jest.advanceTimersByTime(50));release(t.button,event);
  await act(async()=>{});expect(Alert.alert).not.toHaveBeenCalled();expect(t.command).toHaveBeenCalledTimes(1);expect(t.command).toHaveBeenCalledWith({type:'protection.toggle'});
});

test('disabled Guard cannot claim a contact or open its pause menu',()=>{
  const t=guard(true);expect(t.button.props.onStartShouldSetResponder()).toBe(false);
  // The disabled child declines responder ownership, so test the outer contact too.
  const menu=screen.getByTestId('guard.pause-menu');const event=begin(menu);hold();release(menu,event);
  expect(Alert.alert).not.toHaveBeenCalled();expect(t.command).not.toHaveBeenCalled();
});

test('a Guard without pause options keeps its ordinary button semantics and exposes no menu',async()=>{
  const t=guard(false,false);const event=begin(t.button);hold();release(t.button,event);await act(async()=>{});
  expect(Alert.alert).not.toHaveBeenCalled();expect(t.command).toHaveBeenCalledTimes(1);expect(t.command).toHaveBeenCalledWith({type:'protection.toggle'});
});

test.each(['authority','background'] as const)('a held Guard menu cannot mutate after its presentation retires: %s',async boundary=>{
  const t=guard();const event=begin(t.button);hold();expect(Alert.alert).toHaveBeenCalledTimes(1);
  if(boundary==='authority')t.revoke();else AppState.currentState='background';
  release(t.button,event);await act(async()=>option().onPress?.());expect(t.command).not.toHaveBeenCalled();
});

test.each(['terminate','unmount','move'] as const)('cancelled Guard contact does not open the menu or toggle: %s',outcome=>{
  const t=guard();const event=begin(t.button);
  if(outcome==='terminate')act(()=>t.button.props.onResponderTerminate(event));
  else if(outcome==='unmount')t.view.unmount();
  else {const moved={...event,nativeEvent:{...event.nativeEvent,pageX:1000,pageY:1000,touches:[{...event.nativeEvent,pageX:1000,pageY:1000}]}};act(()=>t.button.props.onResponderMove(moved));release(t.button,moved);}
  hold();expect(Alert.alert).not.toHaveBeenCalled();expect(t.command).not.toHaveBeenCalledWith({type:'protection.toggle'});expect(t.command).not.toHaveBeenCalledWith({type:'protection.pause',minutes:5});
});

test('direct actionable child retains its existing long-press and short-press handlers',()=>{
  const onPress=jest.fn(),onLongPress=jest.fn(),onAction=jest.fn();
  render(<AndroidContextMenu contextID="control" actions={actions} onAction={onAction}><LavaActionButton title="Control" onPress={onPress} onLongPress={onLongPress}/></AndroidContextMenu>);
  const button=screen.getByRole('button',{name:'Control'});const event=begin(button);hold();release(button,event);
  expect(onLongPress).toHaveBeenCalledTimes(1);expect(onLongPress).toHaveBeenCalledWith(event);expect(Alert.alert).toHaveBeenCalledTimes(1);expect(onPress).not.toHaveBeenCalled();
  const tap=begin(button);release(button,tap);expect(onPress).toHaveBeenCalledTimes(1);expect(onLongPress).toHaveBeenCalledTimes(1);
});

test('non-actionable domain row keeps the outer menu and its custom accessibility action',()=>{
  const onAction=jest.fn(),onAccessibilityAction=jest.fn();
  render(<AndroidContextMenu testID="domain-menu" contextID="domain" actions={actions} accessibilityActions={[{name:'5',label:'Pause for 5 minutes'}]} onAccessibilityAction={onAccessibilityAction} onAction={onAction}><View><Text>Public domain fixture</Text></View></AndroidContextMenu>);
  const row=screen.getByTestId('domain-menu');const event=begin(row);hold();release(row,event);
  expect(Alert.alert).toHaveBeenCalledTimes(1);act(()=>option().onPress?.());expect(onAction).toHaveBeenCalledWith({nativeEvent:{id:'5'}});
  const action={nativeEvent:{actionName:'5'}};act(()=>row.props.onAccessibilityAction(action));expect(onAccessibilityAction).toHaveBeenCalledWith(action);
});

test('Guard custom accessibility pause action stays on the original button',async()=>{
  const t=guard();expect(t.button.props.accessibilityActions).toEqual([{name:'5',label:'Pause for 5 minutes'}]);
  await act(async()=>t.button.props.onAccessibilityAction({nativeEvent:{actionName:'5'}}));
  expect(t.command).toHaveBeenCalledTimes(1);expect(t.command).toHaveBeenCalledWith({type:'protection.pause',minutes:5});expect(Alert.alert).not.toHaveBeenCalled();
});
