import {cloneElement,isValidElement} from 'react';
import {Alert,Pressable} from 'react-native';
import type {ColorValue,GestureResponderEvent} from 'react-native';
import type {LavaContextMenuProps} from '../android/componentProps';
import {localized} from '../app/presentation';

/**
 * The Android adapter's native prop surface. `blockedTintColor`/`allowedTintColor` mirror the iOS
 * `LavaContextMenuNativeComponent` spec: Block keeps the orange mark and Allow the shared green one.
 * Android's platform action sheet cannot render per-row glyphs, so the tints ride the adapter (and
 * its Pressable host) as the parity contract; a future native action-sheet view consumes them.
 */
type AndroidContextMenuProps = LavaContextMenuProps & {allowedTintColor?: ColorValue};

export default function AndroidContextMenu(p: AndroidContextMenuProps) {
  const show=(offset=0)=>{
    const remaining=p.actions.slice(offset);
    if(!remaining.length)return;
    const visible=remaining.length>2?remaining.slice(0,1):remaining;
    Alert.alert(p.accessibilityLabel??localized('Options'),undefined,[
      ...visible.map(action=>({text:action.title,onPress:()=>p.onAction?.({nativeEvent:{id:action.id}} as never)})),
      ...(remaining.length>2?[{text:localized('More options'),onPress:()=>show(offset+1)}]:[]),
      {text:localized('Cancel'),style:'cancel'},
    ]);
  };
  // A direct actionable child owns its contact. Its own long-press callback
  // lets Pressability consume release instead of also invoking the child's tap.
  // Non-actionable rows retain the outer menu; do not capture descendants.
  const child=isValidElement<{onPress?:unknown;onLongPress?:(event:GestureResponderEvent)=>void;disabled?:boolean}>(p.children)?p.children:undefined;
  const actionable=typeof child?.props.onPress==='function';
  const content=child&&actionable&&child.props.disabled!==true&&p.actions.length
    ?cloneElement(child,{onLongPress:event=>{child.props.onLongPress?.(event);show();}})
    :p.children;
  return <Pressable {...p} accessible={p.accessible} onLongPress={actionable?undefined:()=>show()}>{content}</Pressable>;
}
