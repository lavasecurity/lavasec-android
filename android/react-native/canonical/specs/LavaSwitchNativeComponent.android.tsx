import {useEffect,useState} from 'react';
import {Switch,type LayoutChangeEvent} from 'react-native';
import type {LavaSwitchProps} from '../android/componentProps';
import {colors} from '../src/colors';

// The platform leaf keeps a requested value while an asynchronous owner settles,
// so a controlled parent that has not yet committed cannot snap the switch back.
// `resetRevision` (or an authoritative `value` that matches the request)
// reconciles the held intent; `pending` locks the control for the duration.
export default function AndroidSwitch(p:LavaSwitchProps) {
  const [intent,setIntent]=useState<boolean>();
  const displayed=p.optimistic?(intent??p.value):p.value;
  useEffect(()=>{setIntent(undefined);},[p.resetRevision]);
  useEffect(()=>{if(intent!==undefined&&intent===p.value)setIntent(undefined);},[intent,p.value]);
  const change=(next:boolean)=>{
    if(p.disabled||p.pending)return;
    if(p.optimistic)setIntent(next);
    p.onValueChange?.({nativeEvent:{value:next}} as never);
  };
  return <Switch testID={p.testID} accessibilityLabel={p.label} accessibilityHint={p.accessibilityHint} accessible={p.accessible} value={displayed}
    disabled={p.disabled||p.pending} trackColor={{false:colors.disabledSurface,true:colors.softGreen}}
    thumbColor={displayed?(p.tintColor??colors.safeGreen):colors.secondaryText}
    onValueChange={change}
    onLayout={(event:LayoutChangeEvent)=>{
      const {width,height}=event.nativeEvent.layout;
      p.onSizeChange?.({nativeEvent:{width,height}} as never);
    }}
    style={p.style}/>;
}
