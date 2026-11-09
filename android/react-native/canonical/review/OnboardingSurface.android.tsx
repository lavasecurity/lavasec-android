import {useContext,useEffect,useLayoutEffect,useRef,useState,type ComponentRef,type PropsWithChildren} from 'react';
import {AppState,View} from 'react-native';
import {NavigationContainer,NavigationIndependentTree,createNavigationContainerRef} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import type {OnboardingSetup} from '../app/contract';
import {useReview} from './ReviewContext';
import {nativeFlowHeader,onboardingHeaderOptions} from './scaffold';
import {useLavaColorScheme} from '../src/appearance';
import {OnboardingFlow} from './OnboardingFlow';
import {OnboardingGeometryContext,useOnboardingMeasurements} from './onboarding-geometry';
import {usePresentationAuthority} from '../app/use-presentation-readiness';
import {AndroidIndependentNavigation} from './independent-navigation.android';

import {AndroidPresentationContext,AndroidPresentationControlContext,useAndroidPresentationReady} from './presentation-owner.android';

type SurfaceRoutes={Runtime:undefined;Onboarding:undefined};
const Stack=createNativeStackNavigator<SurfaceRoutes>();

/** One RN tree measures both setup and its real Guard destination. The native
 * stack owns the full-window modal, safe areas, touch delivery and status bar. */
export function OnboardingSurface({children,enabled=true}:PropsWithChildren<{enabled?:boolean}>){
  const {app,live,onboardingPreview}=useReview();const authoritative=usePresentationAuthority(app);const scheme=useLavaColorScheme();
  const retained=useRef<OnboardingSetup|undefined>(undefined);
  if(live)retained.current=live.onboardingSetup?.mock===!!onboardingPreview?live.onboardingSetup:undefined;
  const setup=enabled?retained.current:undefined;
  const presentation=useContext(AndroidPresentationContext),control=useContext(AndroidPresentationControlContext);
  const currentControl=useRef(control);currentControl.current=control;
  const [viewportReady,setViewportReady]=useState(false);
  useLayoutEffect(()=>{if(!setup)return;currentControl.current?.beginModal(`onboarding:${setup.id}`);return()=>currentControl.current?.restoreMain();},[setup?.id]);
  useAndroidPresentationReady(presentation,!!setup&&presentation?.ownerID===`onboarding:${setup.id}`,viewportReady);
  const [navigation]=useState(()=>createNavigationContainerRef<SurfaceRoutes>());
  const [ready,setReady]=useState(false);
  // An initial native mock visit can precede this root's first snapshot. A
  // never-focused underlying native-stack screen has no dependable destination
  // geometry. Mount its real Runtime first and open setup only after its Guard
  // panel, mascot and action have each delivered native measurements.
  const initialState=useRef({index:0,routes:[{name:'Runtime'}]}).current;
  const panel=useRef<ComponentRef<typeof View>>(null),mascot=useRef<ComponentRef<typeof View>>(null),action=useRef<ComponentRef<typeof View>>(null),source=useRef<ComponentRef<typeof View>>(null),root=useRef<ComponentRef<typeof View>>(null);
  const [size,setSize]=useState({width:0,height:0});
  const {origin,frames,sourceFrame,destinationReady}=useOnboardingMeasurements(setup?.id,{root,source,panel,mascot,action});
  useEffect(()=>{
    if(!ready||!authoritative||AppState.currentState!=='active')return;const current=navigation.getCurrentRoute()?.name;
    if(setup&&destinationReady&&current!=='Onboarding')navigation.navigate('Onboarding');
    else if(!setup&&current==='Onboarding'&&navigation.canGoBack())navigation.goBack();
  },[ready,setup?.id,destinationReady,authoritative,navigation]);
  return <OnboardingGeometryContext.Provider value={{panel,mascot,action,source,root,frames,sourceFrame,origin,size,setup}}>{enabled?<NavigationIndependentTree><NavigationContainer ref={navigation} initialState={initialState} onReady={()=>setReady(true)}>
    <Stack.Navigator screenOptions={{headerShown:false,animation:'none'}}>
      <Stack.Screen name="Runtime">{()=> <AndroidIndependentNavigation><View collapsable={false} testID="onboarding.runtime" style={{flex:1}} pointerEvents={setup?'none':'auto'} accessibilityElementsHidden={!!setup} importantForAccessibility={setup?'no-hide-descendants':'auto'}>{children}</View></AndroidIndependentNavigation>}</Stack.Screen>
      <Stack.Screen name="Onboarding" options={{...nativeFlowHeader(scheme==='dark'),...onboardingHeaderOptions,headerShown:true,presentation:'transparentModal',gestureEnabled:false,statusBarStyle:setup?.page===0||scheme==='dark'?'light':'dark'}}>
        {()=> <SafeAreaProvider><View ref={root} collapsable={false} style={{flex:1}} onLayout={event=>{setSize(event.nativeEvent.layout);setViewportReady(event.nativeEvent.layout.width>0&&event.nativeEvent.layout.height>0);}} testID="onboarding.surface"><OnboardingFlow/></View></SafeAreaProvider>}
      </Stack.Screen>
    </Stack.Navigator>
  </NavigationContainer></NavigationIndependentTree>:children}</OnboardingGeometryContext.Provider>;
}
