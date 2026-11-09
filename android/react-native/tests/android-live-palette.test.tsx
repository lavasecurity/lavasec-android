import React,{useState} from 'react';
import {Animated as NativeAnimated,PlatformColor,StyleSheet} from 'react-native';
import {fireEvent,render} from '@testing-library/react-native';
import {View,Text,Pressable,TextInput,Switch,Animated,requireNativeComponent} from '../platform/appearance-native';
import {AndroidPaletteContext} from '../platform/palette-context';
import {paletteColor,paletteProps,paletteStyle,type PaletteScheme} from '../platform/palette';
import {colors} from '../canonical/src/colors';

const card=StyleSheet.create({saved:{backgroundColor:colors.cardBackground,borderColor:colors.separator}});
const NativeField=requireNativeComponent<{testID:string;value:string;textColor:unknown;selectionColor:unknown}>('PaletteFixture');

test('live Light and Dark resolve mounted surfaces, text and press states without retiring local input',()=>{
  const mounted=jest.fn();
  function Content(){
    const [draft,setDraft]=useState('unfinished draft');
    React.useEffect(()=>{mounted();},[]);
    return <View testID="card" style={card.saved}>
      <Text testID="label" style={{color:colors.primaryText}}>Label</Text>
      <TextInput testID="draft" value={draft} onChangeText={setDraft} style={{color:colors.primaryText}} selectionColor={colors.safeGreen}/>
      <Pressable testID="action" style={({pressed})=>({backgroundColor:pressed?colors.pressedSurface:colors.safeControlGreen})}>
        <Text style={{color:colors.actionForeground}}>Save</Text>
      </Pressable>
      <Switch testID="toggle" value trackColor={{false:colors.disabledSurface,true:colors.safeControlGreen}}/>
      <NativeField testID="native-field" value={draft} textColor={colors.primaryText} selectionColor={colors.safeControlGreen}/>
    </View>;
  }
  const Fixture=({scheme}:{scheme:PaletteScheme})=><AndroidPaletteContext.Provider value={scheme}><Content/></AndroidPaletteContext.Provider>;
  const screen=render(<Fixture scheme="dark"/>);
  expect(screen.getByTestId('card')).toHaveStyle({backgroundColor:'rgb(36,36,36)'});
  expect(screen.getByTestId('label')).toHaveStyle({color:'rgb(246,241,229)'});
  fireEvent.changeText(screen.getByTestId('draft'),'keep this unsaved value');
  screen.rerender(<Fixture scheme="light"/>);
  expect(screen.getByTestId('card')).toHaveStyle({backgroundColor:'rgb(240,238,227)',borderColor:'rgb(210,214,202)'});
  expect(screen.getByTestId('label')).toHaveStyle({color:'rgb(38,58,43)'});
  expect(screen.getByTestId('action')).toHaveStyle({backgroundColor:'rgb(40,107,69)'});
  expect(screen.getByTestId('draft')).toHaveProp('value','keep this unsaved value');
  expect(screen.getByTestId('toggle')).toHaveProp('trackColorForFalse','rgb(229,229,218)');
  expect(screen.getByTestId('toggle')).toHaveProp('trackColorForTrue','rgb(40,107,69)');
  expect(screen.getByTestId('native-field')).toHaveProp('textColor','rgb(38,58,43)');
  expect(screen.getByTestId('native-field')).toHaveProp('selectionColor','rgb(40,107,69)');
  expect(screen.getByTestId('native-field')).toHaveProp('value','keep this unsaved value');
  screen.rerender(<Fixture scheme="dark"/>);
  expect(screen.getByTestId('card')).toHaveStyle({backgroundColor:'rgb(36,36,36)'});
  expect(screen.getByTestId('action')).toHaveStyle({backgroundColor:'rgb(43,82,59)'});
  expect(screen.getByTestId('native-field')).toHaveProp('textColor','rgb(246,241,229)');
  expect(mounted).toHaveBeenCalledTimes(1);
});

test('style precedence, animations, refs and non-Lava native colors retain their behavior',()=>{
  const opacity=new NativeAnimated.Value(.5);
  const ref=React.createRef<React.ComponentRef<typeof View>>();
  const style=[card.saved,{borderColor:colors.primaryText,opacity,transform:[{scale:1.2}]}];
  expect(paletteStyle(style,'light')).toEqual({backgroundColor:'rgb(240,238,227)',borderColor:'rgb(38,58,43)',opacity,transform:[{scale:1.2}]});
  const other=PlatformColor('?attr/textColorPrimary');
  expect(paletteColor(other,'light')).toBe(other);
  expect(paletteColor({resource_paths:['@android:color/white','@color/lava_rn_primarytext']},'dark')).toEqual({resource_paths:['@android:color/white','@color/lava_rn_primarytext']});
  const screen=render(<AndroidPaletteContext.Provider value="light"><View ref={ref} testID="native-ref"/><Animated.View testID="animated" style={{backgroundColor:colors.cardBackground,opacity}}/></AndroidPaletteContext.Provider>);
  expect(screen.getByTestId('animated')).toHaveStyle({backgroundColor:'rgb(240,238,227)'});
  // The forwarding boundary adds no host view around the actual native target.
  expect(screen.getByTestId('native-ref').type).toBe('View');
  expect(typeof ref.current?.measure).toBe('function');
  expect(typeof ref.current?.setNativeProps).toBe('function');
});

test('pressed styles, refresh colors and SVG color props use the complete selected palette',()=>{
  const input={style:({pressed}:{pressed:boolean})=>({backgroundColor:pressed?colors.pressedSurface:colors.cardBackground}),colors:[colors.safeGreen],fill:colors.primaryText,stroke:colors.separator};
  const resolved=paletteProps(input,'light');
  expect((resolved.style as typeof input.style)({pressed:true})).toEqual({backgroundColor:'rgb(227,229,217)'});
  expect(resolved.colors).toEqual(['rgb(36,103,67)']);
  expect(resolved.fill).toBe('rgb(38,58,43)');
  expect(resolved.stroke).toBe('rgb(210,214,202)');
});
