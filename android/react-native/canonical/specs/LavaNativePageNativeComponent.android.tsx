import {requireNativeComponent} from 'react-native';
import type {LavaNativePageProps} from '../android/componentProps';
// The Android host is registered as a legacy view manager (`LavaNativePage`), so
// this platform sibling binds to it directly and forwards `page`/`focused` down
// while `onBack`/`onNavigate` travel back up. The enclosing React navigation stack
// owns the one header, the transition and destination admission.
export default requireNativeComponent<LavaNativePageProps>('LavaNativePage');
