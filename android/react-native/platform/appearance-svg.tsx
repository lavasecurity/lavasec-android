import Svg,* as Native from 'react-native-svg';
import {withPalette} from './with-palette';
export * from 'react-native-svg';

export default withPalette(Svg);
export const Circle=withPalette(Native.Circle);
export const Line=withPalette(Native.Line);
export const Polyline=withPalette(Native.Polyline);
export const Rect=withPalette(Native.Rect);
export const Text=withPalette(Native.Text);
export const Defs=withPalette(Native.Defs);
export const G=withPalette(Native.G);
export const LinearGradient=withPalette(Native.LinearGradient);
export const Mask=withPalette(Native.Mask);
export const Path=withPalette(Native.Path);
export const Stop=withPalette(Native.Stop);
