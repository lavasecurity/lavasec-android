import {fireEvent,render,screen} from '@testing-library/react-native';
import {SystemButton} from '../canonical/src/flows/SystemButton.android';

test('disabling the shared Android action retires native click focus and cached presses',()=>{
 const press=jest.fn();const view=render(<SystemButton title="Passed" eventID="visit" onPress={press} testID="result"/>);
 const cached=screen.UNSAFE_getByType(SystemButton).findByProps({focusable:true}).props.onPress;
 expect(screen.getByTestId('result').props.focusable).toBe(true);
 fireEvent.press(screen.getByTestId('result'));expect(press).toHaveBeenCalledTimes(1);
 view.rerender(<SystemButton title="Passed" eventID="visit" onPress={press} disabled testID="result"/>);
 expect(screen.getByTestId('result')).toBeDisabled();expect(screen.getByTestId('result').props.focusable).toBe(false);
 cached();fireEvent.press(screen.getByTestId('result'));expect(press).toHaveBeenCalledTimes(1);
});
