import {useState} from 'react';
import NativeChoice from '../specs/LavaChoiceNativeComponent';
import type {LavaChoiceProps} from './contracts';
import {useChoiceRequest} from './ui/behavior';
import {colors} from './colors';

/** Stable values and acknowledgements are shared; Android measures its native control. */
export function LavaChoice<Value extends string>(props:LavaChoiceProps<Value>) {
  const {label,options,reselectValue,disabled=false,testID,presentation='segments'}=props;
  const {displayed,selectionRevision,change}=useChoiceRequest(props);
  const [height,setHeight]=useState(48);
  return <NativeChoice label={label} controlID={testID??label} options={options} value={displayed}
    selectionRevision={selectionRevision} reselectValue={reselectValue} disabled={disabled}
    stepper={presentation==='stepper'} pageControl={presentation==='pages'} accessible={false}
    testID={testID} tintColor={colors.safeGreen} style={presentation==='stepper'?{height,width:120}:{height,alignSelf:'stretch'}}
    onValueChange={event=>change(event.nativeEvent.value)}
    onSizeChange={event=>{const next=event.nativeEvent.height;if(Number.isFinite(next)&&next>0)setHeight(next);}}/>;
}
