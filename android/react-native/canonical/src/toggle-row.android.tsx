import {useState} from 'react';
import {useToggleRequest} from './ui/behavior';
import {localized} from '../app/presentation';
import NativeSwitch from '../specs/LavaSwitchNativeComponent';
import {colors} from './colors';
import type {LavaToggleRowProps} from './contracts';
import {LavaControlContent} from './row-content';
import {foundation} from './foundation';

// Android owns native switch measurement; shared behavior owns acknowledgement.
function useToggleControl({title,summary,verbatimTitle=false,value,onValueChange,disabled=false,pending:externalPending=false,optimistic=false,accessibilityHint,testID}:LavaToggleRowProps) {
  const {intent,resetRevision,change}=useToggleRequest({onValueChange,disabled,pending:externalPending});
  const [size,setSize]=useState<{width:number;height:number}>(foundation.nativeSwitch);
  const control=<NativeSwitch testID={testID} label={verbatimTitle?title:localized(title)} accessibilityHint={accessibilityHint!==undefined?localized(accessibilityHint):summary?localized(summary):undefined} accessible={false}
      pointerEvents={externalPending||intent!==undefined?'none':'auto'} pending={externalPending||intent!==undefined} value={optimistic?(intent??value):value}
      optimistic={optimistic} resetRevision={resetRevision} disabled={disabled} onValueChange={event=>change(event.nativeEvent.value)}
      onSizeChange={event=>{const {width,height}=event.nativeEvent;if(width>0&&height>0&&Number.isFinite(width)&&Number.isFinite(height))setSize({width,height});}}
      tintColor={colors.safeGreen} style={size}/>;
  return {change,control};
}

// The same native switch can occupy a numbered list row's accessory lane.
export function LavaToggleControl(props:LavaToggleRowProps) { return useToggleControl(props).control; }
export function LavaToggleRow(props:LavaToggleRowProps) {
  const {change,control}=useToggleControl(props);
  return <LavaControlContent title={props.title} verbatimTitle={props.verbatimTitle} summary={props.summary} titleRole={props.titleRole} disabled={props.disabled} onLabelPress={()=>change(!props.value)} testID={props.testID}>{control}</LavaControlContent>;
}
