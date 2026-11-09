import type {LavaToolbarAction} from '../contracts';
import {toolbarButton,toolbarToggle} from '../../../review/scaffold';

/** Android native-stack header slots retain the shared semantic action identity. */
const symbols={back:'chevron.left',close:'xmark',confirm:'checkmark',delete:'trash',edit:'square.and.pencil',
  automatic:'moon',reload:'arrow.clockwise',refresh:'arrow.triangle.2.circlepath',reset:'arrow.counterclockwise',
  add:'plus',share:'square.and.arrow.up',import:'square.and.arrow.down',calendar:'calendar',notes:'pencil',assist:'eye',hide:'eye.slash',undo:'arrow.uturn.backward'} as const;
export {toolbarButton,toolbarToggle};
export function toolbarAction(label:string,action:LavaToolbarAction,onPress:()=>void,disabled=false,testID?:string){
  return toolbarButton(label,symbols[action],onPress,disabled,testID);
}
