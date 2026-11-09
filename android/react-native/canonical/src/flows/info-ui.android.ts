import {createElement,type PropsWithChildren} from 'react';
import {View,type ColorValue} from 'react-native';
import {LavaCard} from '../components.android';
import {colors} from '../colors.android';
import {foundation} from '../foundation';
export {Copy} from '../../review/primitives';
export {InlineSymbolText} from './InlineSymbolText.android';

export function Panel({children,warning=false,accent}:PropsWithChildren<{warning?:boolean;accent?:ColorValue}>){
  const body=createElement(View,{style:{minHeight:foundation.row.standard-foundation.space.infoPanelVerticalInset*2,justifyContent:'center'}},children);
  return createElement(View,null,createElement(LavaCard,{role:'panel',borderColor:warning||accent?accent??colors.lavaOrange:null,children:body}));
}
