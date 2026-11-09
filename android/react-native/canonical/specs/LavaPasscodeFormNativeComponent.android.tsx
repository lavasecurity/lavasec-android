import {requireNativeComponent,type NativeSyntheticEvent,type ViewProps} from 'react-native';
interface Props extends ViewProps {
  ownerID:string;
  fontScale:number;
  focusRequest:number;
  onStateChange:(event:NativeSyntheticEvent<{height:number;authenticating:boolean;message:string}>)=>void;
}
export default requireNativeComponent<Props>('LavaPasscodeForm');
