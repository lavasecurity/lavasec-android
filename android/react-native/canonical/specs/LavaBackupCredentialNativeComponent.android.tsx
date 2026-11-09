import {requireNativeComponent,type NativeSyntheticEvent,type ViewProps} from 'react-native';
interface Props extends ViewProps {
  ownerID:string;
  fontScale:number;
  onSizeChange:(event:NativeSyntheticEvent<{height:number}>)=>void;
}
/** Native custody only: no credential value prop or text event. */
export default requireNativeComponent<Props>('LavaBackupCredential');
