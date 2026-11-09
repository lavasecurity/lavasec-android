import {requireNativeComponent,type ViewProps} from 'react-native';
interface Props extends ViewProps {payload:string;moduleCount:number}
export default requireNativeComponent<Props>('LavaShareQr');
