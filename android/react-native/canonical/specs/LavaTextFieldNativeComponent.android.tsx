import {forwardRef,useContext,type ComponentRef} from 'react';
import {requireNativeComponent} from 'react-native';
import type {LavaTextFieldProps} from '../android/componentProps';
import {colors} from '../src/colors';
import {AndroidFieldAutoFocusContext} from '../review/presentation-owner.android';

const NativeField=requireNativeComponent<LavaTextFieldProps>('LavaTextField');
/** Shared pages own composition; this leaf owns uninterrupted Android IME state.
 * Confidential configuration accepts only its native owner ID and bounded style.
 * No configuration value or text callback may enter Fabric, even by accident. */
export default forwardRef<ComponentRef<typeof NativeField>,LavaTextFieldProps>(function AndroidTextField(props,ref){
  const autoFocusReady=useContext(AndroidFieldAutoFocusContext);
  if(props.kind==='wireGuard')return <NativeField ref={ref} testID={props.testID} style={props.style}
    kind="wireGuard" ownerID={props.ownerID} inputLabel={props.inputLabel} placeholder={props.placeholder}
    resetRevision={0} fontPointSize={props.fontPointSize} onFocusChange={props.onFocusChange}
    onSizeChange={props.onSizeChange}/>;
  return <NativeField {...props} ref={ref} autoFocusReady={autoFocusReady} accessibilityLabel={props.accessibilityLabel??props.inputLabel} textColor={props.textColor??colors.primaryText}
    placeholderTextColor={props.placeholderTextColor??colors.secondaryText}
    selectionColor={props.selectionColor??colors.safeControlGreen}/>;
});
