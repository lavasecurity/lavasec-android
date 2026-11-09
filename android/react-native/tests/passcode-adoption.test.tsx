import {act,fireEvent,render,screen} from '@testing-library/react-native';
import PasscodeRoot from '../canonical/review/PasscodeRoot.android';
import {PasscodeScreen} from '../canonical/src/flows/passcode/PasscodeScreen';
import NativeForm from '../canonical/specs/LavaPasscodeFormNativeComponent.android';

test('the secure dialog root renders shared composition with an opaque native form and app text scale',()=>{
  render(<PasscodeRoot ownerID="request" title="Enter passcode" subtitle="Unlock Lava" textScale={1.35}/>);
  expect(screen.UNSAFE_getByType(PasscodeScreen)).toBeTruthy();
  const props=screen.UNSAFE_getByType(NativeForm).props;
  expect(props.ownerID).toBe('request');expect(props.fontScale).toBe(1.35);
  for(const key of ['value','text','passcode','onChangeText'])expect(props[key]).toBeUndefined();
});
test('only native metadata can show busy and retry copy while React controls refocus without text',()=>{
  render(<PasscodeRoot ownerID="request" title="Enter passcode" subtitle="Unlock Lava"/>);
  act(()=>fireEvent(screen.UNSAFE_getByType(NativeForm),'stateChange',
    {nativeEvent:{height:92,authenticating:true,message:'Wrong passcode'}}));
  expect(screen.UNSAFE_getByType(PasscodeScreen).props.state.authenticating).toBe(true);
  expect(screen.getByText('Wrong passcode')).toBeTruthy();
  fireEvent(screen.getByTestId('passcode-screen'),'touchEnd');
  expect(screen.UNSAFE_getByType(NativeForm).props.focusRequest).toBe(1);
});
test('a replacement prompt does not retain the previous native metadata',()=>{
  const view=render(<PasscodeRoot ownerID="first" title="First" subtitle=""/>);
  act(()=>fireEvent(screen.UNSAFE_getByType(NativeForm),'stateChange',
    {nativeEvent:{height:92,authenticating:false,message:'Old error'}}));
  view.rerender(<PasscodeRoot ownerID="second" title="Second" subtitle=""/>);
  expect(screen.queryByText('Old error')).toBeNull();
  expect(screen.UNSAFE_getByType(NativeForm).props.ownerID).toBe('second');
});
