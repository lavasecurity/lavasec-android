import type {PropsWithChildren} from 'react';
import {StyleSheet, View, type ColorValue, type StyleProp, type ViewStyle} from 'react-native';
import {colors} from './colors';
import {lavaTokens} from './generated/tokens';

/** Continuous fill/clip and a sibling outline share bounds without clipping the outline twice. */
export function LavaSurface({children, role='card', cornerRadius=lavaTokens.surface.cardCornerRadius, borderColor, plain=false, style, contentStyle, testID}: PropsWithChildren<{
  role?:'card'|'panel'; cornerRadius?:number; borderColor?:ColorValue|null; plain?:boolean; style?:StyleProp<ViewStyle>; contentStyle?:StyleProp<ViewStyle>; testID?:string;
}>) {
  const shape:ViewStyle={borderRadius:cornerRadius};
  const fill=plain?'transparent':colors[role==='panel'?lavaTokens.surface.panelBackground:lavaTokens.surface.cardBackground];
  const stroke=borderColor??(role==='panel'?colors[lavaTokens.surface.panelStroke]:undefined);
  return <View testID={testID} style={style}>
    <View testID={testID?`${testID}.content`:undefined} style={[shape,{overflow:'hidden',backgroundColor:fill},contentStyle]}>{children}</View>
    {stroke!==undefined&&<View testID={testID?`${testID}.surface`:undefined} pointerEvents="none" accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
      style={[StyleSheet.absoluteFill,shape,{overflow:'hidden',borderColor:stroke,borderWidth:lavaTokens.surface.outlineWidth}]} />}
  </View>;
}
