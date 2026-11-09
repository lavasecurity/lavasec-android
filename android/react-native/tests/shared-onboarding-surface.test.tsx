import {useContext} from 'react';
import {act,fireEvent,render,screen} from '@testing-library/react-native';
import {AppState,Dimensions,View,type AppStateStatus} from 'react-native';
import type {OnboardingSetup} from '../canonical/app/contract';
import {OnboardingSurface} from '../canonical/review/OnboardingSurface.android';
import {OnboardingGeometryContext,useOnboardingDestination} from '../canonical/review/onboarding-geometry';
import {AndroidPresentationControlContext} from '../canonical/review/presentation-owner.android';

let mockSetup:OnboardingSetup|undefined,mockPreview=true;
let mockRoute='Runtime',mockRefresh=()=>{};
const mockNavigate=jest.fn((name:string)=>{mockRoute=name;mockRefresh();});
const mockBack=jest.fn(()=>{mockRoute='Runtime';mockRefresh();});
const mockInitialStates:Array<{index:number;routes:Array<{name:string}>}>=[];
let mockGeometry:ReturnType<typeof useOnboardingDestination>;
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({live:{onboardingSetup:mockSetup},onboardingPreview:mockPreview}),useOptionalReview:()=>undefined}));
jest.mock('../canonical/src/appearance',()=>({useLavaColorScheme:()=> 'light'}));
jest.mock('../canonical/review/scaffold',()=>({nativeFlowHeader:()=>({}),onboardingHeaderOptions:{}}));
jest.mock('../canonical/review/OnboardingFlow',()=>({OnboardingFlow:()=>{
  const React=require('react'),{View}=require('react-native'),{OnboardingGeometryContext}=require('../canonical/review/onboarding-geometry');
  const geometry=React.useContext(OnboardingGeometryContext);mockGeometry=geometry;
  return React.createElement(View,{ref:geometry.source,testID:'fixture.setup.source'});
}}));
jest.mock('react-native-safe-area-context',()=>({SafeAreaProvider:({children}:{children:React.ReactNode})=>children}));
jest.mock('@react-navigation/native',()=>{
  const React=require('react'),{View}=require('react-native');
  return {PreventRemoveContext:jest.requireActual('@react-navigation/native').PreventRemoveContext,useIsFocused:()=>true,
    createNavigationContainerRef:()=>({getCurrentRoute:()=>({name:mockRoute}),navigate:mockNavigate,canGoBack:()=>mockRoute==='Onboarding',goBack:mockBack}),
    NavigationIndependentTree:({children}:{children:React.ReactNode})=>children,
    NavigationContainer:({children,initialState,onReady}:{children:React.ReactNode;initialState:{index:number;routes:Array<{name:string}>};onReady:()=>void})=>{
      React.useLayoutEffect(()=>{mockInitialStates.push(initialState);onReady();},[]);return React.createElement(View,null,children);
    },
  };
});
jest.mock('@react-navigation/native-stack',()=>({createNativeStackNavigator:()=>({
  Navigator:({children}:{children:React.ReactNode})=>{const React=require('react'),{View}=require('react-native');const [,setRevision]=React.useState(0);mockRefresh=()=>setRevision((value:number)=>value+1);return React.createElement(View,null,React.Children.map(children,(child:React.ReactElement)=>React.cloneElement(child,{activeRoute:mockRoute})));},
  Screen:({name,activeRoute,children}:{name:string;activeRoute:string;children:()=>React.ReactNode})=>name==='Runtime'||activeRoute==='Onboarding'?children():null,
})}));

type Measurement=[number,number,number,number];
const baseline:Record<string,Measurement>={
  'fixture.guard.panel':[16,360,368,330], 'fixture.guard.mascot':[152,388,96,96], 'fixture.guard.action':[36,600,328,64],
  'onboarding.surface':[0,60,400,800], 'fixture.setup.source':[0,114,400,128],
};
let measurements:Record<string,Measurement|undefined>,callbacks:Array<(...value:Measurement)=>void>,lifecycle:Set<(state:AppStateStatus)=>void>;
function MeasuredGuard(){
  const geometry=useContext(OnboardingGeometryContext);mockGeometry=geometry;
  return <View><View ref={geometry.panel} testID="fixture.guard.panel"/><View ref={geometry.mascot} testID="fixture.guard.mascot"/><View ref={geometry.action} testID="fixture.guard.action"/></View>;
}
const owner=()=>({beginModal:jest.fn(()=>({serial:2,routeKey:'onboarding',ownerID:`onboarding:${mockSetup?.id}`,surface:'none' as const,accepted:false})),restoreMain:jest.fn()});
const tree=(control:ReturnType<typeof owner>,host='initial-host')=><AndroidPresentationControlContext.Provider value={control}><OnboardingSurface key={host}><MeasuredGuard/></OnboardingSurface></AndroidPresentationControlContext.Provider>;
const node=(element:{props:{testID?:string}})=>({measureInWindow:(callback:(...value:Measurement)=>void)=>{
  const measurement=measurements[element.props.testID??''];if(measurement)callback(...measurement);else callbacks.push(callback);
}});
const tick=(milliseconds=160)=>act(async()=>{jest.advanceTimersByTime(milliseconds);});
const setup=(mock:boolean,id='native-visit'):OnboardingSetup=>({id,mock,page:0,history:[],visited:[0],level:'balanced',fallback:true,dnsProfile:false,supportsDNSProfile:false,vpnInstalled:false,notifications:false,busy:'',error:'',phase:'setup'});
beforeEach(()=>{
  jest.useFakeTimers();jest.clearAllMocks();mockRoute='Runtime';mockRefresh=()=>{};mockInitialStates.length=0;
  mockPreview=true;mockSetup=setup(true);measurements={...baseline};callbacks=[];lifecycle=new Set();AppState.currentState='active';
  jest.spyOn(AppState,'addEventListener').mockImplementation((_type,callback)=>{lifecycle.add(callback);return {remove:()=>lifecycle.delete(callback)};});
  jest.spyOn(Dimensions,'addEventListener').mockImplementation(()=>({remove:jest.fn()}));
  jest.spyOn(View.prototype as {measureInWindow:(callback:(...value:Measurement)=>void)=>void},'measureInWindow').mockImplementation(function(this:{props:{testID?:string}},callback){node(this).measureInWindow(callback);});
});
afterEach(()=>{jest.useRealTimers();jest.restoreAllMocks();});

test.each([false,true])('initial shared setup mounts and measures Runtime before opening the exact native modal (mock=%s)',async mock=>{
  mockPreview=mock;mockSetup=setup(mock);measurements['fixture.guard.panel']=undefined;
  const control=owner(),view=render(tree(control));await act(async()=>{});
  expect(mockInitialStates).toEqual([{index:0,routes:[{name:'Runtime'}]}]);
  expect(mockNavigate).not.toHaveBeenCalled();expect(screen.queryByTestId('onboarding.surface')).toBeNull();
  expect(control.beginModal).toHaveBeenCalledWith('onboarding:native-visit');
  const runtime=screen.getByTestId('onboarding.runtime',{includeHiddenElements:true});
  expect(runtime.props.collapsable).toBe(false);expect(runtime.props.pointerEvents).toBe('none');
  measurements['fixture.guard.panel']=baseline['fixture.guard.panel'];await tick(200);await tick();
  expect(mockNavigate).toHaveBeenCalledTimes(1);expect(mockNavigate).toHaveBeenCalledWith('Onboarding');
  const viewport=screen.getByTestId('onboarding.surface');
  fireEvent(viewport,'layout',{nativeEvent:{layout:{width:400,height:800,x:0,y:0}}});await tick();
  expect(mockGeometry.setup?.id).toBe('native-visit');
  expect(mockGeometry.frames?.mascot).toEqual({x:152,y:328,width:96,height:96});
  expect(mockGeometry.sourceFrame).toEqual({x:0,y:54,width:400,height:128});
  mockSetup=undefined;view.rerender(tree(control));await act(async()=>{});
  expect(screen.getByTestId('onboarding.runtime').props.collapsable).toBe(false);
  expect(screen.getByTestId('onboarding.runtime')).toBe(runtime);
  expect(screen.getByTestId('onboarding.runtime').props.pointerEvents).toBe('auto');
  expect(control.restoreMain).toHaveBeenCalledTimes(1);view.unmount();
});

test('retired host measurement callbacks cannot open the successor preview',async()=>{
  measurements['fixture.guard.panel']=undefined;
  const control=owner(),view=render(tree(control,'old-host'));await act(async()=>{});
  const old=[...callbacks];expect(old.length).toBeGreaterThan(0);
  mockSetup=setup(true,'successor-visit');callbacks=[];view.rerender(tree(control,'successor-host'));await act(async()=>{});
  await act(async()=>{for(const callback of old)callback(...baseline['fixture.guard.panel']!);});
  expect(mockNavigate).not.toHaveBeenCalled();expect(mockGeometry.setup?.id).toBe('successor-visit');expect(mockGeometry.frames).toBeUndefined();
  measurements['fixture.guard.panel']=baseline['fixture.guard.panel'];await tick(200);await tick();
  expect(mockNavigate).toHaveBeenCalledTimes(1);expect(control.beginModal).toHaveBeenLastCalledWith('onboarding:successor-visit');view.unmount();
});

test('a backgrounded or dismissed initial preview cannot navigate from a late preflight',async()=>{
  measurements['fixture.guard.panel']=undefined;
  const control=owner(),view=render(tree(control));await act(async()=>{});
  const old=[...callbacks];
  act(()=>{AppState.currentState='background';for(const callback of lifecycle)callback('background');});
  await act(async()=>{for(const callback of old)callback(...baseline['fixture.guard.panel']!);});
  expect(mockNavigate).not.toHaveBeenCalled();
  measurements['fixture.guard.panel']=baseline['fixture.guard.panel'];
  act(()=>{AppState.currentState='active';for(const callback of lifecycle)callback('active');});await act(async()=>{});
  expect(mockNavigate).toHaveBeenCalledTimes(1);
  mockSetup=undefined;view.rerender(tree(control));await act(async()=>{});
  expect(mockBack).toHaveBeenCalledTimes(1);expect(control.restoreMain).toHaveBeenCalledTimes(1);view.unmount();
});
