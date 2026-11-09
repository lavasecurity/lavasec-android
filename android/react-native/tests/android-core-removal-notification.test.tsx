import React from 'react';
import {View} from 'react-native';
import {act,renderAsync,waitFor} from '@testing-library/react-native';

declare const require: (name:string)=>any;
jest.mock('react-native-safe-area-context',()=>jest.requireActual('react-native-safe-area-context/jest/mock').default);
jest.mock('@react-navigation/elements',()=>jest.requireActual('../node_modules/@react-navigation/elements/lib/module/index.js'));
jest.mock('../node_modules/@react-navigation/native-stack/lib/module/views/NativeStackView',()=>jest.requireActual('../node_modules/@react-navigation/native-stack/lib/module/views/NativeStackView.native'));
// NavigationContent loads the actual adapter's pinned output. Neighbor modules
// forward the installed contexts so every native-stack consumer shares identity.
jest.mock('../node_modules/@react-navigation/core/lib/module/PreventRemoveProvider',()=>jest.requireActual('./core-removal-notification-fixture.cjs').loadAdapterProvider(jest));
const Native=require('@react-navigation/native');
const {createNativeStackNavigator}=require('@react-navigation/native-stack');
const fixture=require('./core-removal-notification-fixture.cjs');
const Stack=createNativeStackNavigator();
let core:any,nav:any,history:number[]=[];
function Capture({children}:{children:React.ReactNode}) {
 core=Native.usePreventRemoveContext();
 React.useLayoutEffect(()=>{history.push(Object.keys(core.preventedRoutes).length);});
 return children;
}
function Page(){nav=Native.useNavigation();return <View/>;}
const layout=({children}:any)=><Capture>{children}</Capture>;
async function mount(navigatorLayout:any=layout){
 history=[];
 const view=await renderAsync(<Native.NavigationContainer initialState={{index:1,routes:[{name:'A'},{name:'B'}]}}><Stack.Navigator layout={navigatorLayout} screenOptions={{headerShown:false}}><Stack.Screen name="A" component={Page}/><Stack.Screen name="B" component={Page}/></Stack.Navigator></Native.NavigationContainer>);
 const keys=nav.getState().routes.map((route:any)=>route.key);return {view,keys};
}
afterAll(()=>fixture.cleanup());

test('adapter output uses the exact installed navigation contexts and no-op notifications never invoke the state setter',async()=>{
 const {view,keys}=await mount();
 const target=fixture.getFixture();
 expect(require(target.dependency+'/lib/module/PreventRemoveContext.js').PreventRemoveContext).toBe(Native.PreventRemoveContext);
 const before=target.counters.requests;
 act(()=>core.notifyPreventRemove());
 expect(target.counters.requests).toBe(before);
 act(()=>{core.setPreventRemove('owner',keys[1],true);core.notifyPreventRemove();});
 expect(core.preventedRoutes).toEqual({[keys[1]]:{preventRemove:true}});
 expect(target.counters.requests).toBeGreaterThan(before);
 const after=target.counters.requests;
 act(()=>{core.setPreventRemove('owner',keys[1],true);core.notifyPreventRemove();});
 expect(target.counters.requests).toBe(after);
 expect(()=>core.setPreventRemove('invalid','missing-route',true)).toThrow("Couldn't find a route");
 await view.unmountAsync();
});

test('the exact locked unpatched provider control still schedules an unchanged notification',async()=>{
 const {PreventRemoveProvider:Original}=fixture.loadOriginalProvider(jest);
 const {view}=await mount(({children}:any)=><Original><Capture>{children}</Capture></Original>);
 const before=fixture.getFixture().counters.originalRequests;
 act(()=>core.notifyPreventRemove());
 // This is the original dispatch that the fixed-provider test forbids. Merely
 // asserting equal public maps would miss it: React can eagerly bail afterward.
 expect(fixture.getFixture().counters.originalRequests).toBeGreaterThan(before);
 expect(core.preventedRoutes).toEqual({});
 await view.unmountAsync();
});

test('queued true then false before commit never publishes the retired intermediate route',async()=>{
 const {view,keys}=await mount();
 act(()=>{core.setPreventRemove('owner',keys[1],true);core.notifyPreventRemove();core.setPreventRemove('owner',keys[1],false);core.notifyPreventRemove();});
 expect(core.preventedRoutes).toEqual({});expect(history).not.toContain(1);
 await view.unmountAsync();
});

test('two queued notifications read live insertion cleanup rather than publishing captured retired owners',async()=>{
 const {view,keys}=await mount();
 act(()=>{
  core.setPreventRemove('one',keys[0],true);core.notifyPreventRemove();
  core.setPreventRemove('two',keys[1],true);core.notifyPreventRemove();
  // A hidden retiring consumer may run insertion cleanup without a passive notification.
  core.setPreventRemove('one',keys[0],false);core.setPreventRemove('two',keys[1],false);
 });
 expect(core.preventedRoutes).toEqual({});expect(history).not.toContain(2);
 const before=fixture.getFixture().counters.requests;
 act(()=>core.notifyPreventRemove());expect(fixture.getFixture().counters.requests).toBe(before);
 await view.unmountAsync();
});

test('an older concurrent request cannot erase the latest reversal or two independent consumers',async()=>{
 const {view,keys}=await mount();
 await act(async()=>{
  React.startTransition(()=>{core.setPreventRemove('one',keys[1],true);core.notifyPreventRemove();});
  core.setPreventRemove('one',keys[1],false);core.notifyPreventRemove();
 });
 await waitFor(()=>expect(core.preventedRoutes).toEqual({}));
 act(()=>{core.setPreventRemove('one',keys[0],true);core.setPreventRemove('two',keys[1],true);core.notifyPreventRemove();});
 expect(core.preventedRoutes).toEqual({[keys[0]]:{preventRemove:true},[keys[1]]:{preventRemove:true}});
 act(()=>{core.setPreventRemove('one',keys[1],true);core.notifyPreventRemove();core.setPreventRemove('one',keys[0],true);core.setPreventRemove('two',keys[1],false);core.notifyPreventRemove();});
 expect(core.preventedRoutes).toEqual({[keys[0]]:{preventRemove:true}});
 await view.unmountAsync();
});

test('a genuinely suspended older transition cannot acknowledge or republish over a newer committed owner',async()=>{
 let current:any,keys:[string,string]=['',''];let suspended=0,hold=true;
 let release!:()=>void;const barrier=new Promise<void>(resolve=>{release=resolve;});
 const committed:string[][]=[];
 function Observe({children}:any){
  const value=Native.usePreventRemoveContext();
  // Only the older B-owner publication suspends. A later urgent A-owner may
  // commit while that transition is still pending.
  if(hold&&keys[1]&&value.preventedRoutes[keys[1]]){suspended++;throw barrier;}
  React.useLayoutEffect(()=>{current=value;committed.push(Object.keys(value.preventedRoutes));});
  return children;
 }
 const view=await renderAsync(<Native.NavigationContainer initialState={{index:1,routes:[{name:'A'},{name:'B'}]}}><React.Suspense fallback={<View/>}><Stack.Navigator layout={({children}:any)=><Observe>{children}</Observe>} screenOptions={{headerShown:false}}><Stack.Screen name="A" component={Page}/><Stack.Screen name="B" component={Page}/></Stack.Navigator></React.Suspense></Native.NavigationContainer>,{concurrentRoot:true});
 keys=nav.getState().routes.map((route:any)=>route.key);
 await act(async()=>React.startTransition(()=>{current.setPreventRemove('older',keys[1],true);current.notifyPreventRemove();}));
 expect(suspended).toBeGreaterThan(0);expect(committed.every(map=>map.length===0)).toBe(true);
 await act(async()=>{current.setPreventRemove('older',keys[1],false);current.setPreventRemove('newer',keys[0],true);current.notifyPreventRemove();});
 expect(current.preventedRoutes).toEqual({[keys[0]]:{preventRemove:true}});
 hold=false;await act(async()=>release());
 expect(current.preventedRoutes).toEqual({[keys[0]]:{preventRemove:true}});
 expect(committed.every(map=>!map.includes(keys[1]))).toBe(true);
 const before=fixture.getFixture().counters.requests;
 act(()=>current.notifyPreventRemove());expect(fixture.getFixture().counters.requests).toBe(before);
 await view.unmountAsync();
});

test('real nested native-stack Back retains child protection then forwards the exact admitted parent action',async()=>{
 const Outer=createNativeStackNavigator(),Inner=createNativeStackNavigator();
 let rootNav:any,leafNav:any,outer:any,parentKey='';let allow=false;const actions:any[]=[];
 function Observe({children}:any){outer=Native.usePreventRemoveContext();return children;}
 function First(){return <View/>;}
 function Leaf(){leafNav=Native.useNavigation();Native.usePreventRemove(true,({data}:any)=>{actions.push(data.action);if(allow)leafNav.dispatch(data.action);});return <View/>;}
 function Nested(){rootNav=Native.useNavigation();parentKey=Native.useRoute().key;return <Inner.Navigator screenOptions={{headerShown:false}}><Inner.Screen name="First" component={First}/><Inner.Screen name="Leaf" component={Leaf}/></Inner.Navigator>;}
 const view=await renderAsync(<Native.NavigationContainer initialState={{index:1,routes:[{name:'Home'},{name:'Nested',state:{index:1,routes:[{name:'First'},{name:'Leaf'}]}}]}}><Outer.Navigator layout={({children}:any)=><Observe>{children}</Observe>} screenOptions={{headerShown:false}}><Outer.Screen name="Home" component={Page}/><Outer.Screen name="Nested" component={Nested}/></Outer.Navigator></Native.NavigationContainer>);
 await waitFor(()=>expect(outer.preventedRoutes).toEqual({[parentKey]:{preventRemove:true}}));
 const first={type:'GO_BACK',target:rootNav.getState().key};
 await act(async()=>rootNav.dispatch(first));expect(actions).toHaveLength(1);expect(rootNav.getState().routes).toHaveLength(2);
 allow=true;const second={type:'GO_BACK',target:rootNav.getState().key};
 await act(async()=>rootNav.dispatch(second));expect(actions).toHaveLength(2);expect(actions[1].type).toBe(second.type);expect(actions[1].target).toBe(second.target);expect(rootNav.getState().routes).toHaveLength(1);
 await waitFor(()=>expect(outer.preventedRoutes).toEqual({}));await view.unmountAsync();
});
