import {StatusBar} from 'react-native';

/**
 * Android top system-bar inset.
 *
 * The host deliberately does not run the stock React Native autolinking
 * plugin, so `react-native-safe-area-context` (the usual source of Android
 * insets) is not resolvable here. RN core `SafeAreaView` only insets iOS, so
 * an edge-to-edge API 35/36 window must pad for the status bar itself. The
 * status-bar height is the top inset the window needs; it is null off Android.
 */
export function androidTopInset(): number {
  return StatusBar.currentHeight ?? 0;
}
