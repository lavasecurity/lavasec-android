import {WindowSizeContext,type WindowSize} from '../src/window-layout';
import {Alert, localized, configurePresentation, PresentationContext,type Presentation} from '../app/presentation';
import {useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ComponentType} from 'react';
import {ActivityIndicator, Appearance, AppState, View, useColorScheme, useWindowDimensions} from 'react-native';
import {DarkTheme, DefaultTheme, NavigationContainer, createNavigationContainerRef} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {createBottomTabNavigator} from '@react-navigation/bottom-tabs';
import {QuietTabButton} from './QuietTabButton.android';
import {Symbol, Screen, Copy} from './primitives';
import {Info} from './scaffold';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import NativeAppearance,{androidAppearanceForHost} from '../specs/NativeLavaAppearance.android';
import NativeReview from '../specs/NativeLavaReview';
import NativeApp from '../specs/NativeLavaApp';
import {androidRuntimeForHost} from '../android/runtime';
import {AppCommandDetachedError, AppStore} from '../app/store';
import {NavigationTurn, nativeRedirectAction,nativeRedirectMatches} from '../app/navigation-turn';
import type {AppCommand, AppSnapshot, RuntimeRetryReceipt} from '../app/contract';
import {AppearanceStore} from './appearance-store';
import {ReviewContext, useReview,LiveRenderBoundary,usePreviewFixtureState} from './ReviewContext';
import {mayInteractWithPresentation} from '../app/read-cache';
import {initialPreviewDraft} from './preview-model';
import {initialSession, protectedActionNames} from './session';
import {platformToolbarOptions, fullScreenModalPresentation, fullSheetPresentation, toolbarButton} from './scaffold';
import {useOrdinaryPushPresentation} from './navigation-scaffold';
import {lavaTokens} from '../src/generated/tokens';
import {androidReviewRouteAllowed, androidUnavailableScreenRoutes} from '../../src/systemCapabilities';
import {LavaAppearanceContext, useLavaColorScheme} from '../src/appearance';
import {AndroidPaletteContext} from '../../platform/palette-context';
import {ForegroundFlowScreen} from './ForegroundFlowScreen';
import {OnboardingSurface} from './OnboardingSurface';
import {FilterRoute} from './FilterRoute';
import {useAndroidRouteRemoval,androidForegroundCloseAction} from './android-route-removal';
import {AndroidNestedRemovalBoundary} from './independent-navigation.android';
import {AndroidPresentationContext,AndroidPresentationControlContext,androidProtectedSurface,androidPresentationOwnerID,withAndroidPresentation,type AndroidPresentation} from './presentation-owner.android';
import {CustomEntryRoute} from './CustomEntryScreen';
import {applyAndroidLocale} from '../android/locale';
import type {PlusReason} from './plus-intents';
import {AndroidNavigationProducer} from '../../src/androidNavigationProducer';
import * as Screens from './screens';
import {EditionSettingsScreen,EditionAccountScreen} from '../android/EditionSettingsScreen';
import {androidEditionRouteAllowed} from '../android/edition';

// Android owns its bottom navigation; screen compositions remain shared.
const Tabs = createBottomTabNavigator();
const Stack = createNativeStackNavigator<Screens.ReviewRoutes>();
const unavailableRoutes = androidUnavailableScreenRoutes;
function UnavailableScreen() { return <Screen><Info title="Coming to Android" description="This part of Lava is not connected yet. Your existing protection and saved settings are unchanged." /></Screen>; }
const destinations = [
  ['Filters', Screens.FiltersScreen, 'Filters'], ['Activity', Screens.ActivityScreen, 'Activity'],
  ['Explore', Screens.ExploreScreen, 'Explore'],
  ['DomainCheck', Screens.DomainCheckScreen, 'Check a domain'],
  ['Library', Screens.LibraryScreen, 'Your filters'], ['Filter', FilterRoute, 'Example filter'],
  ['Review', Screens.ReviewScreen, 'Review'], ['Share', Screens.ShareScreen, 'Choose a filter to share'],
  ['Import', Screens.ImportScreen, 'Import a filter'], ['TopDomains', Screens.DomainListScreen, 'Top Domains'],
  ['History', History, 'Domain History'], ['Customization', Screens.CustomizationScreen, 'Customization'],
  ['Guardian', Screens.GuardianScreen, 'Lava Guard'], ['Account', EditionAccountScreen, 'Account & Backup'],
  ['Upgrade', Screens.UpgradeScreen, 'Lava Security Plus'], ['DNS', Screens.DNSScreen, 'DNS settings'], ['DNSPicker', Screens.DNSPickerScreen, 'Choose DNS'], ['CustomEntry', CustomEntryRoute, 'Custom DNS'],
  ['DNSPatch', Screens.DNSPatchScreen, 'Patch for iOS 27+'],
  ['Privacy', Screens.PrivacyScreen, 'Privacy & Data'], ['Security', Screens.SecurityScreen, 'Security'],
  ['Feedback', Screens.FeedbackSettingsScreen, 'Feedback'], ['Legal', Screens.LegalScreen, 'Legal Notices'],
  ['Stats', Screens.StatsScreen, 'Nerd Stats'], ['Network', Screens.NetworkScreen, 'Network Activity'],
  ['ShareDetail', Screens.ShareDetailScreen, 'Share my filter'], ['Passcode', Screens.PasscodeScreen, 'Passcode'],
  ['AddDomain', Screens.AddDomainScreen, 'Add Blocked Domain'], ['AddBlocklist', Screens.AddBlocklistScreen, 'Add a blocklist'],
  ['VPNChaining', Screens.VPNChainingScreen, 'VPN chaining'],
  ['DeviceQA', Screens.DeviceQAScreen, 'Device QA'],
  ['AutoSwitch', Screens.AutoSwitchScreen, 'Auto-switch filters'],
  ['Foreground',ForegroundFlowScreen,'Lava'],
  ['Components', Screens.ComponentsScreen, 'Design system'],
  ['Sudoku', Screens.SudokuScreen, 'Sudoku'],
] as const;
function History() { return <Screens.DomainListScreen history />; }
const privateRoute=(component:ComponentType,retireOnPolicyChange=true)=>function PrivateRoute(){
  useAndroidRouteRemoval();
  return <LiveRenderBoundary component={component} retainBody directScrollRoot retireOnPolicyChange={retireOnPolicyChange}/>;
};
const GuardRoute=privateRoute(Screens.GuardScreen),SettingsRoute=privateRoute(EditionSettingsScreen,false),PlusRoute=privateRoute(Screens.UpgradeScreen);
function ForegroundRoute(){
  useAndroidRouteRemoval();
  return <ForegroundFlowScreen/>;
}
const routeDestinations=destinations.map(([name,component,title])=>[name,name==='CustomEntry'?CustomEntryRoute:name==='Filter'?FilterRoute:name==='Foreground'?ForegroundRoute:privateRoute(component,name!=='Security'&&name!=='Privacy'),title] as const);

function RootStack({root}: {root: 'Guard' | 'Settings'}) {
  const {app,live,reviewGallery} = useReview();
  const pushPresentation=useOrdinaryPushPresentation();
  const sheets = new Set(['Guardian','Review','Import','ShareDetail','AddDomain','AddBlocklist','DNSPicker']);
  // Header colors and the native navigation theme share the resolved app
  // preference, including while UIKit is transitioning between controllers.
  const scheme = useLavaColorScheme();
  const rgb = (color: readonly number[]) => `rgb(${color.map(value => Math.round(value * 255)).join(',')})`;
  // Use headerLargeTitleEnabled, not the deprecated headerLargeTitle alias:
  // native-stack keys animated header-height handling on the supported option.
  // Android navigation chrome uses the same resolved paint as the page body.
  return <AndroidNestedRemovalBoundary diagnostics={live?.qaTools===true?(root==='Guard'?'guard-parent':'settings-parent'):undefined}><Stack.Navigator initialRouteName={root === 'Guard' && reviewGallery ? 'Components' : root}
    layout={({state,children})=><AndroidNestedRemovalBoundary state={state} diagnostics={live?.qaTools===true?(root==='Guard'?'guard-routes':'settings-routes'):undefined}>{children}</AndroidNestedRemovalBoundary>}
    screenOptions={{statusBarHidden: false, statusBarStyle: scheme === 'dark' ? 'light' : 'dark', headerLargeTitleEnabled: true, headerShadowVisible: false,
    headerBackButtonDisplayMode: 'minimal',
    headerTintColor: rgb(lavaTokens.colors.navigationForeground[scheme]),
    headerStyle: {backgroundColor: rgb(lavaTokens.colors.groupedBackground[scheme])},
    headerTitleStyle: {color: rgb(lavaTokens.colors.ink[scheme])}, headerLargeTitleStyle: {color: rgb(lavaTokens.colors.ink[scheme])},
    contentStyle: {backgroundColor: rgb(lavaTokens.colors.groupedBackground[scheme])}}}>
    <Stack.Screen name={root} component={withAndroidPresentation(root === 'Guard' ? GuardRoute : SettingsRoute)}
      options={{title: localized(root),headerShown:true}} />
    {routeDestinations.filter(([name])=>androidEditionRouteAllowed(name,live?.edition)&&androidReviewRouteAllowed(name, live?.qaTools===true, !!app, {ownerPresent:!!live?.vpn})).map(([name, component, title]) => <Stack.Screen key={name} name={name} component={withAndroidPresentation(unavailableRoutes.has(name) ? UnavailableScreen : component)} options={({navigation,route}) => platformToolbarOptions({title:localized(name==='Account'&&live?.edition?.accounts===false?'Local Backup':title),headerBackVisible:true,
      ...(!sheets.has(name)&&name!=='Passcode'&&name!=='Sudoku'?pushPresentation:{}),
      ...(name === 'Components' && !app ? {unstable_headerRightItems:()=>[toolbarButton('Close','xmark',()=>NativeReview.close(),false,'review.close')]} : {}),
      headerLargeTitleEnabled: !['Library', 'Filter', 'Share', 'Guardian', 'Review', 'Import', 'ShareDetail', 'Passcode', 'AddDomain', 'AddBlocklist'].includes(name),
      ...(sheets.has(name) ? {...fullSheetPresentation,presentation:'modal',
        unstable_headerLeftItems: () => [toolbarButton('Close', 'xmark', () => navigation.goBack())], headerBackVisible: false} : {}),
      ...(name==='Passcode'?{presentation:'fullScreenModal',headerLargeTitleEnabled:false,headerTransparent:false,headerStyle:{backgroundColor:rgb(lavaTokens.colors.groupedBackground[scheme])},
        unstable_headerLeftItems:()=>[toolbarButton('Close','xmark',()=>navigation.goBack())]}:{}),
      // VPN chaining is hosted native content without its own SwiftUI navigation
      // stack. Keep its title on this stack as an inline title so UIKit can own
      // the push/pop transition with Settings' large title.
      ...(['AutoSwitch','DNSPatch','VPNChaining'].includes(name)?{headerLargeTitleEnabled:false}:{}),
      ...(name==='DeviceQA'||name==='Feedback'&&app?{headerShown:false}:{}),
      ...(['Feedback','Foreground'].includes(name)?{gestureEnabled:false,headerLargeTitleEnabled:false,presentation:'modal'}:{}),
      // Sudoku keeps the native full-screen presentation while its shared rail
      // owns the controls in both orientations. Status-bar visibility is owned
      // by the screen's orientation: visible in portrait, hidden in landscape.
      ...(name==='Sudoku'?{...fullScreenModalPresentation,
        headerShown:false,title:''}:{}),
    })} />)}
  </Stack.Navigator></AndroidNestedRemovalBoundary>;
}
function GuardStack() { return <RootStack root="Guard" />; }
function SettingsStack() { return <RootStack root="Settings" />; }

export function LavaUIReview({activityExample = false, reviewGallery = false, fullApp = false, onboardingPreview = false, initialSnapshot, initialAppearance, plusContext,nativeHostID}: {activityExample?: boolean; reviewGallery?: boolean; fullApp?: boolean; onboardingPreview?:boolean; initialSnapshot?: string; initialAppearance?: string;plusContext?:PlusReason;nativeHostID?:string}) {
  // A legacy canonical seed contains no native host proof. Bound production
  // roots obtain their first app frame from the exact originating Activity.
  const [{app,producer}] = useState(() => {
    const producer=new AndroidNavigationProducer();
    const native=fullApp&&NativeApp?(nativeHostID!==undefined?androidRuntimeForHost(nativeHostID):NativeApp):undefined;
    const app=native?new AppStore(producer.observe(native),nativeHostID===undefined&&initialSnapshot?JSON.parse(initialSnapshot) as AppSnapshot:undefined):undefined;
    return {app,producer};
  });
  return <LavaPresentation app={app} producer={producer} onboardingPreview={onboardingPreview} fullApp={fullApp} activityExample={activityExample} reviewGallery={reviewGallery} initialAppearance={initialAppearance} plusContext={plusContext} nativeHostID={nativeHostID} />;
}
const emptyLive = {snapshot: null, error: null};
const noSubscribe = () => () => {};
const emptyHydration={epoch:0,required:false};
function LavaPresentation({app,producer, onboardingPreview, fullApp, activityExample, reviewGallery, initialAppearance, plusContext,nativeHostID}: {app?: AppStore;producer:AndroidNavigationProducer; onboardingPreview:boolean; fullApp: boolean; activityExample: boolean; reviewGallery: boolean; initialAppearance?: string;plusContext?:PlusReason;nativeHostID?:string}) {
  const window=useWindowDimensions();
  // Available window dimensions (including split-screen), never a device-model guess.
  const [viewport,setViewport]=useState<WindowSize|null>(null);
  const available=viewport??window;
  const navigationRail=available.width>=840&&available.height>=480;
  const appState = useSyncExternalStore(app?.subscribe ?? noSubscribe, app?.getSnapshot ?? (() => emptyLive));
  const live = appState.snapshot ?? undefined;
  // Public locale and text metrics survive a withheld private snapshot.
  const publicPresentation=useRef<Presentation>({locale:'en',textScales:null});
  if(live?.presentation)publicPresentation.current=live.presentation;
  const hydration=useSyncExternalStore(app?.subscribe??noSubscribe,app?.getPresentationHydration??(()=>emptyHydration));
  const presentationAuthority=useSyncExternalStore(app?.subscribe??noSubscribe,()=>app?.getPresentationAuthority()??true);
  const [navigation] = useState(() => createNavigationContainerRef<Screens.ReviewRoutes>());
  const [navigatorReady,setNavigatorReady]=useState(false);
  const lastNavigation = useRef(0);
  const pendingNavigation=useRef<{epoch:number;generation?:string;host?:string;matched?:boolean;request:NonNullable<AppSnapshot['navigation']>;dispatched:boolean;ticket:ReturnType<AppStore['registerPresentationRead']>}|undefined>(undefined);
  const [readyReceipt,setReadyReceipt]=useState<{serial:number;epoch:number}|undefined>(undefined);
  const navigationAck=useRef<string|undefined>(undefined);
  const navigationTurn = useRef(new NavigationTurn());
  const settingsEntryPending = useRef(false);
  const [presentationOwner,setPresentationOwner]=useState<AndroidPresentation|null>(null);
  const presentationSerial=useRef(0);
  const [retryReceipt,setRetryReceipt]=useState<RuntimeRetryReceipt|undefined>(undefined);
  const retryVisit=useRef<object|undefined>(undefined),retryMeasured=useRef(false),retryHandoff=useRef(false);
  const [retryLayout,setRetryLayout]=useState(0);
  const publication=useRef<{key:string;owner:AndroidPresentation;attempt:number;status:'pending'|'accepted'|'refused'}|undefined>(undefined);
  const presentationLifecycle=useRef(0),presentationMounted=useRef(false);
  const [publicationSettlement,setPublicationSettlement]=useState(0);
  const presentedFlow=useRef<string|undefined>(undefined);
  const modalPresentationOwner=useRef<string|undefined>(undefined);
  producer.render();
  useLayoutEffect(()=>producer.commit({
    qaTools:live?.qaTools===true,snapshotRevision:live?.revision,hydrationEpoch:hydration.epoch,hydrationRequired:hydration.required,
    presentationSerial:publication.current?.owner.serial??0,presentationStatus:publication.current?.status??'none',presentationAuthority,
    securityReadRevision:live?.security?.readRevision,
  }));
  const acceptNativeNavigation=()=>{
    const pending=pendingNavigation.current,current=app?.getSnapshot().snapshot;
    const state=navigation.getRootState();
    if(!pending||!current||!app?.getPresentationAuthority()||AppState.currentState!=='active'||app.getPresentationHydration().epoch!==pending.epoch
      ||current.navigation?.serial!==pending.request.serial||!state||!nativeRedirectMatches(state,pending.request.tab,pending.request.screen))return;
    if(pending.generation!==current.generation||pending.host!==current.nativeHostID)return;
    // Settle the redirect's read ticket first so the actual destination can reach Ready.
    if(!pending.matched){pending.matched=true;app.settlePresentationRead(pending.ticket);}
    const published=publication.current;
    if(!published||published.status!=='accepted'||!isCurrentPublication(published)||
      readyReceipt?.serial!==published.owner.serial||readyReceipt.epoch!==pending.epoch)return;
    const key=JSON.stringify([nativeHostID,current.generation,pending.request.serial,published.owner.serial,pending.epoch]);
    if(navigationAck.current===key)return;navigationAck.current=key;
    void app.command({type:'navigation.accept',serial:pending.request.serial,presentationSerial:published.owner.serial}).then(()=>{
      if(pendingNavigation.current!==pending||publication.current!==published||!isCurrentPublication(published)||
        AppState.currentState!=='active'||!app.getPresentationAuthority()||app.getPresentationHydration().epoch!==pending.epoch)return;
      lastNavigation.current=pending.request.serial;pendingNavigation.current=undefined;
    }).catch(()=>{});
  };
  const navigateNativeRequest=()=>{
    if(plusContext||onboardingPreview||AppState.currentState!=='active'||!navigatorReady||!app?.getPresentationAuthority())return;
    const request=app.getSnapshot().snapshot?.navigation;
    if(!request||request.serial<=lastNavigation.current||!navigation.isReady())return;
    const pending=pendingNavigation.current;
    if(!pending||pending.request.serial!==request.serial||pending.epoch!==app.getPresentationHydration().epoch)return;
    const state=navigation.getRootState();if(!state)return;
    if(nativeRedirectMatches(state,request.tab,request.screen)){acceptNativeNavigation();return;}
    if(pending.dispatched)return;
    const action=nativeRedirectAction(state,request.tab,request.screen);
    if(!action){pendingNavigation.current=undefined;app.settlePresentationRead(pending.ticket);return;}
    pending.dispatched=true;navigationTurn.current.markExternalRequest();navigation.dispatch(action);
  };
  useLayoutEffect(()=>{
    const request=live?.navigation;
    if(!app||!app.getPresentationAuthority()||!request||request.serial<=lastNavigation.current||plusContext||onboardingPreview||AppState.currentState!=='active')return;
    const pending={epoch:hydration.epoch,generation:live?.generation,host:live?.nativeHostID,request,dispatched:false,ticket:app.registerPresentationRead()};pendingNavigation.current=pending;
    return()=>{if(pendingNavigation.current===pending)pendingNavigation.current=undefined;app.settlePresentationRead(pending.ticket);};
  },[app,live?.navigation?.serial,!!live,hydration.epoch,plusContext,onboardingPreview]);
  useLayoutEffect(navigateNativeRequest,[live?.navigation?.serial,!!live,navigatorReady,hydration.epoch]);
  useLayoutEffect(()=>app?.connect(),[app]);
  useEffect(()=>{if(!app||!navigation.isReady()||plusContext||onboardingPreview)return;const flow=live?.foregroundFlow;
    if(flow&&presentedFlow.current!==flow.id){presentedFlow.current=flow.id;navigation.navigate('Foreground',{id:flow.id});}
    else if(!flow&&presentedFlow.current&&live?.foregroundClosing?.includes(presentedFlow.current)){const action=androidForegroundCloseAction(navigation.getRootState(),presentedFlow.current);presentedFlow.current=undefined;if(action)navigation.dispatch(action);}
  },[app,live?.foregroundFlow?.id,live?.foregroundClosing,navigation,plusContext,onboardingPreview,navigatorReady]);
  const run = (command: AppCommand) => { if (app&&mayInteractWithPresentation(app)) void app.command(command).catch(error => {if (!(error instanceof AppCommandDetachedError)&&mayInteractWithPresentation(app)) Alert.alert('Lava', String(error.message ?? error));}); };
  const currentPresentationTarget=()=>{
    const modal=modalPresentationOwner.current;
    if(modal)return {routeKey:modal,surface:'none' as const,ownerID:modal};
    const route=navigation.getCurrentRoute();if(!route)return;
    const params=route.params as {id?:string;kind?:string}|undefined;
    const kind=route.name==='Foreground'?app?.getSnapshot().snapshot?.foregroundFlow?.kind:route.name==='CustomEntry'?params?.kind:undefined;
    return {routeKey:route.key,surface:androidProtectedSurface(route.name,kind),ownerID:androidPresentationOwnerID(route.name,params?.id)};
  };
  const presentationKey=(target:NonNullable<ReturnType<typeof currentPresentationTarget>>)=>JSON.stringify([
    nativeHostID,target.routeKey,target.surface,target.ownerID,app?.getSnapshot().snapshot?.generation,app?.getSnapshot().snapshot?.security?.readRevision,
    app?.getPresentationHydration().epoch,presentationLifecycle.current,
  ]);
  const isCurrentPublication=(attempt:NonNullable<typeof publication.current>)=>{
    const target=currentPresentationTarget();
    return presentationMounted.current&&publication.current===attempt&&AppState.currentState==='active'
      &&!!app?.getPresentationAuthority()&&!!target&&attempt.key===presentationKey(target);
  };
  const publishPresentation=(routeKey:string,surface:AndroidPresentation['surface'],ownerID?:string)=>{
    if(!app||!presentationMounted.current||AppState.currentState!=='active'||!app.getPresentationAuthority())return;
    const key=presentationKey({routeKey,surface,ownerID}),previous=publication.current;
    if(previous?.key===key&&(previous.status!=='refused'||previous.attempt>=2))return previous.owner;
    const owner:AndroidPresentation={serial:++presentationSerial.current,routeKey,surface,ownerID,accepted:false};
    const attempt={key,owner,attempt:previous?.key===key?previous.attempt+1:1,status:'pending' as 'pending'|'accepted'|'refused'};
    publication.current=attempt;setPresentationOwner(owner);
    const refused=()=>{
      if(!isCurrentPublication(attempt))return;
      attempt.status='refused';
      setPresentationOwner(current=>current?.serial===owner.serial?{...current,accepted:false}:current);
      // A refusal can arrive while the current foreground frame is settling.
      // Retry once; a later lifecycle/graph/layout epoch starts a new visit.
      if(attempt.attempt<2)setPublicationSettlement(value=>value+1);
    };
    void app.command({type:'navigation.presentation',serial:owner.serial,surface,...(ownerID?{ownerID}:{}),...(app.getSnapshot().snapshot?.security?.readRevision!==undefined?{readRevision:app.getSnapshot().snapshot!.security!.readRevision}:{})}).then(result=>{
      // Native facades reject non-completed replies. Keep the receipt safe if
      // an adapter ever forwards a structured rejection instead.
      if(result&&typeof result==='object'&&'status' in result&&result.status!=='completed'){refused();return;}
      if(!isCurrentPublication(attempt))return;
      attempt.status='accepted';attempt.owner={...owner,accepted:true};
      setPresentationOwner(current=>current?.serial===owner.serial?attempt.owner:current);
    },refused);
    return owner;
  };
  const recordNavigationTurn = () => {
    const state=navigation.getRootState();if(!plusContext&&!onboardingPreview&&state&&navigationTurn.current.advance(state))run({type:'navigation.endTurn'});
    const target=currentPresentationTarget();if(target&&!modalPresentationOwner.current)publishPresentation(target.routeKey,target.surface,target.ownerID);
  };
  const presentationControl=useMemo(()=>({beginModal:(ownerID:string)=>{
    modalPresentationOwner.current=ownerID;
    return publishPresentation(ownerID,'none',ownerID)??{serial:presentationSerial.current,routeKey:ownerID,surface:'none' as const,ownerID,accepted:false};
  },restoreMain:()=>{
    modalPresentationOwner.current=undefined;publication.current=undefined;
    setPresentationOwner(current=>current?{...current,accepted:false}:current);recordNavigationTurn();
  },ready:(serial:number,epoch:number)=>{
    const current=publication.current;
    if(!current||current.owner.serial!==serial||current.status!=='accepted'||!isCurrentPublication(current)||app?.getPresentationHydration().epoch!==epoch)return;
    setReadyReceipt(previous=>previous?.serial===serial&&previous.epoch===epoch?previous:{serial,epoch});
  }}),[app,nativeHostID,plusContext,onboardingPreview]);
  useLayoutEffect(()=>{acceptNativeNavigation();},[readyReceipt]);
  useLayoutEffect(()=>{
    presentationMounted.current=true;
    let phase=AppState.currentState;
    const subscription=AppState.addEventListener('change',state=>{
      if(state===phase)return;phase=state;
      // A retained route needs a new publication receipt after every native
      // pause, even when its route key and graph did not change.
      presentationLifecycle.current++;publication.current=undefined;
      setPresentationOwner(current=>current?{...current,accepted:false}:current);
      setPublicationSettlement(value=>value+1);
    });
    return()=>{presentationMounted.current=false;publication.current=undefined;subscription.remove();};
  },[app]);
  // Publication and readiness are separate receipts. Re-publish the exact
  // main/modal owner on restoration before any readiness acknowledgement.
  useLayoutEffect(()=>{
    if(!app||!navigatorReady||AppState.currentState!=='active'||!presentationAuthority)return;
    const target=currentPresentationTarget();if(target)publishPresentation(target.routeKey,target.surface,target.ownerID);
  },[app,live?.generation,live?.security?.readRevision,hydration.epoch,presentationAuthority,navigatorReady,publicationSettlement]);
  // The public error has its own paint receipt. It never completes hydration,
  // admits native writes, or serves as a normal navigation Ready receipt.
  useLayoutEffect(()=>{
    retryVisit.current=undefined;setRetryReceipt(undefined);
    if(!fullApp||!app||live||!appState.error||AppState.currentState!=='active')return;
    retryHandoff.current=true;retryMeasured.current=false;
    const visit={};retryVisit.current=visit;
    void app.prepareRuntimeRetry(++presentationSerial.current).then(receipt=>{
      if(retryVisit.current===visit&&receipt)setRetryReceipt(receipt);
    }).catch(()=>{});
    return()=>{if(retryVisit.current===visit)retryVisit.current=undefined;};
  },[app,fullApp,!!live,appState,publicationSettlement]);
  useLayoutEffect(()=>{
    const visit=retryVisit.current;
    if(!app||!retryReceipt||!retryMeasured.current||!visit)return;
    const frame=requestAnimationFrame(()=>{
      if(retryVisit.current===visit&&AppState.currentState==='active')void app.acknowledgeRuntimeRetry(retryReceipt).catch(()=>{});
    });
    return()=>cancelAnimationFrame(frame);
  },[app,retryReceipt,retryLayout]);
  const currentPublication=publication.current;
  const admittedPresentation=presentationOwner?{...presentationOwner,accepted:presentationOwner.accepted===true
    &&!!currentPublication&&currentPublication.status==='accepted'&&isCurrentPublication(currentPublication)}:null;

  const [appearance] = useState(() => new AppearanceStore(nativeHostID!==undefined?androidAppearanceForHost(nativeHostID):NativeAppearance, initialAppearance ? {preference: initialAppearance} : undefined));
  const [fixtureLook, setFixtureLook] = useState('original');
  const {draft:fixtureDraft,savedDraft:fixtureSavedDraft,session:fixtureSession,setDraft:setFixtureDraft,setSavedDraft,setSession:setFixtureSession}=usePreviewFixtureState(!fullApp);
  const look = live?.look ?? fixtureLook;
  const draft = live?.draft ?? fixtureDraft;
  const savedDraft = live?.savedDraft ?? fixtureSavedDraft;
  const session = live ? {...fixtureSession, ...live.session, sudoku: live.sudoku} : fixtureSession;
  const setLook = (value: string) => app ? run({type: 'settings.set', key: 'look', value}) : setFixtureLook(value);
  const setDraft = setFixtureDraft;
  const setSession = (value: typeof session) => {
    if(!app){setFixtureSession(value);return;}
    if(!live||!mayInteractWithPresentation(app)||AppState.currentState!=='active')return;
    const pending: Promise<unknown>[]=[];
    const run=(command:AppCommand)=>{pending.push(app.command(command));};
    // Native settings are individual commands. Share route IDs carry selection;
    // production callbacks never copy native private fields into preview state.
    if (value.filter !== session.filter) {
      const filter = live.filters.find(item => item.name === value.filter);
      if (filter) run({type: 'filter.open', id: filter.id});
    }
    if (value.editing !== session.editing) run({type: value.editing ? 'filter.edit' : 'filter.cancel', id: live.session.filterID});
    if (value.blocklists.join('|') !== session.blocklists.join('|')) run({type: 'filter.lists', id: live.session.filterID, ids: value.blocklists});
    for (const key of ['deviceDNS','fallback','provider','transport','matchTextSize','textSize','haptics','liveActivities','matchIcon','biometrics'] as const) {
      if (value[key] !== session[key]) run({type: 'settings.set', key, value: value[key]});
    }
    for (const group of ['logs','notifications'] as const) for (const [key, enabled] of Object.entries(value[group])) {
      if (enabled !== session[group][key]) run({type: 'settings.set', key: `${group}.${key}`, value: enabled});
    }
    for (const key of protectedActionNames) {
      if (value.protectedActions[key] !== session.protectedActions[key]) run({type:'settings.set',key:`protectedActions.${key}`,value:value.protectedActions[key]});
    }
    if (value.sudoku && value.sudoku !== session.sudoku) run({type: 'sudoku.save', game: value.sudoku});
    return Promise.all(pending).then(()=>{},error=>{if(!(error instanceof AppCommandDetachedError)&&error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);});
  };
  const {snapshot} = useSyncExternalStore(appearance.subscribe, appearance.getSnapshot);
  const confirmedAppearance = snapshot?.preference;
  useEffect(() => {
    // Shared palette and native PlatformColor resources follow the same confirmed preference.
    // Applying Android's existing appearance adapter is effect-only: do not write the owner
    // again or turn its snapshot event into another settings command.
    if (confirmedAppearance) Appearance.setColorScheme(confirmedAppearance === 'system' ? 'unspecified' : confirmedAppearance);
  }, [confirmedAppearance]);
  const systemScheme = useColorScheme();
  const dark = snapshot?.preference === 'dark' || (snapshot?.preference !== 'light' && systemScheme === 'dark');
  useEffect(() => {
    const disconnect = appearance.connect();
    const foreground = AppState.addEventListener('change', state => { if (state === 'active') { void appearance.refresh(); void app?.refresh(); } });
    return () => { foreground.remove(); disconnect(); };
  }, [appearance, app]);
  const base = dark ? DarkTheme : DefaultTheme;
  const green = lavaTokens.colors.safeGreen[dark ? 'dark' : 'light'];
  const background = lavaTokens.colors.groupedBackground[dark ? 'dark' : 'light'];
  const rgb = (color: readonly number[]) => `rgb(${color.map(value => Math.round(value * 255)).join(',')})`;
  // One locale authority: normalise any Android/BCP-47 tag to the canonical catalog key before
  // `configurePresentation` and the shared `PresentationContext` consume it (plan 309 F22/V2).
  const presentation = applyAndroidLocale(publicPresentation.current);
  configurePresentation(presentation);
  const cold=fullApp&&!live;
  // Keep the private handoff hidden until native has retired the public receipt
  // and accepted the normal presentation; then its usual hydration cover owns it.
  if(retryHandoff.current&&admittedPresentation?.accepted===true)retryHandoff.current=false;
  const frameHidden=cold||retryHandoff.current&&admittedPresentation?.accepted!==true;
  const frameInert=frameHidden||fullApp&&(!presentationAuthority||hydration.required);
  // Keep these native boundaries through cold-cover removal. Opacity/pointer
  // changes otherwise change Fabric stacking-context traits and lift the
  // navigator's native children into the surface during the first reveal.
  // The native surface already owns the exact usable pane. Pin the viewport to
  // its edges so reopening/inset changes do not depend on a percentage-width root.
  return <View collapsable={false} testID="lava-window-viewport" style={{position:'absolute',top:0,right:0,bottom:0,left:0}} onLayout={event=>{
    const {width,height}=event.nativeEvent.layout;
    if(width>0&&height>0)setViewport(old=>old?.width===width&&old?.height===height?old:{width,height});
  }}><WindowSizeContext.Provider value={viewport}>
    <View collapsable={false} testID="lava-render-frame" style={[{flex:1,width:'100%'},{opacity:frameHidden?0:1}]} pointerEvents={frameInert?'none':'auto'}
      accessibilityElementsHidden={frameInert} importantForAccessibility={frameInert?'no-hide-descendants':'auto'}>
  <ReviewContext.Provider value={{app, live, onboardingPreview:onboardingPreview||live?.onboardingSetup?.mock===true, appearance, look, setLook, draft, setDraft, savedDraft, setSavedDraft, session, setSession, activityExample, reviewGallery}}>
    <AndroidPaletteContext.Provider value={dark?'dark':'light'}><LavaAppearanceContext.Provider value={dark?'dark':'light'}><PresentationContext.Provider value={presentation}><SafeAreaProvider style={{flex:1,width:'100%'}}><AndroidPresentationContext.Provider value={admittedPresentation}><AndroidPresentationControlContext.Provider value={presentationControl}><OnboardingSurface enabled={!!app&&!plusContext}><NavigationContainer ref={navigation} onReady={()=>{setNavigatorReady(true);recordNavigationTurn();navigateNativeRequest();}} onStateChange={()=>{acceptNativeNavigation();recordNavigationTurn();navigateNativeRequest();}} theme={{...base, colors: {...base.colors, primary: rgb(green), background: rgb(background)}}}>
      {plusContext?<Stack.Navigator layout={({state,children})=><AndroidNestedRemovalBoundary state={state}>{children}</AndroidNestedRemovalBoundary>} screenOptions={{headerShown:false}}>
        <Stack.Screen name="Upgrade" component={withAndroidPresentation(PlusRoute)} initialParams={{reason:plusContext,embedded:true} as Screens.ReviewRoutes['Upgrade']}/>
      </Stack.Navigator>:<Tabs.Navigator layout={({state,children})=><AndroidNestedRemovalBoundary state={state}>{children}</AndroidNestedRemovalBoundary>} screenOptions={({route}) => ({headerShown: false, tabBarActiveTintColor: rgb(green),
        tabBarPosition:navigationRail?'left':'bottom',tabBarVariant:navigationRail?'material':'uikit',
        tabBarShowLabel:false,tabBarActiveBackgroundColor:'transparent',
        tabBarIconStyle:{width:32,height:32},
        tabBarButton:props=><QuietTabButton {...props}/>,
        tabBarStyle:{backgroundColor:rgb(lavaTokens.colors.cardBackground[dark?'dark':'light'])},
        // Only a protected Settings entry waits for native authentication.
        // The tab adapter always permits native reselection of the active tab.

      })}>
        <Tabs.Screen name="GuardTab" component={GuardStack} options={{title: localized('Guard'),tabBarAccessibilityLabel:localized('Guard'), tabBarIcon: ({focused}) => <Symbol name={focused?'shield.fill':'shield'} size={28} tone={focused?'green':'secondary'} />}} />
        <Tabs.Screen name="SettingsTab" component={SettingsStack} options={{title: localized('Settings'),tabBarAccessibilityLabel:localized('Settings'), tabBarIcon: ({focused}) => <Symbol name={focused?'gearshape.fill':'gearshape'} size={28} tone={focused?'green':'secondary'} />}}
          listeners={({navigation}) => ({tabPress: event => {
            const state=navigation.getState();
            if(state.routes[state.index]?.name==='SettingsTab'||!app||!session.protectedActions['Update App Settings'])return;
            event.preventDefault();
            if(settingsEntryPending.current)return;
            settingsEntryPending.current=true;
            const origin=navigation.getState().routes[navigation.getState().index]?.key;
            void app.command({type:'navigation.authorize',surface:'appSettings',newTurn:true})
              .then(() => {if(AppState.currentState==='active'&&navigation.getState().routes[navigation.getState().index]?.key===origin){navigationTurn.current.markExternalRequest();navigation.navigate('SettingsTab');}})
              .catch(error => {if(!(error instanceof AppCommandDetachedError)&&error.message!=='Authentication cancelled.')Alert.alert('Lava', error.message);})
              .finally(()=>{settingsEntryPending.current=false;});
          }})} />
      </Tabs.Navigator>}
    </NavigationContainer></OnboardingSurface></AndroidPresentationControlContext.Provider></AndroidPresentationContext.Provider></SafeAreaProvider></PresentationContext.Provider></LavaAppearanceContext.Provider></AndroidPaletteContext.Provider>
  </ReviewContext.Provider></View>
    {cold&&<View key={retryReceipt?.serial??'loading'} collapsable={false} style={{position:'absolute',top:0,bottom:0,left:0,right:0,justifyContent:'center',alignItems:'center'}}
      onLayout={event=>{if(event.nativeEvent.layout.width>0&&event.nativeEvent.layout.height>0&&!retryMeasured.current){retryMeasured.current=true;setRetryLayout(value=>value+1);}}}>
      {(!app||appState.error)?<Screens.RuntimeError message="Lava could not load the app. Try again." retry={()=>void app?.retryPresentation()}/>:<ActivityIndicator accessibilityLabel={localized('Loading Lava')}/>}
    </View>}
  </WindowSizeContext.Provider></View>;
}
