import {useRef} from 'react';
import {Pressable} from 'react-native';
import {Text,localized} from '../../app/presentation';
import {colors} from '../colors.android';
import type {SystemButtonProps} from './system-button';

export function SystemButton({title,eventID,onPress,prominent=false,disabled=false,destructive=false,testID}:SystemButtonProps){
  const latest=useRef({eventID,onPress,disabled});latest.current={eventID,onPress,disabled};
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={localized(title)} accessibilityState={{disabled}} disabled={disabled} focusable={!disabled}
    onPress={()=>{const current=latest.current;if(!current.disabled&&current.eventID===eventID)current.onPress();}}
    style={({pressed})=>({minHeight:48,paddingHorizontal:16,justifyContent:'center',borderRadius:16,alignSelf:'flex-start',backgroundColor:prominent?colors.safeControlGreen:'transparent',opacity:disabled ? 0.4 : pressed ? 0.94 : 1})}>
    <Text style={{fontSize:16,fontWeight:'600',color:disabled?colors.secondaryText:destructive?colors.errorText:prominent?colors.actionForeground:colors.safeGreen}}>{title}</Text>
  </Pressable>;
}
