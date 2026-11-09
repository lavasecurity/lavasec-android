import {Pressable,StyleSheet,View} from 'react-native';
import type {BottomTabBarButtonProps} from '@react-navigation/bottom-tabs';
import {colors} from '../src/colors';

/** Keep navigation's semantics and target; press feedback is a quiet state layer. */
export function QuietTabButton({children,style,onPress,onLongPress,onLayout,testID,accessibilityLabel,
  accessibilityState,accessibilityRole,role,disabled,'aria-label':label,'aria-selected':selected}:BottomTabBarButtonProps){
  return <Pressable style={[style,{justifyContent:'center'}]} onPress={onPress} onLongPress={onLongPress} onLayout={onLayout}
    testID={testID} accessibilityLabel={label??accessibilityLabel}
    accessibilityState={{...accessibilityState,...(selected===undefined?{}:{selected})}}
    role={role??'tab'} accessibilityRole={accessibilityRole} disabled={disabled}>
    {({pressed})=><>
      {pressed&&<View pointerEvents="none" style={[StyleSheet.absoluteFill,{backgroundColor:colors.primaryText,opacity:0.06}]}/>}
      {children}
    </>}
  </Pressable>;
}
