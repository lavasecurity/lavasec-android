import * as Native from 'react-native';
import {withPalette} from './with-palette';
export * from 'react-native';

// Lava's IME, slider and decoration leaves use custom host components. Keep their
// props live too, while refs continue to target the original native control.
export function requireNativeComponent<T extends {}>(viewName:string):Native.HostComponent<T> {
  return withPalette(Native.requireNativeComponent<T>(viewName));
}

export const View=withPalette(Native.View);
export const Text=withPalette(Native.Text);
export const Pressable=withPalette(Native.Pressable);
export const KeyboardAvoidingView=withPalette(Native.KeyboardAvoidingView);
export const ScrollView=withPalette(Native.ScrollView);
export const FlatList=withPalette(Native.FlatList);
export const SectionList=withPalette(Native.SectionList);
export const TextInput=withPalette(Native.TextInput);
export const Image=withPalette(Native.Image);
export const ImageBackground=withPalette(Native.ImageBackground);
export const ActivityIndicator=withPalette(Native.ActivityIndicator);
export const Switch=withPalette(Native.Switch);
export const RefreshControl=withPalette(Native.RefreshControl);
export const SafeAreaView=withPalette(Native.SafeAreaView);
export const Modal=withPalette(Native.Modal);
export const Animated={...Native.Animated,
  View:Native.Animated.createAnimatedComponent(View),
  Text:Native.Animated.createAnimatedComponent(Text),
  Image:Native.Animated.createAnimatedComponent(Image),
  ScrollView:Native.Animated.createAnimatedComponent(ScrollView),
  FlatList:Native.Animated.createAnimatedComponent(FlatList),
  SectionList:Native.Animated.createAnimatedComponent(SectionList),
};
