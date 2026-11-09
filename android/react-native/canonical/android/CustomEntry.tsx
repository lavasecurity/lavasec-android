import {useRoute,type RouteProp} from '@react-navigation/native';
import type {ReviewRoutes} from '../review/navigation';
import {CustomBlocklistEntryScreen} from './CustomBlocklistEntry';
import {CustomDNSEntryScreen} from './CustomDNSEntry';

/**
 * The shared `CustomEntry` route serves two kinds. The Android navigator renders an RN editor for
 * each: the custom DNS provider form and the custom blocklist form. Only the Android navigator
 * references this wrapper.
 */
export function CustomEntryRoute() {
  const route=useRoute<RouteProp<ReviewRoutes,'CustomEntry'>>();
  return route.params.kind==='dns'?<CustomDNSEntryScreen/>:<CustomBlocklistEntryScreen/>;
}
