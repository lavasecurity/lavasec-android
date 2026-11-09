import React from 'react';
import {StyleSheet} from 'react-native';
import {render} from '@testing-library/react-native';
import {PresentationContext,Text} from '../canonical/app/presentation';
import {LavaText} from '../canonical/src';
import {Copy} from '../canonical/review/primitives';

jest.mock('@react-navigation/native',()=>({
  useNavigation:()=>({setOptions:jest.fn()}),useIsFocused:()=>true,useRoute:()=>({}),
}));

const ramps=['body','headline','subheadline','footnote','caption1','caption2','title1','title2','title3','largeTitle','callout'] as const;
const largest=Object.fromEntries(ramps.map(ramp=>[ramp,1.35]));

test.each(ramps)('confirmed manual text scale reaches %s once, including non-body semantic ramps',ramp=>{
  const screen=render(<PresentationContext.Provider value={{locale:'en',textScales:largest}}>
    <Text dynamicTypeRamp={ramp} style={{fontSize:20,lineHeight:25}}>Measured role</Text>
  </PresentationContext.Provider>);
  const label=screen.getByText('Measured role');
  expect(StyleSheet.flatten(label.props.style)).toMatchObject({fontSize:27,lineHeight:33.75});
  expect(label.props.allowFontScaling).toBe(false);
  expect(label.props.dynamicTypeRamp).toBeUndefined();
});

test('real shared roles retain their baseline proportions and headings under the largest override',()=>{
  const screen=render(<PresentationContext.Provider value={{locale:'en',textScales:largest}}>
    <LavaText role="cardTitle">Card heading</LavaText>
    <LavaText role="rowTitle">Row label</LavaText>
    <Copy role="body">Body copy</Copy>
    <Copy role="caption">Caption copy</Copy>
  </PresentationContext.Provider>);
  for(const [title,size] of [['Card heading',17],['Row label',16],['Body copy',16],['Caption copy',13]] as const){
    const label=screen.getByText(title);
    expect(StyleSheet.flatten(label.props.style).fontSize).toBeCloseTo(size*1.35);
    expect(label.props.allowFontScaling).toBe(false);
  }
  expect(screen.getByText('Card heading').props.accessibilityRole).toBe('header');
  expect(screen.getByText('Body copy').props.accessibilityRole).toBeUndefined();
});

test('system matching retains native scaling and intentionally fixed metrics remain fixed',()=>{
  const screen=render(<PresentationContext.Provider value={{locale:'en',textScales:null}}>
    <Copy role="body">System body</Copy>
    <Text allowFontScaling={false} style={{fontSize:24}}>Fixed metric</Text>
  </PresentationContext.Provider>);
  expect(screen.getByText('System body').props.allowFontScaling).toBe(true);
  expect(screen.getByText('System body').props.dynamicTypeRamp).toBe('body');
  expect(StyleSheet.flatten(screen.getByText('System body').props.style).fontSize).toBe(16);
  expect(StyleSheet.flatten(screen.getByText('Fixed metric').props.style).fontSize).toBe(24);
});
