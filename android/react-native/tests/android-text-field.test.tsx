import React from 'react';
import {render,fireEvent} from '@testing-library/react-native';
import TextField from '../canonical/specs/LavaTextFieldNativeComponent';
import {AndroidFieldAutoFocusContext} from '../canonical/review/presentation-owner.android';
import {BufferedInput} from '../canonical/review/BufferedInput';
import {DomainInput} from '../canonical/review/scaffold';

// Native buffer/IME/reset enforcement lives in LavaTextFieldViewTest and the
// packaged InputConnection journey. These tests exercise the Fabric boundary.
test('forwards ordinary fields while native owns acknowledgements and explicit resets',()=>{
  const screen=render(<TextField testID="input" inputLabel="Domain" placeholder="example.org" value="seed" resetRevision={0} ownerID="visit" characterLimit={80}/>);
  const input=screen.getByTestId('input');
  expect(input.props.inputLabel).toBe('Domain');expect(input.props.value).toBe('seed');
  expect(input.props.ownerID).toBe('visit');expect(input.props.characterLimit).toBe(80);
  screen.rerender(<TextField testID="input" inputLabel="Domain" placeholder="example.org" value="explicit reset" resetRevision={1} ownerID="visit"/>);
  expect(screen.getByTestId('input').props.resetRevision).toBe(1);
});

test('forwards ordinary committed text and submission events',()=>{
  const change=jest.fn();const submit=jest.fn();
  const screen=render(<TextField testID="input" inputLabel="Domain" placeholder="example.org" resetRevision={0} onChange={change} onSubmit={submit}/>);
  const input=screen.getByTestId('input');
  fireEvent(input,'change',{nativeEvent:{text:'例え.テスト'}});fireEvent(input,'submit',{nativeEvent:{text:'例え.テスト'}});
  expect(change).toHaveBeenCalledWith({nativeEvent:{text:'例え.テスト'}});expect(submit).toHaveBeenCalledWith({nativeEvent:{text:'例え.テスト'}});
});

test('maps composition limits keyboard traits height and focus for shared prose fields',()=>{
  const size=jest.fn();const focus=jest.fn();
  const screen=render(<TextField testID="input" inputLabel="Details" placeholder="Details" kind="prose" resetRevision={0}
    characterLimit={2000} keyboardType="default" autoCapitalize="sentences" autoCorrect spellCheck smartInsertDelete
    lineHeight={22} editable={false} onSizeChange={size} onFocusChange={focus}/>);
  const input=screen.getByTestId('input');
  expect(input.props.kind).toBe('prose');expect(input.props.characterLimit).toBe(2000);
  expect(input.props.editable).toBe(false);expect(input.props.spellCheck).toBe(true);
  fireEvent(input,'sizeChange',{nativeEvent:{height:120}});fireEvent(input,'focusChange',{nativeEvent:{focused:false}});
  expect(size).toHaveBeenCalledWith({nativeEvent:{height:120}});expect(focus).toHaveBeenCalledWith({nativeEvent:{focused:false}});
});

test('confidential WireGuard is owner-ID-only even if a caller accidentally supplies text props',()=>{
  const screen=render(<TextField testID="secret" inputLabel="Content" placeholder="Paste .conf" kind="wireGuard" ownerID="native-owner"
    resetRevision={3} value="synthetic confidential buffer" onChange={jest.fn()} onSubmit={jest.fn()} editable/>);
  const input=screen.getByTestId('secret');
  expect(input.props.ownerID).toBe('native-owner');expect(input.props.value).toBeUndefined();
  expect(input.props.onChange).toBeUndefined();expect(input.props.onSubmit).toBeUndefined();
  expect(input.props.editable).toBeUndefined();expect(input.props.resetRevision).toBe(0);
});


test('route transition focus admission reaches direct DomainInput and BufferedInput without changing their edit or measurement admission',()=>{
  const change=jest.fn();const submit=jest.fn();
  const fields=(ready:boolean)=><AndroidFieldAutoFocusContext.Provider value={ready}>
    <DomainInput label="Domain" placeholder="example.org" onChange={change} onSubmit={submit}/>
    <BufferedInput testID="buffered" autoFocus value="retained search" editable onChangeText={change}/>
  </AndroidFieldAutoFocusContext.Provider>;
  const view=render(fields(false));
  const domain=view.getByLabelText('Domain');const buffered=view.getByTestId('buffered');
  expect(domain.props.autoFocus).toBe(true);expect(buffered.props.autoFocus).toBe(true);
  expect(domain.props.autoFocusReady).toBe(false);expect(buffered.props.autoFocusReady).toBe(false);
  expect(domain.props.editable).toBe(true);expect(buffered.props.editable).toBe(true);
  fireEvent(domain,'sizeChange',{nativeEvent:{height:44}});
  expect(view.getByLabelText('Domain').props.style.height).toBe(44);
  fireEvent(domain,'change',{nativeEvent:{text:'example.org'}});
  expect(change).toHaveBeenCalledWith('example.org');
  view.rerender(fields(true));
  expect(view.getByLabelText('Domain').props.autoFocusReady).toBe(true);
  expect(view.getByTestId('buffered').props.autoFocusReady).toBe(true);
  expect(view.getByTestId('buffered').props.value).toBe('retained search');
  view.rerender(fields(false));
  expect(view.getByLabelText('Domain').props.autoFocusReady).toBe(false);
  expect(view.getByTestId('buffered').props.autoFocusReady).toBe(false);
});

test('an isolated field keeps its autofocus default while a production route cannot override a revoked focus receipt',()=>{
  const isolated=render(<TextField testID="isolated" autoFocus inputLabel="Name" placeholder="Name" resetRevision={0}/>);
  expect(isolated.getByTestId('isolated').props.autoFocusReady).toBe(true);
  isolated.unmount();
  const view=render(<AndroidFieldAutoFocusContext.Provider value={false}>
    <TextField testID="scoped" autoFocus autoFocusReady inputLabel="Name" placeholder="Name" resetRevision={0}/>
  </AndroidFieldAutoFocusContext.Provider>);
  expect(view.getByTestId('scoped').props.autoFocusReady).toBe(false);
});

test('the native confidential owner leaf does not receive autofocus props from a route focus receipt',()=>{
  const view=render(<AndroidFieldAutoFocusContext.Provider value={true}>
    <TextField testID="private" kind="wireGuard" ownerID="native-owner" autoFocus autoFocusReady
      inputLabel="Content" placeholder="Paste .conf" resetRevision={0}/>
  </AndroidFieldAutoFocusContext.Provider>);
  expect(view.getByTestId('private').props.autoFocus).toBeUndefined();
  expect(view.getByTestId('private').props.autoFocusReady).toBeUndefined();
});
