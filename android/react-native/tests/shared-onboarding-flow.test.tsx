import {act,fireEvent,render,screen,within} from '@testing-library/react-native';
import {Animated,AppState,StyleSheet,View} from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';
import type {AppCommand,AppSnapshot,OnboardingSetup} from '../canonical/app/contract';
import {OnboardingFlow,OnboardingPages} from '../canonical/review/OnboardingFlow';
import {OnboardingGeometryContext,type Anchor} from '../canonical/review/onboarding-geometry';
import {onboardingPageMotion} from '../canonical/review/onboarding-motion';
import {OnboardingCurtain,OnboardingStep,onboardingPageTitle} from '../canonical/review/onboarding-scaffold';
import {useToolbar} from '../canonical/review/scaffold';
let mockReduced=false,mockCrossFade=false;
let mockLive:AppSnapshot|undefined,mockApp:import('../canonical/app/store').AppStore|undefined;

jest.mock('@react-navigation/elements',()=>({HeaderHeightContext:require('react').createContext(0),useHeaderHeight:()=>0}));
jest.mock('@react-navigation/native',()=>({useNavigation:()=>({}),useIsFocused:()=>true,usePreventRemove:jest.fn()}));
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:jest.fn(()=>({top:0,bottom:21,left:59,right:59}))}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({look:'lava',app:mockApp,live:mockLive}),useOptionalReview:()=>mockApp?{app:mockApp}:undefined,useRouteBodyConcealed:()=>false}));
jest.mock('../canonical/review/navigation-scaffold',()=>({...jest.requireActual('../canonical/review/navigation-scaffold'),useReducedMotionPreference:()=>mockReduced,useCrossFadePreference:()=>mockCrossFade}));
jest.mock('../canonical/review/scaffold',()=>({...jest.requireActual('../canonical/review/scaffold'),useToolbar:jest.fn()}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
jest.mock('../canonical/src/OnboardingDrawing',()=>({OnboardingBackground:require('react-native').View,OnboardingLavaDrawing:require('react-native').View}));

const setup=(page:number):OnboardingSetup=>({id:'visit',mock:true,page,history:page?[0,1]:[],visited:[0,1,2],level:'balanced',fallback:true,dnsProfile:false,supportsDNSProfile:true,vpnInstalled:false,notifications:false,busy:'',error:'',phase:'setup'});
type Motion={value:unknown;config:{duration?:number;delay?:number;toValue:unknown;useNativeDriver?:boolean};finish?: (result:{finished:boolean})=>void;stop:jest.Mock};
let motions:Motion[];
beforeEach(()=>{
  jest.useFakeTimers();motions=[];mockReduced=false;mockCrossFade=false;mockApp=undefined;mockLive=undefined;AppState.currentState='active';jest.mocked(useToolbar).mockClear();jest.mocked(SafeArea.useSafeAreaInsets).mockReturnValue({top:0,bottom:21,left:59,right:59});
  jest.spyOn(Animated,'timing').mockImplementation((_value,config)=>{
    const motion:Motion={value:_value,config,stop:jest.fn()};motions.push(motion);
    return {start:callback=>{motion.finish=callback;},stop:motion.stop,reset:jest.fn()};
  });
});
afterEach(()=>{jest.useRealTimers();jest.restoreAllMocks();});

const flow=(page:number,width=874,height=402)=>{
  const anchor:Anchor={current:null};
  return <OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width,height},sourceFrame:{x:59,y:48,width:128,height:128},setup:setup(page)}}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
};
test.each([[false,false,320],[true,false,200],[false,true,200]])('Features to Welcome shares its native return clock and prepares paused waves (reduced=%s, crossFade=%s)',(reduced,crossFade,duration)=>{
  mockReduced=reduced as boolean;mockCrossFade=crossFade as boolean;
  render(flow(1));motions=[];screen.rerender(flow(0));
  const progress=screen.UNSAFE_getByType(OnboardingPages).props.welcomeReturn;
  const clock=motions.find(m=>m.value===progress)!;
  expect(clock.config).toMatchObject({duration,toValue:1,useNativeDriver:true});expect(clock.config.delay).toBeUndefined();
  expect(motions.filter(m=>m.value===progress)).toHaveLength(1);
  // The retained footer animates two dot widths on JS; the shared welcome
  // clock and its background/mascot motion remain on the native driver.
  expect(motions.filter(m=>!m.config.useNativeDriver)).toHaveLength(2);
  const incoming=screen.UNSAFE_getAllByType(Animated.View).find(node=>{const style=StyleSheet.flatten(node.props.style);return style?.opacity===progress;});
  expect(incoming).toBeDefined();expect(StyleSheet.flatten(incoming!.props.style).marginLeft).toBeUndefined();
  expect(StyleSheet.flatten(screen.getByTestId('onboarding.page.outgoing',{includeHiddenElements:true}).props.style).marginLeft).toBeUndefined();
  const floor=()=>screen.UNSAFE_getAllByType(View).find(node=>node.props.floor)!;
  expect(floor().props.active).toBe(false);
  act(()=>clock.finish?.({finished:true}));
  expect(floor().props.active).toBe(true);expect(screen.queryByTestId('onboarding.page.outgoing',{includeHiddenElements:true})).toBeNull();
  expect(within(screen.getByTestId('onboarding.footer')).getByTestId('onboarding.steps')).toBeTruthy();
  expect(within(screen.getByTestId('onboarding.source.column',{includeHiddenElements:true})).queryByTestId('onboarding.steps',{includeHiddenElements:true})).toBeNull();
});
test('a cancelled Welcome return cannot start waves or retire the next page transition',()=>{
  render(flow(1));screen.rerender(flow(0));const oldProgress=screen.UNSAFE_getByType(OnboardingPages).props.welcomeReturn;
  const old=motions.find(m=>m.value===oldProgress)!;
  screen.rerender(flow(1));expect(old.stop).toHaveBeenCalled();
  act(()=>old.finish?.({finished:true}));expect(screen.UNSAFE_getByType(OnboardingPages).props.welcomeReturn).toBeUndefined();
  expect(screen.getByTestId('onboarding.page.outgoing',{includeHiddenElements:true})).toBeTruthy();
  screen.rerender(flow(0));const floor=screen.UNSAFE_getAllByType(View).find(node=>node.props.floor)!;
  act(()=>old.finish?.({finished:true}));expect(floor.props.active).toBe(false);
  expect(screen.getByTestId('onboarding.page.outgoing',{includeHiddenElements:true})).toBeTruthy();
});
test('Welcome Back waits for a usable committed canvas before starting the shared clock',()=>{
  render(flow(1,0,0));motions=[];screen.rerender(flow(0,0,0));
  const progress=screen.UNSAFE_getByType(OnboardingPages).props.welcomeReturn;
  expect(motions.some(m=>m.value===progress)).toBe(false);
  screen.rerender(flow(0));
  expect(motions.filter(m=>m.value===progress)).toHaveLength(1);
  expect(motions.find(m=>m.value===progress)!.config).toMatchObject({duration:320,useNativeDriver:true});
});
test('portrait Features to Welcome retains the original body layout while fading its incoming copy',()=>{
  render(flow(1,402,874));screen.rerender(flow(0,402,874));
  expect(screen.getByTestId('onboarding.composition')).toHaveStyle({flexDirection:'column',alignItems:'center'});
  expect(screen.getByRole('header',{name:onboardingPageTitle(0)})).toBeTruthy();
  expect(jest.mocked(useToolbar).mock.calls.at(-1)?.[0].title).toBe('');
});

test.each([[false,false,320],[true,false,200],[false,true,200]])('Back to features uses ordinary interruptible motion (reduced=%s, crossFade=%s)',(reduced,crossFade,duration)=>{
  const content=(page:number)=><OnboardingPages setup={setup(page)} command={jest.fn(async()=>true)} height={300} reduced={reduced as boolean} crossFade={crossFade as boolean}/>;
  render(content(2));motions=[];screen.rerender(content(1));
  expect(motions).toHaveLength(2);
  expect(motions.every(m=>m.config.duration===duration)).toBe(true);
  expect(motions.some(m=>m.config.delay===150)).toBe(false);
  expect(screen.getByRole('header',{name:onboardingPageTitle(1)})).toBeTruthy();
});
test('the first Welcome departure retains its authored entrance and a cancelled exit cannot retire the next outgoing page',()=>{
  const content=(page:number)=><OnboardingPages setup={setup(page)} command={jest.fn(async()=>true)} height={300} reduced={false} crossFade={false}/>;
  render(content(0));screen.rerender(content(1));
  expect(motions.some(m=>m.config.duration===1250&&m.config.delay===150)).toBe(true);
  const oldExit=motions.find(m=>m.config.duration===1100)!;
  screen.rerender(content(2));const outgoing=screen.getByTestId('onboarding.page.outgoing',{includeHiddenElements:true});
  expect(outgoing.props).toMatchObject({pointerEvents:'none',accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});expect(oldExit.stop).toHaveBeenCalled();
  act(()=>oldExit.finish?.({finished:true}));
  expect(screen.getByTestId('onboarding.page.outgoing',{includeHiddenElements:true})).toBe(outgoing);
  expect(within(outgoing).getByText(onboardingPageTitle(1),{includeHiddenElements:true}).props.accessibilityRole).toBe('header');
  act(()=>motions.at(-1)?.finish?.({finished:true}));
  expect(screen.queryByTestId('onboarding.page.outgoing',{includeHiddenElements:true})).toBeNull();
});
test('returning to Welcome brings its curtain back with ordinary timing and ignores a retired departure callback',()=>{
  render(<OnboardingCurtain welcome width={874} height={402} reduced={false}/>);
  screen.rerender(<OnboardingCurtain welcome={false} width={874} height={402} reduced={false}/>);const oldExit=motions.at(-1)!;
  screen.rerender(<OnboardingCurtain welcome width={874} height={402} reduced={false}/>);
  expect(motions.at(-1)?.config).toMatchObject({duration:320,toValue:0});
  act(()=>oldExit.finish?.({finished:true}));expect(screen.getByTestId('onboarding.floor',{includeHiddenElements:true})).toBeTruthy();
  expect(onboardingPageMotion(1,0,false,false).entryDuration).toBe(320);
});
test.each([0,1,2,4])('page %s keeps its single title in the centered body across rotation',page=>{
  const anchor:Anchor={current:null};const value=(width:number,height:number)=>({panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width,height},setup:setup(page)});
  const content=(width:number,height:number)=><OnboardingGeometryContext.Provider value={value(width,height)}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
  render(content(874,402));
  expect(jest.mocked(useToolbar).mock.calls.at(-1)?.[0]).toMatchObject({title:'',headerBackVisible:false});
  expect(screen.getByRole('header',{name:onboardingPageTitle(page)})).toBeTruthy();
  expect(within(screen.getByTestId('onboarding.footer')).getByTestId('onboarding.steps')).toBeTruthy();
  screen.rerender(content(402,874));
  expect(jest.mocked(useToolbar).mock.calls.at(-1)?.[0].title).toBe('');
  expect(screen.getByRole('header',{name:onboardingPageTitle(page)})).toBeTruthy();
  expect(within(screen.getByTestId('onboarding.footer')).getByTestId('onboarding.steps')).toBeTruthy();
});
test.each([[700,600,140.8],[1024,768,158.72],[1080,810,163.2],[1366,1024,179.2],[874,500,130.13333333333333]])('landscape %s×%s aligns its source in the scene and retains one progress group through Welcome return',(width,height,sourceSize)=>{
  render(flow(1,width,height));
  expect(screen.getByTestId('onboarding.composition')).toHaveStyle({flexDirection:'row',alignItems:'center'});
  expect(screen.getByTestId('onboarding.source.column')).not.toHaveStyle({position:'absolute'});
  expect(StyleSheet.flatten(screen.getByTestId('onboarding.source.column').props.style).width).toBeCloseTo(sourceSize);
  expect(within(screen.getByTestId('onboarding.footer')).getByTestId('onboarding.steps')).toBeTruthy();
  expect(screen.getAllByTestId('onboarding.steps')).toHaveLength(1);
  screen.rerender(flow(0,width,height));
  expect(screen.getAllByTestId('onboarding.steps')).toHaveLength(1);
  expect(within(screen.getByTestId('onboarding.source.column',{includeHiddenElements:true})).queryByTestId('onboarding.steps')).toBeNull();
});
test('tablet onboarding mascot uses the same baseline as its Guard destination',()=>{
  render(flow(2,1032,1376));
  const drawing=screen.UNSAFE_getAllByType(View).find(node=>node.props.size&&node.props.mood)!;
  expect(drawing.props.size).toBeCloseTo(134.4);
  expect(StyleSheet.flatten(screen.getByTestId('onboarding.source').props.style).height).toBeCloseTo(179.2);
});
test('a native page projection that precedes its navigation acknowledgement keeps the actual final Next control disabled until that acknowledgement',async()=>{
  let acknowledge!:(value:unknown)=>void;
  const delayed=new Promise(resolve=>{acknowledge=resolve;});
  const command=jest.fn(async(action:AppCommand)=>action.type==='onboarding.navigate'&&action.page===4?delayed:true);
  mockApp={command,getSnapshot:()=>({snapshot:mockLive}),getPresentationAuthority:()=>true,getPresentationHydration:()=>({epoch:1,required:false}),subscribe:()=>()=>{}} as unknown as import('../canonical/app/store').AppStore;
  const anchor:Anchor={current:null};
  const content=(page:number)=>{
    const nativeSetup={...setup(page),mock:false,vpnInstalled:true,supportsDNSProfile:false};
    mockLive={onboardingSetup:nativeSetup,security:{readRevision:1}} as AppSnapshot;
    return <OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},sourceFrame:{x:24,y:48,width:128,height:128},setup:nativeSetup}}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
  };
  const view=render(content(3));await act(async()=>{});
  fireEvent.press(screen.getByRole('button',{name:'Next step'}));
  expect(command).toHaveBeenCalledWith({type:'onboarding.navigate',id:'visit',page:4,revisit:false});
  // Android publishes the native page before its command's final snapshot read
  // resolves. The same visible control must reflect that outstanding receipt.
  view.rerender(content(4));
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState).toMatchObject({disabled:true,busy:true});
  fireEvent.press(screen.getByRole('button',{name:'Next step'}));
  expect(command.mock.calls.filter(([action])=>action.type==='onboarding.navigate')).toHaveLength(1);
  await act(async()=>{acknowledge(true);await delayed;});
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState.disabled).toBe(false);
  fireEvent.press(screen.getByRole('button',{name:'Next step'}));await act(async()=>{});
  expect(command).toHaveBeenCalledWith({type:'onboarding.navigate',id:'visit',page:5,revisit:false});
  expect(command.mock.calls.filter(([action])=>action.type==='onboarding.navigate')).toHaveLength(2);
  expect(command.mock.calls.some(([action])=>action.type==='onboarding.complete')).toBe(false);view.unmount();
});


test('a retained old navigation callback and late acknowledgement cannot borrow or disable a successor onboarding visit',async()=>{
  let acknowledgeOld!:(value:unknown)=>void,acknowledgeNew!:(value:unknown)=>void;
  const old=new Promise(resolve=>{acknowledgeOld=resolve;}),next=new Promise(resolve=>{acknowledgeNew=resolve;});
  const command=jest.fn(async(action:AppCommand)=>action.type==='onboarding.navigate'?(action.id==='old-visit'?old:next):true);
  mockApp={command,getSnapshot:()=>({snapshot:mockLive}),getPresentationAuthority:()=>true,getPresentationHydration:()=>({epoch:1,required:false}),subscribe:()=>()=>{}} as unknown as import('../canonical/app/store').AppStore;
  const anchor:Anchor={current:null};
  const content=(id:string,page:number)=>{
    const nativeSetup={...setup(page),id,mock:true,vpnInstalled:true,supportsDNSProfile:false};
    mockLive={onboardingSetup:nativeSetup,security:{readRevision:1}} as AppSnapshot;
    return <OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},sourceFrame:{x:24,y:48,width:128,height:128},setup:nativeSetup}}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
  };
  const view=render(content('old-visit',3));await act(async()=>{});
  const oldButton=screen.getByRole('button',{name:'Next step'});let handler=oldButton;
  while(typeof handler.props.onPress!=='function'&&handler.parent)handler=handler.parent;
  const oldCallback=handler.props.onPress;expect(typeof oldCallback).toBe('function');fireEvent.press(oldButton);
  view.rerender(content('new-visit',3));await act(async()=>{});
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState.disabled).toBe(false);
  act(()=>oldCallback());expect(command.mock.calls.filter(([action])=>action.type==='onboarding.navigate')).toHaveLength(1);
  fireEvent.press(screen.getByRole('button',{name:'Next step'}));view.rerender(content('new-visit',4));
  expect(command).toHaveBeenCalledWith({type:'onboarding.navigate',id:'new-visit',page:4,revisit:false});
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState).toMatchObject({disabled:true,busy:true});
  await act(async()=>{acknowledgeOld(true);await old;});
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState.disabled).toBe(true);
  await act(async()=>{acknowledgeNew(true);await next;});
  expect(screen.getByRole('button',{name:'Next step'}).props.accessibilityState.disabled).toBe(false);
  expect(command.mock.calls.some(([action])=>action.type==='onboarding.complete'||action.type==='onboarding.vpn'||action.type==='onboarding.notifications')).toBe(false);view.unmount();
});


function admissionFixture(initialPage:number,phase:OnboardingSetup['phase']='setup'){
 let authority=true,hydrating=false;const listeners=new Set<()=>void>();
 const command=jest.fn(async(_action:AppCommand)=>true);
 mockApp={command,getSnapshot:()=>({snapshot:mockLive}),getPresentationAuthority:()=>authority,getPresentationHydration:()=>({epoch:1,required:hydrating}),
  subscribe:(listener:()=>void)=>{listeners.add(listener);return ()=>listeners.delete(listener);}} as unknown as import('../canonical/app/store').AppStore;
 const anchor:Anchor={current:null};let owned={...setup(initialPage),phase,vpnInstalled:true};
 const content=(id=owned.id,page=owned.page)=>{
  owned={...owned,id,page};mockLive={onboardingSetup:owned,security:{readRevision:1}} as AppSnapshot;
  return <OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},sourceFrame:{x:24,y:48,width:128,height:128},frames:{panel:{x:24,y:250,width:354,height:150},mascot:{x:137,y:80,width:128,height:128},action:{x:24,y:650,width:354,height:64}},setup:owned}}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
 };
 const notify=()=>act(()=>{for(const listener of listeners)listener();});
 return {command,content,notify,setAuthority:(value:boolean)=>{authority=value;notify();},setHydrating:(value:boolean)=>{hydrating=value;notify();},retire:()=>{mockLive={...mockLive!,onboardingSetup:undefined};notify();}};
}

test.each(['beforeStart','afterStart'] as const)('mock Close silently retires a typed detached %s result',async phase=>{
 const {AppCommandDetachedError}=require('../canonical/app/store') as typeof import('../canonical/app/store');
 const {Alert}=require('../canonical/app/presentation') as typeof import('../canonical/app/presentation');
 const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
 const fixture=admissionFixture(1);const view=render(fixture.content());await act(async()=>{});
 fixture.command.mockRejectedValueOnce(new AppCommandDetachedError(phase));
 const close=jest.mocked(useToolbar).mock.calls.at(-1)![0].unstable_headerRightItems!({canGoBack:true})[0];
 if(!close||!('onPress' in close))throw new Error('Expected the mock Close toolbar action');
 await act(async()=>{close.onPress?.();});
 expect(fixture.command.mock.calls.filter(([action])=>action.type==='onboarding.dismiss')).toEqual([[{type:'onboarding.dismiss',id:'visit'}]]);
 expect(alert).not.toHaveBeenCalled();view.unmount();
});

test('Open Guard reflects authority and hydration admission without replaying a refused tap',async()=>{
 const fixture=admissionFixture(5,'ready');const view=render(fixture.content());
 await act(async()=>{jest.advanceTimersByTime(2000);});
 const open=()=>screen.getByRole('button',{name:'Open Guard'});
 expect(screen.getByTestId('onboarding.mascot').props.accessible).toBe(false);
 expect(screen.getByTestId('onboarding.ready').props.accessible).toBe(true);
 expect(open()).toBeEnabled();
 fixture.setAuthority(false);expect(open()).toBeDisabled();fireEvent.press(open());
 expect(fixture.command.mock.calls.filter(([action])=>action.type==='onboarding.open')).toHaveLength(0);
 fixture.setHydrating(true);fixture.setAuthority(true);expect(open()).toBeDisabled();
 fixture.setHydrating(false);expect(open()).toBeEnabled();
 await act(async()=>{});expect(fixture.command.mock.calls.filter(([action])=>action.type==='onboarding.open')).toHaveLength(0);
 fireEvent.press(open());await act(async()=>{});
 expect(fixture.command.mock.calls.filter(([action])=>action.type==='onboarding.open')).toEqual([[{type:'onboarding.open',id:'visit'}]]);
 view.unmount();
});

test('all current setup choices and toolbar controls follow admission subscription and retired-owner refusal',async()=>{
 const fixture=admissionFixture(4);const view=render(fixture.content());await act(async()=>{});
 const next=()=>screen.getByRole('button',{name:'Next step'}),choice=()=>screen.getByTestId('onboarding.dns-fallback');
 expect(next()).toBeEnabled();expect(choice()).toBeEnabled();
 let handler=choice();while(typeof handler.props.onPress!=='function'&&handler.parent)handler=handler.parent;const oldChoice=handler.props.onPress;
 fixture.setHydrating(true);expect(next()).toBeDisabled();expect(choice()).toBeDisabled();
 const toolbar=jest.mocked(useToolbar).mock.calls.at(-1)![0];
 expect(toolbar.unstable_headerLeftItems?.({canGoBack:true})[0]).toMatchObject({disabled:true});
 expect(toolbar.unstable_headerRightItems?.({canGoBack:true})[0]).toMatchObject({disabled:true});
 act(()=>oldChoice());expect(fixture.command.mock.calls.some(([action])=>action.type==='onboarding.choice')).toBe(false);
 fixture.setHydrating(false);expect(next()).toBeEnabled();expect(choice()).toBeEnabled();
 fixture.retire();expect(next()).toBeDisabled();expect(choice()).toBeDisabled();
 act(()=>oldChoice());expect(fixture.command.mock.calls.some(([action])=>action.type==='onboarding.choice')).toBe(false);
 view.rerender(fixture.content('successor'));await act(async()=>{});
 expect(next()).toBeEnabled();expect(choice()).toBeEnabled();
 act(()=>oldChoice());expect(fixture.command.mock.calls.some(([action])=>action.type==='onboarding.choice')).toBe(false);
 fireEvent.press(choice());await act(async()=>{});
 expect(fixture.command.mock.calls.filter(([action])=>action.type==='onboarding.choice')).toEqual([[{type:'onboarding.choice',id:'successor',fallback:false}]]);
 expect(fixture.command.mock.calls.some(([action])=>action.type==='onboarding.navigate')).toBe(false);view.unmount();
});


test('current setup readiness can settle beneath disabled controls without replaying navigation',async()=>{
 const {PresentationHydration}=require('../canonical/app/presentation-hydration') as typeof import('../canonical/app/presentation-hydration');
 const listeners=new Set<()=>void>(),notify=()=>{for(const listener of listeners)listener();};
 const hydration=new PresentationHydration(notify);hydration.beginBoundary();
 const nativeSetup={...setup(4),vpnInstalled:true};mockLive={onboardingSetup:nativeSetup,security:{readRevision:1}} as AppSnapshot;
 const command=jest.fn(async(_action:AppCommand)=>true);
 mockApp={command,getSnapshot:()=>({snapshot:mockLive}),getPresentationAuthority:()=>true,getPresentationHydration:hydration.getSnapshot,
  registerPresentationRead:()=>hydration.registerRead(),settlePresentationRead:(ticket:import('../canonical/app/presentation-hydration').PresentationReadTicket|undefined)=>hydration.settleRead(ticket),
  subscribe:(listener:()=>void)=>{listeners.add(listener);return ()=>listeners.delete(listener);}} as unknown as import('../canonical/app/store').AppStore;
 const anchor:Anchor={current:null};const view=render(<OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},setup:nativeSetup}}><OnboardingFlow/></OnboardingGeometryContext.Provider>);
 expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
 await act(async()=>{hydration.completeLayout(hydration.getSnapshot().epoch);await Promise.resolve();});
 expect(hydration.getSnapshot().required).toBe(true);
 fireEvent(screen.getByTestId('onboarding.flow.viewport'),'layout',{nativeEvent:{layout:{width:402,height:874,x:0,y:0}}});
 await act(async()=>{await Promise.resolve();});
 expect(hydration.getSnapshot().required).toBe(false);expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
 expect(command.mock.calls.some(([action])=>action.type==='onboarding.navigate')).toBe(false);
 fireEvent.press(screen.getByRole('button',{name:'Next step'}));await act(async()=>{});
 expect(command.mock.calls.filter(([action])=>action.type==='onboarding.navigate')).toEqual([[{type:'onboarding.navigate',id:'visit',page:5,revisit:false}]]);view.unmount();
});

test('isolated current setup adapters without native snapshot getters retain their existing control dispatch',async()=>{
 const nativeSetup={...setup(4),vpnInstalled:true};mockLive={onboardingSetup:nativeSetup,security:{readRevision:1}} as AppSnapshot;
 const command=jest.fn(async(_action:AppCommand)=>true);mockApp={command} as unknown as import('../canonical/app/store').AppStore;
 const anchor:Anchor={current:null};const view=render(<OnboardingGeometryContext.Provider value={{panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},setup:nativeSetup}}><OnboardingFlow/></OnboardingGeometryContext.Provider>);
 await act(async()=>{});expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
 fireEvent.press(screen.getByRole('button',{name:'Next step'}));await act(async()=>{});
 expect(command.mock.calls.filter(([action])=>action.type==='onboarding.navigate')).toEqual([[{type:'onboarding.navigate',id:'visit',page:5,revisit:false}]]);view.unmount();
});

// Actual Flow -> AppStore dispatch. The native port is deliberately held and
// has no test queue: ordering/coalescing belongs to the shipping JS caller.
async function onboardingIntentFixture(page:number,holdEnter=false){
 const {AppStore}=require('../canonical/app/store') as typeof import('../canonical/app/store');
 const {projectAndroidSnapshot}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
 const {productFixture}=require('./productFixture') as typeof import('./productFixture');
 const source=projectAndroidSnapshot({...productFixture,nativeHostID:'onboarding-host',revision:10,onboardingSetup:setup(page)});
 let revision=source.revision;
 const pending:Array<{action:AppCommand;resolve:()=>void;reject:(error:Error)=>void}>=[];
 let receive!:(value:string)=>void;
 const native={getSnapshot:jest.fn(async()=>JSON.stringify(source)),onSnapshot:(callback:(value:string)=>void)=>{receive=callback;return {remove:jest.fn()};},command:jest.fn((raw:string)=>{
  const action=JSON.parse(raw) as AppCommand;
  if(action.type==='onboarding.enter'&&!holdEnter)return Promise.resolve(JSON.stringify({snapshot:source,result:true}));
  return new Promise<string>((resolve,reject)=>pending.push({action,resolve:()=>resolve(JSON.stringify({snapshot:{...source,revision:++revision},result:true})),reject}));
 })};
 const app=new AppStore(native,source),disconnect=app.connect();mockApp=app;mockLive=source;
 await app.refresh();app.completePresentationLayout(app.getPresentationHydration().epoch);
 await act(async()=>{});act(()=>jest.advanceTimersByTime(32));await act(async()=>{});
 const view=render(flow(page));await act(async()=>{});
 const send=screen.UNSAFE_getByType(OnboardingPages).props.command as (action:AppCommand)=>Promise<boolean>;
 return {app,native,pending,send,view,disconnect,emit:(snapshot:AppSnapshot)=>act(()=>receive(JSON.stringify(snapshot))),flush:()=>act(async()=>{})};
}

test('queued absolute A-B-A onboarding choices all reach the real AppStore in order',async()=>{
 const f=await onboardingIntentFixture(3);
 try{
  const a=screen.getByTestId('onboarding.filter.balanced'),b=screen.getByTestId('onboarding.filter.comprehensive');
  act(()=>{fireEvent.press(a);fireEvent.press(b);fireEvent.press(a);});
  expect(f.pending.map(({action})=>action)).toEqual([{type:'onboarding.choice',id:'visit',level:'balanced'}]);
  expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  await act(async()=>{f.pending[0]!.resolve();});
  expect(f.pending.map(({action})=>action)).toEqual([{type:'onboarding.choice',id:'visit',level:'balanced'},{type:'onboarding.choice',id:'visit',level:'comprehensive'}]);
  expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  await act(async()=>{f.pending[1]!.resolve();});
  expect(f.pending.map(({action})=>action)).toEqual([{type:'onboarding.choice',id:'visit',level:'balanced'},{type:'onboarding.choice',id:'visit',level:'comprehensive'},{type:'onboarding.choice',id:'visit',level:'balanced'}]);
  expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  await act(async()=>{f.pending[2]!.resolve();});
  expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('queued navigation A-B-B-A-A keeps latest intent guarded when the first A settles',async()=>{
 const f=await onboardingIntentFixture(3);
 const a:AppCommand={type:'onboarding.navigate',id:'visit',page:0,revisit:true},b:AppCommand={type:'onboarding.navigate',id:'visit',page:1,revisit:true};
 try{
  let first!:Promise<boolean>,middle!:Promise<boolean>,latest!:Promise<boolean>,duplicateB!:Promise<boolean>,duplicateA!:Promise<boolean>;
  act(()=>{first=f.send(a);middle=f.send(b);duplicateB=f.send(b);latest=f.send(a);duplicateA=f.send(a);});
  expect(f.pending.map(({action})=>action)).toEqual([a]);
  await expect(duplicateB).resolves.toBe(false);await expect(duplicateA).resolves.toBe(false);
  await act(async()=>{f.pending[0]!.resolve();await first;});
  let duplicateAfterOld!:Promise<boolean>;act(()=>{duplicateAfterOld=f.send(a);});
  await expect(duplicateAfterOld).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([a,b]);
  expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  await act(async()=>{f.pending[1]!.resolve();await middle;});
  expect(f.pending.map(({action})=>action)).toEqual([a,b,a]);
  expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  await act(async()=>{f.pending[2]!.resolve();await latest;});
  expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
  let fresh!:Promise<boolean>;act(()=>{fresh=f.send(a);});expect(f.pending).toHaveLength(4);
  await act(async()=>{f.pending[3]!.resolve();await fresh;});
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('queued identical side effects coalesce while distinct side effects and later fresh intents remain',async()=>{
 const f=await onboardingIntentFixture(2),vpn:AppCommand={type:'onboarding.vpn',id:'visit'},notifications:AppCommand={type:'onboarding.notifications',id:'visit'};
 try{
  let first!:Promise<boolean>,second!:Promise<boolean>,duplicate!:Promise<boolean>;
  act(()=>{first=f.send(vpn);duplicate=f.send(vpn);second=f.send(notifications);});
  await expect(duplicate).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([vpn,notifications]);
  await act(async()=>{f.pending[0]!.resolve();await first;});
  expect(screen.getByTestId('onboarding.notifications')).toBeDisabled();
  let fresh!:Promise<boolean>;act(()=>{fresh=f.send(vpn);});expect(f.pending.map(({action})=>action)).toEqual([vpn,notifications,vpn]);
  await act(async()=>{f.pending[1]!.resolve();f.pending[2]!.resolve();await Promise.all([second,fresh]);});
  expect(screen.getByTestId('onboarding.notifications')).toBeEnabled();
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('queued choices cannot dispatch after the actual onboarding component unmounts',async()=>{
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 let first!:Promise<boolean>,queued!:Promise<boolean>;
 act(()=>{first=f.send(a);queued=f.send(b);});expect(f.pending.map(({action})=>action)).toEqual([a]);
 f.view.unmount();
 await act(async()=>{f.pending[0]!.resolve();await first;});
 await expect(queued).resolves.toBe(false);await expect(first).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([a]);
 f.disconnect();
});

test.each(['visit','generation','host'] as const)('queued choices cannot borrow a successor %s while the original receipt is held',async boundary=>{
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 try{
  let first!:Promise<boolean>,queued!:Promise<boolean>;act(()=>{first=f.send(a);queued=f.send(b);});
  expect(f.pending.map(({action})=>action)).toEqual([a]);
  const previous=f.app.getSnapshot().snapshot!;
  const successor={...previous,revision:50,...(boundary==='visit'?{onboardingSetup:{...previous.onboardingSetup!,id:'successor'}}:boundary==='generation'?{generation:'8'}:{nativeHostID:'successor-host'})};
  f.emit(successor);const accepted=f.app.getSnapshot().snapshot;await f.flush();
  await act(async()=>{f.pending[0]!.resolve();await first;});
  await expect(queued).resolves.toBe(false);await expect(first).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([a]);
  expect(f.app.getSnapshot().snapshot).toBe(accepted);
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('queued choices retain the original AppStore and cannot dispatch through a replacement app',async()=>{
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 try{
  let first!:Promise<boolean>,queued!:Promise<boolean>;act(()=>{first=f.send(a);queued=f.send(b);});
  const replacement=jest.fn(async()=>true);
  mockApp={command:replacement,getSnapshot:()=>({snapshot:mockLive}),getPresentationAuthority:()=>true,getPresentationHydration:()=>({epoch:1,required:false}),subscribe:()=>()=>{}} as unknown as import('../canonical/app/store').AppStore;
  f.view.rerender(flow(3));await f.flush();replacement.mockClear();
  await act(async()=>{f.pending[0]!.resolve();await first;});
  await expect(queued).resolves.toBe(false);await expect(first).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([a]);expect(replacement).not.toHaveBeenCalled();
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('queued choices refused beneath a real background authority boundary do not replay after resume',async()=>{
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 try{
  let first!:Promise<boolean>,queued!:Promise<boolean>;act(()=>{first=f.send(a);queued=f.send(b);});
  const previous=f.app.getSnapshot().snapshot!;
  f.emit({...previous,revision:50,foreground:false,available:false});expect(f.app.getPresentationAuthority()).toBe(false);
  await act(async()=>{f.pending[0]!.resolve();await first;});
  await expect(queued).resolves.toBe(false);await expect(first).resolves.toBe(false);expect(f.pending.map(({action})=>action)).toEqual([a]);
  f.emit({...previous,revision:51});await f.flush();expect(f.pending.map(({action})=>action)).toEqual([a]);
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('an ordinary current choice failure remains visible and does not poison the next queued choice',async()=>{
 const {Alert}=require('../canonical/app/presentation') as typeof import('../canonical/app/presentation');const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 try{
  let first!:Promise<boolean>,queued!:Promise<boolean>;act(()=>{first=f.send(a);queued=f.send(b);});
  expect(f.pending.map(({action})=>action)).toEqual([a]);
  await act(async()=>{f.pending[0]!.reject(new Error('Current native failure'));await first;});
  expect(alert).toHaveBeenCalledWith('Lava','Current native failure');expect(alert).toHaveBeenCalledTimes(1);
  expect(f.pending.map(({action})=>action)).toEqual([a,b]);
  await act(async()=>{f.pending[1]!.resolve();await queued;});expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});


test('queued choices cannot reborrow a renewed same-visit read epoch after real pause and resume',async()=>{
 const callbacks=new Set<(state:import('react-native').AppStateStatus)=>void>();
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,callback)=>{callbacks.add(callback);return {remove:()=>{callbacks.delete(callback);}};});
 const f=await onboardingIntentFixture(3),a:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'},b:AppCommand={type:'onboarding.choice',id:'visit',level:'comprehensive'};
 try{
  let first!:Promise<boolean>,queued!:Promise<boolean>;act(()=>{first=f.send(a);queued=f.send(b);});expect(f.pending.map(({action})=>action)).toEqual([a]);
  const source=f.app.getSnapshot().snapshot!,epoch=f.app.getReadEpoch();
  act(()=>{AppState.currentState='background';for(const callback of callbacks)callback('background');});
  expect(f.app.getPresentationAuthority()).toBe(false);expect(f.app.getReadEpoch()).toBeGreaterThan(epoch);
  act(()=>{AppState.currentState='active';for(const callback of callbacks)callback('active');});await f.flush();
  f.emit({...source,revision:50,security:{...source.security,readRevision:(source.security.readRevision??0)+1}});
  fireEvent(screen.getByTestId('onboarding.flow.viewport'),'layout',{nativeEvent:{layout:{width:874,height:402,x:0,y:0}}});
  f.app.completePresentationLayout(f.app.getPresentationHydration().epoch);await f.flush();act(()=>jest.advanceTimersByTime(32));await f.flush();
  expect(f.app.getPresentationAuthority()).toBe(true);expect(f.app.getPresentationHydration().required).toBe(false);
  const accepted=f.app.getSnapshot().snapshot;
  await act(async()=>{f.pending[0]!.resolve();await first;});await expect(queued).resolves.toBe(false);
  expect(f.pending.map(({action})=>action)).toEqual([a]);expect(f.app.getSnapshot().snapshot).toBe(accepted);
  let fresh!:Promise<boolean>;act(()=>{fresh=f.send(b);});expect(f.pending.map(({action})=>action)).toEqual([a,b]);
  await act(async()=>{f.pending[1]!.resolve();await fresh;});
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();AppState.currentState='active';await f.flush();}
});



test.each(['onboarding.enter','onboarding.navigate'] as const)('a held old %s cannot coalesce or retire the fresh UI or metadata action after resume',async type=>{
 const callbacks=new Set<(state:import('react-native').AppStateStatus)=>void>();
 jest.spyOn(AppState,'addEventListener').mockImplementation((_event,callback)=>{callbacks.add(callback);return {remove:()=>{callbacks.delete(callback);}};});
 const f=await onboardingIntentFixture(3,type==='onboarding.enter');
 const action:AppCommand=type==='onboarding.enter'?{type,id:'visit'}:{type,id:'visit',page:4,revisit:false};
 const choice:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'};
 try{
  if(type==='onboarding.navigate')fireEvent.press(screen.getByRole('button',{name:'Next step'}));
  expect(f.pending.map(request=>request.action)).toEqual([action]);
  const source=f.app.getSnapshot().snapshot!;
  act(()=>{AppState.currentState='background';for(const callback of callbacks)callback('background');});
  act(()=>{AppState.currentState='active';for(const callback of callbacks)callback('active');});await f.flush();
  f.emit({...source,revision:50,security:{...source.security,readRevision:(source.security.readRevision??0)+1}});
  fireEvent(screen.getByTestId('onboarding.flow.viewport'),'layout',{nativeEvent:{layout:{width:874,height:402,x:0,y:0}}});
  f.app.completePresentationLayout(f.app.getPresentationHydration().epoch);await f.flush();act(()=>jest.advanceTimersByTime(32));await f.flush();
  expect(f.app.getPresentationAuthority()).toBe(true);expect(f.app.getPresentationHydration().required).toBe(false);
  // An obsolete held flight cannot disable a genuinely admitted fresh control.
  expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
  if(type==='onboarding.navigate')fireEvent.press(screen.getByRole('button',{name:'Next step'}));
  else{
   expect(screen.getByTestId('onboarding.filter.balanced')).toBeEnabled();
   fireEvent.press(screen.getByTestId('onboarding.filter.balanced'));
   // Enter is a metadata effect, not an invented UI control. Drive its real
   // renewed readRevision dependency rather than calling a retained send closure.
   mockLive=f.app.getSnapshot().snapshot!;f.view.rerender(flow(3));await f.flush();
  }
  const expected=type==='onboarding.enter'?[action,choice,action]:[action,action];
  expect(f.pending.map(request=>request.action)).toEqual(expected);
  await act(async()=>{f.pending[0]!.resolve();});
  let duplicate!:Promise<boolean>;act(()=>{duplicate=f.send(action);});await expect(duplicate).resolves.toBe(false);
  expect(f.pending.map(request=>request.action)).toEqual(expected);expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  if(type==='onboarding.enter'){
   await act(async()=>{f.pending[1]!.resolve();});expect(screen.getByRole('button',{name:'Next step'})).toBeDisabled();
  }
  await act(async()=>{f.pending[type==='onboarding.enter'?2:1]!.resolve();});
  expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
  if(type==='onboarding.navigate')fireEvent.press(screen.getByRole('button',{name:'Next step'}));
  else act(()=>{for(const callback of callbacks)callback('active');});
  await f.flush();expect(f.pending.map(request=>request.action)).toEqual([...expected,action]);
  await act(async()=>{f.pending.at(-1)!.resolve();});
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();AppState.currentState='active';await f.flush();}
});


test('queued sequencing tail absorbs only its own rejection when a current error presenter throws',async()=>{
 const {Alert}=require('../canonical/app/presentation') as typeof import('../canonical/app/presentation');
 const presentationFailure=new Error('Current error presenter failed');jest.spyOn(Alert,'alert').mockImplementation(()=>{throw presentationFailure;});
 const f=await onboardingIntentFixture(3),choice:AppCommand={type:'onboarding.choice',id:'visit',level:'balanced'};
 try{
  let first!:Promise<boolean>;act(()=>{first=f.send(choice);});
  const handled=first.catch(error=>error);
  await act(async()=>{f.pending[0]!.reject(new Error('Current native failure'));});
  await expect(handled).resolves.toBe(presentationFailure);
  expect(screen.getByRole('button',{name:'Next step'})).toBeEnabled();
  // Let the actual host promise rejection turn complete; the caller handled
  // its real error, and an internal sequencing promise must not leak another.
  await new Promise<void>(resolve=>jest.requireActual('timers').setImmediate(resolve));
  let fresh!:Promise<boolean>;act(()=>{fresh=f.send(choice);});expect(f.pending).toHaveLength(2);
  await act(async()=>{f.pending[1]!.resolve();await fresh;});await expect(fresh).resolves.toBe(true);
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});


test.each([0,1,2])('the current onboarding step %s remains selected and cannot send a navigation intent',async page=>{
 const f=await onboardingIntentFixture(page);
 try{
  const selected=screen.getByRole('button',{name:`Step ${page+1} of 6`});
  expect(selected).toBeDisabled();expect(selected.props.accessibilityState).toMatchObject({selected:true});expect(selected).toHaveStyle({opacity:1});
  fireEvent.press(selected);expect(f.pending).toHaveLength(0);
  const other=page===0?1:0;fireEvent.press(screen.getByRole('button',{name:`Step ${other+1} of 6`}));
  expect(f.pending.map(({action})=>action)).toEqual([{type:'onboarding.navigate',id:'visit',page:other,revisit:true}]);
  await act(async()=>{f.pending[0]!.resolve();});
 }finally{f.pending.forEach(request=>request.resolve());f.view.unmount();f.disconnect();await f.flush();}
});

test('an accepted arrival retry after fresh destination measurement rebuilds travel and sends one owned Ready',async()=>{
 const {AppStore}=require('../canonical/app/store') as typeof import('../canonical/app/store');
 const {projectAndroidSnapshot}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
 const {productFixture}=require('./productFixture') as typeof import('./productFixture');
 const {LavaActionButton}=require('../canonical/src') as typeof import('../canonical/src');
 const state:OnboardingSetup={...setup(5),phase:'arriving',vpnInstalled:true};
 const source=projectAndroidSnapshot({...productFixture,nativeHostID:'onboarding-host',revision:10,onboardingSetup:state});
 const native={getSnapshot:jest.fn(async()=>JSON.stringify(source)),onSnapshot:()=>({remove:jest.fn()}),command:jest.fn(async(raw:string)=>JSON.stringify({snapshot:source,result:true}))};
 const app=new AppStore(native,source),disconnect=app.connect();mockApp=app;mockLive=source;
 await app.refresh();app.completePresentationLayout(app.getPresentationHydration().epoch);await act(async()=>{});act(()=>jest.advanceTimersByTime(32));await act(async()=>{});
 const anchor:Anchor={current:null};
 const frames={panel:{x:24,y:100,width:354,height:400},mascot:{x:136,y:124,width:128,height:128},action:{x:48,y:400,width:306,height:56}};
 const geometry={panel:anchor,mascot:anchor,action:anchor,source:anchor,root:anchor,origin:{x:0,y:0},size:{width:402,height:874},sourceFrame:{x:136,y:48,width:128,height:128},setup:state};
 const content=(measured:boolean)=><OnboardingGeometryContext.Provider value={{...geometry,frames:measured?frames:undefined}}><OnboardingFlow/></OnboardingGeometryContext.Provider>;
 const view=render(content(false));
 try{
  await act(async()=>{jest.advanceTimersByTime(8100);});
  const button=screen.getByRole('button',{name:'Try again'});expect(button).toBeEnabled();
  const recovery=screen.getByTestId('onboarding.retry');
  // A direct page-5 mount has no retained page body. Recovery must contribute
  // its own content height rather than absolutely filling a zero-height parent.
  expect(StyleSheet.flatten(recovery.props.style).position).not.toBe('absolute');
  expect(within(recovery).getByRole('button',{name:'Try again'})).toBe(button);
  const acceptedRetry=screen.UNSAFE_getAllByType(LavaActionButton).find(node=>node.props.title==='Try again')!.props.onPress as ()=>void;
  // The actual enabled control accepted this tap before destination measurement
  // committed; the same visit callback settles after the measurement update.
  view.rerender(content(true));act(()=>acceptedRetry());
  await act(async()=>{jest.advanceTimersByTime(1800);});
  const actions=native.command.mock.calls.map(([raw])=>JSON.parse(raw) as AppCommand);
  expect(actions.filter(action=>action.type==='onboarding.ready')).toEqual([{type:'onboarding.ready',id:'visit'}]);
  expect(screen.getByTestId('onboarding.ready')).toBeTruthy();expect(screen.queryByRole('button',{name:'Try again'})).toBeNull();
  expect(actions.some(action=>['onboarding.release','onboarding.complete','onboarding.open'].includes(action.type))).toBe(false);
  expect(app.getSnapshot().snapshot?.onboardingSetup?.id).toBe('visit');expect(app.getSnapshot().snapshot?.nativeHostID).toBe('onboarding-host');
 }finally{view.unmount();disconnect();await act(async()=>{});}
});
