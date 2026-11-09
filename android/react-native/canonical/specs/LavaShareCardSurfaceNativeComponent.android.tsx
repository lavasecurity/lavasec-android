import {requireNativeComponent,type ViewProps} from 'react-native';
interface Props extends ViewProps {token:string;payload:string;ready:boolean}
export default requireNativeComponent<Props>('LavaShareCardSurface');
