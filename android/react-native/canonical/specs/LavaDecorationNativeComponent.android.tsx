import {requireNativeComponent} from 'react-native';
import type {LavaDecorationProps} from '../android/componentProps';
import {useLavaColorScheme} from '../src/appearance';
const Decoration=requireNativeComponent<LavaDecorationProps>('LavaDecoration');
export default function AndroidDecoration(props:LavaDecorationProps) {
  const scheme=useLavaColorScheme();
  // The native contact recognizer owns cancellation, taps and hold timing.
  return <Decoration {...props} colorScheme={props.colorScheme==='light'||props.colorScheme==='dark'?props.colorScheme:scheme}/>;
}
