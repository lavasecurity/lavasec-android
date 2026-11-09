import React,{forwardRef,useContext} from 'react';
import {useColorScheme} from 'react-native';
import {AndroidPaletteContext} from './palette-context';
import {paletteProps} from './palette';

/** Fabric stores resolved PlatformColor integers on mounted views. Resolve our palette at
 * this Android-only boundary so a scheme change updates props without remounting screens. */
export function withPalette<T>(Component:T):T {
  const Themed=forwardRef<unknown,Record<string,unknown>>((props,ref)=>{
    const confirmed=useContext(AndroidPaletteContext);
    const nativeScheme=useColorScheme();
    const scheme=confirmed??(nativeScheme==='dark'?'dark':'light');
    return React.createElement(Component as React.ComponentType<Record<string,unknown>>,{...paletteProps(props,scheme),ref});
  });
  Themed.displayName=`AndroidPalette(${(Component as {displayName?:string}).displayName??'Control'})`;
  return Themed as unknown as T;
}
