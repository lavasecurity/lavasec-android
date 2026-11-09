import {useContext,useImperativeHandle,useRef,useState,type ComponentRef,type Ref} from 'react';
import {StyleSheet,type TextInputProps} from 'react-native';
import NativeInput from '../specs/LavaTextFieldNativeComponent';
import {AndroidFieldPresentationContext} from './presentation-owner.android';

export type BufferedInputHandle=ComponentRef<typeof NativeInput>;
export type BufferedInputProps=TextInputProps&{
  ref?:Ref<BufferedInputHandle>;resetRevision?:number;characterLimit?:number;grows?:boolean;nativeKind?:'wireGuardName'|'localLogSearch';ownerID?:string;
};
/** Android's bounded native EditText retains its composing buffer. A value is
 * the seed for an explicit reset revision, never an acknowledgement to echo
 * over later typing. Confidential configuration is a separate owner-only leaf. */
export function BufferedInput({ref,value,defaultValue,resetRevision=0,characterLimit=0,grows=false,nativeKind,ownerID,...props}:BufferedInputProps){
  const scopedInteractive=useContext(AndroidFieldPresentationContext);
  const input=useRef<BufferedInputHandle>(null);const [height,setHeight]=useState(0);
  useImperativeHandle(ref,()=>input.current!,[]);
  const style=StyleSheet.flatten(props.style),minimum=Number(style?.minHeight??0);
  return <NativeInput ref={input} testID={props.testID} accessibilityLabel={props.accessibilityLabel}
    inputLabel={props.accessibilityLabel??''} placeholder={props.placeholder??''} value={value??defaultValue??''} resetRevision={resetRevision}
    kind={(nativeKind==='localLogSearch'?'search':nativeKind)??(props.multiline?'prose':props.clearButtonMode?'search':'plain')} ownerID={ownerID} editable={scopedInteractive&&props.editable!==false}
    keyboardType={props.keyboardType} autoCapitalize={props.autoCapitalize} autoCorrect={props.autoCorrect} spellCheck={props.spellCheck} smartInsertDelete={props.smartInsertDelete} clearButtonMode={props.clearButtonMode} characterLimit={characterLimit} maxLength={props.maxLength??-1} selectionColor={props.selectionColor}
    monospace={style?.fontFamily==='monospace'}
    fontPointSize={style?.fontSize} lineHeight={style?.lineHeight} textColor={style?.color} placeholderTextColor={props.placeholderTextColor}
    autoFocus={props.autoFocus}
    onChange={event=>{if(scopedInteractive&&props.editable!==false)props.onChangeText?.(event.nativeEvent.text);}}
    onSubmit={event=>{if(scopedInteractive&&props.editable!==false)props.onSubmitEditing?.(event as unknown as Parameters<NonNullable<TextInputProps['onSubmitEditing']>>[0]);}}
    onFocusChange={event=>{if(event.nativeEvent.focused)props.onFocus?.(event as unknown as Parameters<NonNullable<TextInputProps['onFocus']>>[0]);else props.onBlur?.(event as unknown as Parameters<NonNullable<TextInputProps['onBlur']>>[0]);}}
    onSizeChange={event=>{if(grows)setHeight(current=>current===event.nativeEvent.height?current:event.nativeEvent.height);}}
    style={[props.style,grows&&height?{height:Math.max(minimum,height)}:undefined]}/>;
}
