import {type ComponentProps} from 'react';
import Decoration from '../../specs/LavaDecorationNativeComponent';
import {useTextScale} from '../../app/text-metrics';
import {FlowSheet} from '../../review/form-scaffold';
import {foundation} from '../foundation';

/** Native font scaling sizes glyphs; Android keeps its own layout dimensions. */
export function FlowSymbol({name,size=foundation.control.glyph,height,pointSize=0,weight='regular',tone='green',dynamicTypeRamp,responsive=true}: {
  name:string;size?:number;height?:number;pointSize?:number;weight?:'regular'|'semibold'|'bold';tone?:string;dynamicTypeRamp?:string;responsive?:boolean;
}){
  const textScale=useTextScale(dynamicTypeRamp??'body');
  const scale=responsive?textScale:1;
  return <Decoration symbol={name} tone={tone} fontPointSize={(pointSize||size)*scale} fontWeight={weight}
    style={{width:size*scale,height:(height??size)*scale}} accessible={false} accessibilityElementsHidden/>;
}

/** Android's viewport owns the sheet and IME. UIKit compact-detent measurement
 * is intentionally absent: viewport height must not masquerade as content size. */
export function AndroidFlowSheet({onNaturalSize,compactHeight,...props}:ComponentProps<typeof FlowSheet>&{compactHeight?:boolean;onNaturalSize?:(height:number)=>void}){
  return <FlowSheet {...props}/>;
}
