import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';

// `LavaToggleControl` is the bare accessory-lane switch added to the canonical
// set in Wave 1 (commit e9b9a27): it owns the measured native switch without the
// row label. Pin its spec-leaf behavior on the Android platform leaf.
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn()}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));

import {LavaToggleControl, LavaToggleRow} from '../canonical/src';
import {configurePresentation, localized} from '../canonical/app/presentation';

// The Android host maps the controlled `value`/`disabled` props onto its
// native `on`/`enabled` pair (see gotcha 2026-09-13).
function switchHost(label: string) {
  return render(<LavaToggleControl title={label} value={false} onValueChange={() => {}} disabled={false} />).getByLabelText(label);
}

test('renders only the native switch, never the row label content', () => {
  const {getByLabelText, queryByText} = render(
    <LavaToggleControl title="Feedback" value={false} onValueChange={() => {}} disabled={false} />,
  );
  expect(getByLabelText('Feedback')).toBeTruthy();
  // The bare control lane carries no visible title/summary copy; that anatomy
  // belongs to `LavaToggleRow`.
  expect(queryByText('Feedback')).toBeNull();
});

test('forwards the authoritative value to the native switch', () => {
  const off = switchHost('Feedback');
  expect(off.props.on).toBe(false);

  const on = render(<LavaToggleControl title="Feedback" value onValueChange={() => {}} disabled={false} />).getByLabelText('Feedback');
  expect(on.props.on).toBe(true);
});

test('a disabled leaf is inert and emits nothing', () => {
  const change = jest.fn();
  const screen = render(<LavaToggleControl title="Feedback" value={false} onValueChange={change} disabled />);
  const control = screen.getByLabelText('Feedback');
  expect(control.props.enabled).toBe(false);
  fireEvent(control, 'valueChange', true);
  expect(change).not.toHaveBeenCalled();
});

test('emits the change exactly once per toggle', () => {
  const change = jest.fn();
  const screen = render(<LavaToggleControl title="Feedback" value={false} onValueChange={change} disabled={false} />);
  fireEvent(screen.getByLabelText('Feedback'), 'valueChange', true);
  expect(change).toHaveBeenCalledTimes(1);
  expect(change).toHaveBeenCalledWith(true);
});

test('an optimistic async change holds the requested value until the owner settles', async () => {
  let settle: (() => void) | undefined;
  const change = jest.fn(() => new Promise<void>(resolve => {
    settle = resolve;
  }));
  const screen = render(<LavaToggleControl title="Feedback" value={false} optimistic onValueChange={change} disabled={false} />);
  fireEvent(screen.getByLabelText('Feedback'), 'valueChange', true);

  const held = screen.getByLabelText('Feedback');
  // The requested value is shown while the asynchronous owner is in flight...
  expect(held.props.on).toBe(true);
  // ...and the control is locked (pending) for the duration.
  expect(held.props.enabled).toBe(false);

  await act(async () => {
    settle?.();
  });
  // The owner never committed, so the authoritative `value` reconquers and the
  // internal resetRevision bump clears the held intent.
  expect(screen.getByLabelText('Feedback').props.on).toBe(false);
});

test('LavaToggleRow keeps the row label anatomy and routes its label press through the switch', () => {
  const change = jest.fn();
  const screen = render(<LavaToggleRow title="Feedback" summary="Send usage reports" value={false} onValueChange={change} testID="feedback" />);
  expect(screen.getByText('Feedback')).toBeTruthy();
  // The row label is a press target that toggles to the complementary value.
  fireEvent.press(screen.getByTestId('feedback.label'));
  expect(change).toHaveBeenCalledWith(true);
});


test('verbatim provider names stay unchanged in native switch labels and visible row copy', () => {
  configurePresentation({locale:'de',textScales:null});
  try {
    expect(localized('Cancel')).not.toBe('Cancel');
    const bare=render(<LavaToggleControl title="Cancel" verbatimTitle value={false} onValueChange={()=>{}}/>);
    expect(bare.getByLabelText('Cancel')).toBeTruthy();
    const row=render(<LavaToggleRow title="Cancel" verbatimTitle value={false} onValueChange={()=>{}}/>);
    expect(row.getByText('Cancel')).toBeTruthy();
    expect(row.getByLabelText('Cancel')).toBeTruthy();
    expect(row.queryByText(localized('Cancel'))).toBeNull();
  } finally { configurePresentation({locale:'en',textScales:null}); }
});

test('ordinary toggle copy retains locale lookup when no verbatim identity is supplied', () => {
  configurePresentation({locale:'de',textScales:null});
  try {
    const row=render(<LavaToggleRow title="Cancel" value={false} onValueChange={()=>{}}/>);
    expect(row.getByText(localized('Cancel'))).toBeTruthy();
    expect(row.getByLabelText(localized('Cancel'))).toBeTruthy();
    expect(row.queryByText('Cancel')).toBeNull();
  } finally { configurePresentation({locale:'en',textScales:null}); }
});


test('an externally pending native owner keeps the confirmed value and both gesture sources inert',()=>{
  const change=jest.fn();
  const props={title:'Feedback',value:true,onValueChange:change,optimistic:false};
  const view=render(<LavaToggleRow {...props} pending testID="feedback"/>);
  expect(view.getByLabelText('Feedback').props.on).toBe(true);
  expect(view.getByLabelText('Feedback').props.enabled).toBe(false);
  fireEvent.press(view.getByTestId('feedback.label'));
  fireEvent(view.getByLabelText('Feedback'),'valueChange',false);
  expect(change).not.toHaveBeenCalled();
  view.rerender(<LavaToggleRow {...props} pending={false} testID="feedback"/>);
  fireEvent.press(view.getByTestId('feedback.label'));
  expect(change).toHaveBeenCalledTimes(1);
  expect(change).toHaveBeenCalledWith(false);
});

test('a native-confirmed asynchronous choice never displays the uncommitted value',async()=>{
  let reject!: (error:Error)=>void;
  const change=jest.fn(()=>new Promise<void>((_resolve,fail)=>{reject=fail;}));
  const view=render(<LavaToggleControl title="Feedback" value optimistic={false} onValueChange={change}/>);
  fireEvent(view.getByLabelText('Feedback'),'valueChange',false);
  expect(view.getByLabelText('Feedback').props.on).toBe(true);
  expect(view.getByLabelText('Feedback').props.enabled).toBe(false);
  await act(async()=>{reject(new Error('Authentication cancelled.'));});
  expect(view.getByLabelText('Feedback').props.on).toBe(true);
  expect(view.getByLabelText('Feedback').props.enabled).toBe(true);
  expect(change).toHaveBeenCalledTimes(1);
});

test('a reentrant parent publication cannot dispatch a second native or label gesture',()=>{
  let reenter=()=>{};
  const change=jest.fn(()=>{reenter();});
  const view=render(<LavaToggleRow title="Feedback" value={false} onValueChange={change} testID="feedback"/>);
  const captured=view.getByLabelText('Feedback').props.onChange;
  reenter=()=>captured({nativeEvent:{value:true}});
  fireEvent.press(view.getByTestId('feedback.label'));
  expect(change).toHaveBeenCalledTimes(1);
});

test('a throwing parent releases its exact gesture so a fresh explicit attempt can proceed',()=>{
  const error=new Error('controlled refusal');
  const change=jest.fn().mockImplementationOnce(()=>{throw error;});
  const view=render(<LavaToggleControl title="Feedback" value={false} onValueChange={change}/>);
  expect(()=>fireEvent(view.getByLabelText('Feedback'),'valueChange',true)).toThrow(error);
  fireEvent(view.getByLabelText('Feedback'),'valueChange',true);
  expect(change).toHaveBeenCalledTimes(2);
});
