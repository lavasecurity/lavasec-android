import {Alert, localized, configurePresentation, PresentationContext} from '../app/presentation';
import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {ActivityIndicator, AppState, View, useColorScheme} from 'react-native';
import {DarkTheme, DefaultTheme, NavigationContainer, createNavigationContainerRef} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {createNativeBottomTabNavigator} from '@react-navigation/bottom-tabs/unstable';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import NativeAppearance from '../specs/NativeLavaAppearance';
import NativeReview from '../specs/NativeLavaReview';
import NativeApp from '../specs/NativeLavaApp';
import {AppStore} from '../app/store';
import {NavigationTurn, nativeRedirectAction} from '../app/navigation-turn';
import type {AppCommand, AppSnapshot} from '../app/contract';
import {AppearanceStore} from './appearance-store';
import {ReviewContext, useReview} from './ReviewContext';
import {initialPreviewDraft} from './preview-model';
import {initialSession, protectedActionNames} from './session';
import {fullScreenModalPresentation, fullSheetPresentation, toolbarButton} from './scaffold';
import {useOrdinaryPushPresentation} from './navigation-scaffold';
import {lavaTokens} from '../src/generated/tokens';
import {LavaAppearanceContext, useLavaColorScheme} from '../src/appearance';
import type {PlusReason} from './plus-intents';
import * as Screens from './screens';

// Keep the pinned experimental native tab API at this single iOS boundary. It
// creates UITabBarController; a future Android implementation owns its mapping.
const Tabs = createNativeBottomTabNavigator();
const Stack = createNativeStackNavigator<Screens.ReviewRoutes>();
const destinations = [
  ['Filters', Screens.FiltersScreen, 'Filters'], ['Activity', Screens.ActivityScreen, 'Activity'],
  ['Explore', Screens.ExploreScreen, 'Explore'],
  ['Library', Screens.LibraryScreen, 'Your filters'], ['Filter', Screens.FilterScreen, 'Example filter'],
  ['Review', Screens.ReviewScreen, 'Review'], ['Share', Screens.ShareScreen, 'Choose a filter to share'],
  ['Import', Screens.ImportScreen, 'Import a filter'], ['TopDomains', Screens.DomainListScreen, 'Top Domains'],
  ['History', History, 'Domain History'], ['Customization', Screens.CustomizationScreen, 'Customization'],
  ['Guardian', Screens.GuardianScreen, 'Lava Guard'], ['Account', Screens.AccountScreen, 'Account & Backup'],
  ['Upgrade', Screens.UpgradeScreen, 'Lava Security Plus'], ['DNS', Screens.DNSScreen, 'DNS settings'], ['DNSPicker', Screens.DNSPickerScreen, 'Choose DNS'], ['CustomEntry', Screens.CustomEntryScreen, 'Custom DNS'],
  ['DNSPatch', Screens.DNSPatchScreen, 'Patch for iOS 27+'],
  ['Privacy', Screens.PrivacyScreen, 'Privacy & Data'], ['Security', Screens.SecurityScreen, 'Security'],
  ['Feedback', Screens.FeedbackSettingsScreen, 'Feedback'], ['Legal', Screens.LegalScreen, 'Legal Notices'],
  ['Stats', Screens.StatsScreen, 'Nerd Stats'], ['Network', Screens.NetworkScreen, 'Network Activity'],
  ['ShareDetail', Screens.ShareDetailScreen, 'Share my filter'], ['Passcode', Screens.PasscodeScreen, 'Passcode'],
  ['AddDomain', Screens.AddDomainScreen, 'Add Blocked Domain'], ['AddBlocklist', Screens.AddBlocklistScreen, 'Add a blocklist'],
  ['VPNChaining', Screens.VPNChainingScreen, 'VPN chaining'],
  ['DeviceQA', Screens.DeviceQAScreen, 'Device QA'],
  ['AutoSwitch', Screens.AutoSwitchScreen, 'Auto-switch filters'],
  ['Components', Screens.ComponentsScreen, 'Design system'],
  ['Sudoku', Screens.SudokuScreen, 'Sudoku'],
] as const;
function History() { return <Screens.DomainListScreen history />; }
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
  // Native-stack/UIKit own the header material and scroll-edge treatment.
  // Do not force transparent/opaque paint or replace the title with a custom view.
  return <Stack.Navigator initialRouteName={root === 'Guard' && reviewGallery ? 'Components' : root}
    screenOptions={{statusBarHidden: false, statusBarStyle: scheme === 'dark' ? 'light' : 'dark', headerLargeTitleEnabled: true, headerShadowVisible: false,
    headerBackButtonDisplayMode: 'minimal',
    headerTintColor: rgb(lavaTokens.colors.navigationForeground[scheme]),
    headerTitleStyle: {color: rgb(lavaTokens.colors.ink[scheme])}, headerLargeTitleStyle: {color: rgb(lavaTokens.colors.ink[scheme])},
    contentStyle: {backgroundColor: rgb(lavaTokens.colors.groupedBackground[scheme])}}}>
    <Stack.Screen name={root} component={root === 'Guard' ? Screens.GuardScreen : Screens.SettingsScreen}
      options={{title: localized(root),headerShown:true}} />
    {destinations.filter(([name])=>['VPNChaining','DeviceQA'].includes(name)?!!app&&!!live?.qaTools:name==='Components'?!app||!!live?.qaTools:!app||!['Import','Passcode'].includes(name)).map(([name, component, title]) => <Stack.Screen key={name} name={name} component={component} options={({navigation,route}) => ({title:localized(title),headerBackVisible:true,
      ...(!sheets.has(name)&&name!=='Passcode'&&name!=='Sudoku'?pushPresentation:{}),
      ...(name === 'Components' && !app ? {unstable_headerRightItems:()=>[toolbarButton('Close','xmark',()=>NativeReview.close(),false,'review.close')]} : {}),
      headerLargeTitleEnabled: !['Library', 'Filter', 'Share', 'Guardian', 'Review', 'Import', 'ShareDetail', 'Passcode', 'AddDomain', 'AddBlocklist'].includes(name),
      ...(sheets.has(name) ? {...fullSheetPresentation,
        unstable_headerLeftItems: () => [toolbarButton('Close', 'xmark', () => navigation.goBack())], headerBackVisible: false} : {}),
      ...(name==='Passcode'?{presentation:'fullScreenModal',headerLargeTitleEnabled:false,headerTransparent:false,headerStyle:{backgroundColor:rgb(lavaTokens.colors.groupedBackground[scheme])},
        unstable_headerLeftItems:()=>[toolbarButton('Close','xmark',()=>navigation.goBack())]}:{}),
      // VPN chaining is hosted native content without its own SwiftUI navigation
      // stack. Keep its title on this stack as an inline title so UIKit can own
      // the push/pop transition with Settings' large title.
      ...(['AutoSwitch','DNSPatch','VPNChaining'].includes(name)?{headerLargeTitleEnabled:false}:{}),
      ...(name==='DeviceQA'||name==='Feedback'&&app?{headerShown:false}:{}),
      ...(name==='Feedback'?{gestureEnabled:false}:{}),
      // Sudoku keeps the native full-screen presentation while its shared rail
      // owns the controls in both orientations. Status-bar visibility is owned
      // by the screen's orientation: visible in portrait so the Dynamic Island
      // is not suppressed, hidden in landscape to keep the board height.
      ...(name==='Sudoku'?{...fullScreenModalPresentation,
        headerShown:false,title:''}:{}),
    })} />)}
  </Stack.Navigator>;
}
function GuardStack() { return <RootStack root="Guard" />; }
function SettingsStack() { return <RootStack root="Settings" />; }

export function LavaUIReview({activityExample = false, reviewGallery = false, fullApp = false, onboardingPreview = false, initialSnapshot, plusContext}: {activityExample?: boolean; reviewGallery?: boolean; fullApp?: boolean; onboardingPreview?:boolean; initialSnapshot?: string;plusContext?:PlusReason}) {
  const [app] = useState(() => fullApp && NativeApp ? new AppStore(NativeApp, initialSnapshot ? JSON.parse(initialSnapshot) as AppSnapshot : undefined) : undefined);
  return <LavaPresentation app={app} onboardingPreview={onboardingPreview} fullApp={fullApp} activityExample={activityExample} reviewGallery={reviewGallery} plusContext={plusContext}/>;
}
const emptyLive = {snapshot: null, error: null};
const noSubscribe = () => () => {};
function LavaPresentation({app, onboardingPreview, fullApp, activityExample, reviewGallery,plusContext}: {app?: AppStore; onboardingPreview:boolean; fullApp: boolean; activityExample: boolean; reviewGallery: boolean;plusContext?:PlusReason}) {
  const appState = useSyncExternalStore(app?.subscribe ?? noSubscribe, app?.getSnapshot ?? (() => emptyLive));
  const live = appState.snapshot ?? undefined;
  const [navigation] = useState(() => createNavigationContainerRef());
  const lastNavigation = useRef(0);
  const navigationTurn = useRef(new NavigationTurn());
  const settingsEntryPending = useRef(false);
  const navigateNativeRequest = () => {
    if(plusContext||onboardingPreview)return;
    const request = live?.navigation;
    if (!request || request.serial <= lastNavigation.current || !navigation.isReady()) return;
    const state=navigation.getRootState();
    if(!state)return;
    const action=nativeRedirectAction(state,request.tab,request.screen);
    if(!action)return;
    lastNavigation.current = request.serial;
    navigationTurn.current.markExternalRequest();
    navigation.dispatch(action);
  };
  useEffect(navigateNativeRequest,[live?.navigation?.serial]);
  useEffect(() => app?.connect(), [app]);
  const run = (command: AppCommand) => { if (app) void app.command(command).catch(error => Alert.alert('Lava', String(error.message ?? error))); };
  const recordNavigationTurn = () => {if(plusContext||onboardingPreview)return;const state=navigation.getRootState();if(state&&navigationTurn.current.advance(state))run({type:'navigation.endTurn'});};

  const [appearance] = useState(() => new AppearanceStore(NativeAppearance));
  const [fixtureLook, setFixtureLook] = useState('original');
  const [fixtureDraft, setFixtureDraft] = useState(initialPreviewDraft);
  const [fixtureSavedDraft, setSavedDraft] = useState(initialPreviewDraft);
  const [fixtureSession, setFixtureSession] = useState(initialSession);
  const look = live?.look ?? fixtureLook;
  const draft = live?.draft ?? fixtureDraft;
  const savedDraft = live?.savedDraft ?? fixtureSavedDraft;
  const session = live ? {...fixtureSession, ...live.session, sudoku: live.sudoku} : fixtureSession;
  const setLook = (value: string) => app ? run({type: 'settings.set', key: 'look', value}) : setFixtureLook(value);
  const setDraft = setFixtureDraft;
  const setSession = (value: typeof session) => {
    if (!app || !live) {setFixtureSession(value); return;}
    const pending: Promise<unknown>[]=[];
    const run=(command:AppCommand)=>{pending.push(app.command(command));};
    // Presentation choices remain local. Native settings are individual commands,
    // never a whole stale snapshot written over the authoritative configuration.
    setFixtureSession({...fixtureSession, shareFilter: value.shareFilter});
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
    return Promise.all(pending).then(()=>{},error=>{if(error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);});
  };
  const {snapshot} = useSyncExternalStore(appearance.subscribe, appearance.getSnapshot);
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
  configurePresentation(live?.presentation);
  if (fullApp && !live) return <View style={{flex: 1, justifyContent: 'center', alignItems: 'center'}}><ActivityIndicator accessibilityLabel={localized('Loading Lava')} />{(!app || appState.error) && <Screens.RuntimeError message={appState.error ?? 'The full Lava runtime is missing from this installation.'} retry={() => void app?.refresh()} />}</View>;
  return <ReviewContext.Provider value={{app, live, onboardingPreview, appearance, look, setLook, draft, setDraft, savedDraft, setSavedDraft, session, setSession, activityExample, reviewGallery}}>
    <LavaAppearanceContext.Provider value={dark?'dark':'light'}><PresentationContext.Provider value={live?.presentation??{locale:"en",textScales:null}}><SafeAreaProvider><NavigationContainer ref={navigation} onReady={()=>{recordNavigationTurn();navigateNativeRequest();}} onStateChange={recordNavigationTurn} theme={{...base, colors: {...base.colors, primary: rgb(green), background: rgb(background)}}}>
      {plusContext?<Stack.Navigator screenOptions={{headerShown:false}}><Stack.Screen name="Upgrade" component={Screens.UpgradeScreen} initialParams={{reason:plusContext,embedded:true} as Screens.ReviewRoutes['Upgrade']}/></Stack.Navigator>:<Tabs.Navigator screenOptions={({route}) => ({headerShown: false, tabBarActiveTintColor: rgb(green),
        // Only a protected Settings entry waits for native authentication.
        // The tab adapter always permits native reselection of the active tab.
        tabBarSelectionEnabled: !(app && route.name === 'SettingsTab' && session.protectedActions['Update App Settings']),
      })}>
        <Tabs.Screen name="GuardTab" component={GuardStack} options={{title: localized('Guard'), tabBarIcon: ({focused}) => ({type: 'sfSymbol', name: 'shield.fill'})}} />
        <Tabs.Screen name="SettingsTab" component={SettingsStack} options={{title: localized('Settings'), tabBarIcon: () => ({type: 'sfSymbol', name: 'gearshape.fill'})}}
          listeners={({navigation}) => ({tabPress: () => {
            const state=navigation.getState();
            if(state.routes[state.index]?.name==='SettingsTab'||!app||!session.protectedActions['Update App Settings']||settingsEntryPending.current)return;
            settingsEntryPending.current=true;
            const origin=navigation.getState().routes[navigation.getState().index]?.key;
            void app.command({type:'navigation.authorize',surface:'appSettings',newTurn:true})
              .then(() => {if(AppState.currentState==='active'&&navigation.getState().routes[navigation.getState().index]?.key===origin){navigationTurn.current.markExternalRequest();navigation.navigate('SettingsTab');}})
              .catch(error => {if(error.message!=='Authentication cancelled.')Alert.alert('Lava', error.message);})
              .finally(()=>{settingsEntryPending.current=false;});
          }})} />
      </Tabs.Navigator>}
    </NavigationContainer></SafeAreaProvider></PresentationContext.Provider></LavaAppearanceContext.Provider>
  </ReviewContext.Provider>;
}
