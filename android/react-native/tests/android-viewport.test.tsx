import React from 'react';
import {renderHook} from '@testing-library/react-native';
import {useWindowDimensions} from 'react-native';
import {WindowSizeContext,useViewportDimensions,useWindowLayout} from '../canonical/src/window-layout';

test('the native pane controls both shared responsive metrics and direct viewport consumers',()=>{
 const {result}=renderHook(()=>({native:useWindowDimensions(),pane:useViewportDimensions(),layout:useWindowLayout()}),{
  wrapper:({children})=><WindowSizeContext.Provider value={{width:480,height:800}}>{children}</WindowSizeContext.Provider>,
 });
 expect(result.current.pane).toMatchObject({width:480,height:800,fontScale:result.current.native.fontScale,scale:result.current.native.scale});
 expect(result.current.layout.landscape).toBe(false);expect(result.current.layout.scale).toBe(1);
});
test('fitted native sheets override the enclosing pane and detached roots retain native metrics',()=>{
 const {result}=renderHook(()=>useViewportDimensions(),{wrapper:({children})=><WindowSizeContext.Provider value={{width:900,height:700}}><WindowSizeContext.Provider value={{width:360,height:420}}>{children}</WindowSizeContext.Provider></WindowSizeContext.Provider>});
 expect(result.current.width).toBe(360);expect(result.current.height).toBe(420);
 const detached=renderHook(()=>({native:useWindowDimensions(),pane:useViewportDimensions()}));
 expect(detached.result.current.pane).toEqual(detached.result.current.native);
});
