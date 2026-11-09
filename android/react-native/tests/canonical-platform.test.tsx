import React from 'react';
import {render,fireEvent} from '@testing-library/react-native';
import Choice from '../canonical/specs/LavaChoiceNativeComponent';
import ContextMenu from '../canonical/specs/LavaContextMenuNativeComponent';
import {ContextMenu as ReviewContextMenu} from '../canonical/review/ContextMenu';
import {colors} from '../canonical/src/colors';
import TextField from '../canonical/specs/LavaTextFieldNativeComponent';
import {configurePresentation} from '../canonical/app/presentation';

afterEach(()=>configurePresentation({locale:'en',textScales:null}));

test('the shared Android context menu propagates the Block and Allow tints through its adapter',()=>{
  const screen=render(<ReviewContextMenu testID="tinted" actions={[{id:'blocked',title:'Block',symbol:'xmark.circle.fill'},{id:'allowed',title:'Allow',symbol:'arrow.right.circle.fill'}]} onAction={()=>{}}>row</ReviewContextMenu>);
  expect(screen.getByTestId('tinted')).toHaveProp('blockedTintColor',colors.lavaOrange);
  expect(screen.getByTestId('tinted')).toHaveProp('allowedTintColor',colors.safeGreen);
});

test('Android resolves the controlled choice adapter instead of an iOS-only native component',()=>{
  const changed=jest.fn();
  const screen=render(<Choice label="DNS" controlID="dns" options={[{value:'a',label:'A'},{value:'b',label:'B'}]} value="a" disabled={false} onValueChange={changed}/>);
  fireEvent.press(screen.getByRole('tab',{name:'B'}));
  expect(changed).toHaveBeenCalledWith({nativeEvent:{value:'b'}});
  // An unresolved selection is held optimistically rather than snapping back to
  // the previous authoritative value.
  expect(screen.getByRole('tab',{name:'B'}).props.accessibilityState.selected).toBe(true);
});
test('Android context menus render their content without requesting an iOS view manager',()=>{
  const screen=render(<ContextMenu testID="menu" contextID="menu" actions={[]}><TextField inputLabel="Domain" placeholder="example.org" resetRevision={0}/></ContextMenu>);
  expect(screen.getByLabelText('Domain')).toBeTruthy();
});

test.each([[false,false],[true,false],[false,true],[true,true]])('inactive filters can be viewed with switch=%s share=%s',async(canSwitch,canShare)=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const NativeReview=require('../canonical/specs/NativeLavaReview.android').default;
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  try {
    const pending=NativeReview.chooseFilterAction('Other filter',canSwitch,canShare);
    const buttons=alert.mock.calls[0]![2]!;
    expect(buttons.length).toBeLessThanOrEqual(3);
    buttons.find(button=>button.text==='View filter')!.onPress!();
    await expect(pending).resolves.toBe('view');
  } finally { alert.mockRestore(); }
});

test.each(['switch','share'])('keeps %s available when Android needs a second action menu',async(action)=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const NativeReview=require('../canonical/specs/NativeLavaReview.android').default;
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  try {
    const pending=NativeReview.chooseFilterAction('Other filter',true,true);
    alert.mock.calls[0]![2]!.find(button=>button.text==='More actions')!.onPress!();
    const buttons=alert.mock.calls[1]![2]!;
    expect(buttons).toHaveLength(3);
    buttons.find(button=>button.text===(action==='switch'?'Use this filter':'Share'))!.onPress!();
    await expect(pending).resolves.toBe(action);
  } finally { alert.mockRestore(); }
});

test.each([0,1])('the German native filter chooser keeps user data verbatim and cancels page %s without an action',async(page)=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const NativeReview=require('../canonical/specs/NativeLavaReview.android').default;
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  configurePresentation({locale:'de',textScales:null});
  try {
    // "Account" is itself a catalog key; the supplied filter name must remain user data.
    const pending=NativeReview.chooseFilterAction('Account',true,true);
    expect(alert.mock.calls[0]![0]).toBe('Account');
    const first=alert.mock.calls[0]![2]!;
    expect(first.map(button=>button.text)).toEqual(['Filter ansehen','Weitere Aktionen','Abbrechen']);
    if(page===1){
      first.find(button=>button.text==='Weitere Aktionen')!.onPress!();
      expect(alert.mock.calls[1]![0]).toBe('Account');
      expect(alert.mock.calls[1]![2]!.map(button=>button.text)).toEqual(['Diesen Filter verwenden','Teilen','Abbrechen']);
    }
    alert.mock.calls[page]![2]!.find(button=>button.text==='Abbrechen')!.onPress!();
    await expect(pending).resolves.toBeNull();
    expect(alert).toHaveBeenCalledTimes(page+1);
  } finally { alert.mockRestore(); }
});

test.each([0,1,2,3,4])('long-press menu keeps action %s reachable within Android button limits',(index)=>{
  const {Alert}=require('react-native') as typeof import('react-native');
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  const action=jest.fn();
  try {
    const screen=render(<ContextMenu testID="many" contextID="many" actions={Array.from({length:5},(_,i)=>({id:String(i),title:`Action ${i}`,symbol:'pause'}))} onAction={action}/>);
    fireEvent(screen.getByTestId('many'),'longPress');
    let buttons=alert.mock.calls.at(-1)![2]!;
    while(!buttons.some(button=>button.text===`Action ${index}`)) {
      expect(buttons.length).toBeLessThanOrEqual(3);
      expect(buttons.some(button=>button.style==='cancel')).toBe(true);
      buttons.find(button=>button.text==='More options')!.onPress!();
      buttons=alert.mock.calls.at(-1)![2]!;
    }
    expect(buttons.length).toBeLessThanOrEqual(3);
    buttons.find(button=>button.text===`Action ${index}`)!.onPress!();
    expect(action).toHaveBeenCalledTimes(1);
    expect(action).toHaveBeenCalledWith({nativeEvent:{id:String(index)}});
  } finally { alert.mockRestore(); }
});
