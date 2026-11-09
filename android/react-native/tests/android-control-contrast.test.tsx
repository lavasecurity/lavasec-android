import React from 'react';
import {render} from '@testing-library/react-native';
import Choice from '../canonical/specs/LavaChoiceNativeComponent';
import Switch from '../canonical/specs/LavaSwitchNativeComponent';
import Decoration from '../canonical/specs/LavaDecorationNativeComponent';
import {LavaAppearanceContext} from '../canonical/src/appearance';
import {colors} from '../canonical/src/colors';
import {paletteStyle,type PaletteScheme} from '../platform/palette';

function luminance(color:string):number {
  const channels=color.match(/[\d.]+/g)!.slice(0,3).map(Number).map(value=>value/255)
    .map(value=>value<=.04045?value/12.92:((value+.055)/1.055)**2.4);
  return channels[0]!*.2126+channels[1]!*.7152+channels[2]!*.0722;
}
function contrast(a:string,b:string):number {
  const x=luminance(a),y=luminance(b);
  return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
}
function resolved(value:unknown,scheme:PaletteScheme):string {
  return (paletteStyle({color:value},scheme) as {color:string}).color;
}

test('mounted native glyphs follow the confirmed app palette and retain explicit preview overrides',()=>{
  const screen=render(<LavaAppearanceContext.Provider value="dark"><Decoration testID="glyph" symbol="shield"/></LavaAppearanceContext.Provider>);
  expect(screen.getByTestId('glyph')).toHaveProp('colorScheme','dark');
  screen.rerender(<LavaAppearanceContext.Provider value="light"><Decoration testID="glyph" symbol="shield"/></LavaAppearanceContext.Provider>);
  expect(screen.getByTestId('glyph')).toHaveProp('colorScheme','light');
  screen.rerender(<LavaAppearanceContext.Provider value="dark"><Decoration testID="glyph" symbol="shield" colorScheme="light"/></LavaAppearanceContext.Provider>);
  expect(screen.getByTestId('glyph')).toHaveProp('colorScheme','light');
});

test.each(['light','dark'] as const)('%s selected segment text remains readable with the caller accent',scheme=>{
  const screen=render(<Choice label="Appearance" controlID="appearance" options={[{value:'light',label:'Light'},{value:'dark',label:'Dark'}]}
    value="light" disabled={false} tintColor={colors.safeGreen}/>);
  const selected=paletteStyle(screen.getByRole('tab',{name:'Light',selected:true}).props.style,scheme) as {backgroundColor:string};
  const text=paletteStyle(screen.getByText('Light').props.style,scheme) as {color:string};
  expect(contrast(selected.backgroundColor,text.color)).toBeGreaterThanOrEqual(4.5);
});

test.each(['light','dark'] as const)('%s native switch thumb contrasts with both Lava track states',scheme=>{
  const screen=render(<Switch label="Match system" value optimistic disabled={false} pending={false} resetRevision={0} tintColor={colors.safeGreen}/>);
  const on=screen.getByLabelText('Match system').props;
  expect(contrast(resolved(on.thumbTintColor,scheme),resolved(on.trackColorForTrue,scheme))).toBeGreaterThanOrEqual(3);
  screen.rerender(<Switch label="Match system" value={false} optimistic disabled={false} pending={false} resetRevision={1} tintColor={colors.safeGreen}/>);
  const off=screen.getByLabelText('Match system').props;
  expect(contrast(resolved(off.thumbTintColor,scheme),resolved(off.trackColorForFalse,scheme))).toBeGreaterThanOrEqual(3);
});
