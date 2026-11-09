import React from 'react';
import {render,screen} from '@testing-library/react-native';
import {View} from 'react-native';
import {PresentationContext} from '../canonical/app/presentation';
import {PreparationProgressMotion} from '../canonical/src/flows/preparation/motion';
import type {PreparationSnapshot} from '../canonical/src/flows/preparation/contract';
import {LavaSurface} from '../canonical/src/surface.android';
import {FlowSymbol} from '../canonical/src/flows/layout.android';
import {colors} from '../canonical/src/colors';
import {lavaTokens} from '../canonical/src/generated/tokens';

jest.mock('@react-navigation/native',()=>({useNavigation:()=>({setOptions:jest.fn()}),useIsFocused:()=>true}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));

const snapshot=(progress:number):PreparationSnapshot=>({id:'attempt',origin:'filters',state:'preparing',progress,
  title:'Building the filter',message:'',busy:true,recovery:{retry:false,editReturn:false,keepCurrent:false,upgrade:null}});

test('settled fractional progress stops requesting frames at the exact native target',()=>{
  const motion=new PreparationProgressMotion();
  motion.update(snapshot(.1),0);motion.update(snapshot(.3),10);
  expect(motion.needsFrame(559)).toBe(true);
  expect(motion.frame(560)).toEqual({progress:.3,glyph:false,complete:false});
  expect(motion.needsFrame(560)).toBe(false);
  expect(motion.needsFrame(1_000_000)).toBe(false);
});

test('implicit outline belongs to a panel and a supplied outline remains authoritative',()=>{
  const view=render(<LavaSurface testID="surface"/>);
  expect(screen.queryByTestId('surface.surface',{includeHiddenElements:true})).toBeNull();
  view.rerender(<LavaSurface testID="surface" role="panel" plain/>);
  expect(screen.getByTestId('surface.surface',{includeHiddenElements:true})).toHaveStyle({borderColor:colors[lavaTokens.surface.panelStroke]});
  view.rerender(<LavaSurface testID="surface" role="card" borderColor="#ff0000"/>);
  expect(screen.getByTestId('surface.surface',{includeHiddenElements:true})).toHaveStyle({borderColor:'#ff0000'});
});

test('fixed success glyph preserves its native point size across text scales',()=>{
  const glyph=(responsive:boolean)=><PresentationContext.Provider value={{locale:'en',textScales:{body:2}}}>
    <FlowSymbol name="checkmark.circle" size={66} responsive={responsive}/>
  </PresentationContext.Provider>;
  const view=render(glyph(false));
  const decoration=()=>view.UNSAFE_getAllByType(View).find(node=>node.props.symbol==='checkmark.circle')!;
  expect(decoration().props.fontPointSize).toBe(66);
  expect(decoration().props.style).toEqual({width:66,height:66});
  view.rerender(glyph(true));
  expect(decoration().props.fontPointSize).toBe(132);
  expect(decoration().props.style).toEqual({width:132,height:132});
});
