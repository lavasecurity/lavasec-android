import {useOnboardingDestination} from './onboarding-geometry';
import {usePresentationNativeLayout} from '../app/use-presentation-readiness';
import {useAppAction} from '../app/actions';
import {AppCommandDetachedError} from '../app/store';
export {DNSPatchScreen} from './DNSPatchScreen';
export {VPNChainingScreen,AutoSwitchScreen,FeedbackSettingsScreen,DeviceQAScreen} from './NativePageScreen';
import {useMascotTapInteraction} from './mascot-interaction';
import {ContextMenu} from './ContextMenu';
import {useIsFocused,useNavigation} from '@react-navigation/native';
import {Alert, localized, localizedFormat} from '../app/presentation';
import {useEffect,useRef,useState} from 'react';
import { AppState, View} from 'react-native';
import {Group,Link} from './scaffold';
import {useRouteBodyConcealed} from './ReviewContext';
import {LavaActionButton} from '../src';
import {LavaComponentGallery} from '../gallery/LavaComponentGallery';
import {Copy, Guardian, Row, Screen} from './primitives';
import {useReview} from './ReviewContext';
import {activeFilterSummary,todaySummary} from './connection-model';
import {ExploreInvitation,GuardSummaries,ProtectionHero,StoryColumns,StoryStack,StorySurface} from './story-scaffold';
import {previewNotice, useReviewNavigation} from './navigation';
export type {ReviewRoutes} from './navigation';
export {previewNotice} from './navigation';
export * from './SettingsScreens';
export * from './FilterScreens';
export * from './DiagnosticScreens';
export {FeedbackScreen} from './FeedbackScreen';
export {ActivityScreen} from './ActivityScreen';
export {SudokuScreen} from './SudokuScreen';
export {ExploreScreen} from './ExploreScreen';
export {DomainCheckScreen} from './DomainCheckScreen';

export function GuardScreen() {
  const nav=useReviewNavigation();const {session,look,app,live,onboardingPreview}=useReview();
  const concealed=useRouteBodyConcealed();
  const shared=useOnboardingDestination();const run=useAppAction();
  const ready=shared.setup?.page===5&&['arriving','ready'].includes(shared.setup.phase);
  const rawNav=useNavigation();const revealing=useRef(false);const active=useRef(true);const revealEpoch=useRef(0);
  const taps=useRef({count:0,last:0});const focused=useIsFocused();
  const anchors=shared;
  const [foreground,setForeground]=useState(AppState.currentState==='active');
  const mascotInteraction=useMascotTapInteraction(true,live?.protection.mood==='awake');
  const gesture=(value:'start'|'end'|'tap'|'reveal')=>{if(onboardingPreview)return;void app?.command({type:'guard.gesture',gesture:value}).catch(()=>{});};
  useEffect(()=>{
    const reset=()=>{taps.current.count=0;gesture('end');};
    active.current=focused;if(!focused)reset();
    const listener=AppState.addEventListener('change',state=>{setForeground(state==='active');if(state!=='active')reset();if(state==='background')++revealEpoch.current;});
    return()=>{active.current=false;++revealEpoch.current;listener.remove();reset();};
  },[focused,app]);
  const reveal=()=>{
    taps.current.count=0;gesture('end');
    if(!app){nav.navigate('Guardian');return;}if(revealing.current)return;revealing.current=true;const started=revealEpoch.current;
    void app.command({type:'navigation.authorize',surface:'appSettings'}).then(()=>{if(active.current&&started===revealEpoch.current){gesture('reveal');rawNav.navigate('Guardian' as never);}}).catch(error=>{if(active.current&&started===revealEpoch.current&&!(error instanceof AppCommandDetachedError)&&error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);}).finally(()=>{revealing.current=false;});
  };
  const mascotTap=()=>{
    mascotInteraction.tap();
    const now=Date.now();
    taps.current={count:now-taps.current.last>=1200?1:taps.current.count+1,last:now};
    if(taps.current.count===5){taps.current.count=0;nav.navigate('Sudoku');}
  };
  // The mounted preview loses native private fields under the same resume cover.
  const protection = onboardingPreview&&live?.protection ? {...live.protection,title:'Protection Off',subtitle:'Tap once to add local protection',action:'Turn On',mood:'sleeping',materialIntent:'rest' as const,actionTone:'affirmative' as const,canPause:false,configuring:false,disabled:false,needsVPNSetup:false,needsDNSProviderChange:false} : live?.protection;
  const command = () => {if(onboardingPreview||shared.setup||protection?.disabled||protection?.configuring)return;if(app)void run({type:'protection.toggle'});else previewNotice();};
  const mood = mascotInteraction.grateful&&protection?.mood==='awake'?'grateful':protection?.mood ?? "sleeping";
  const pauseOptions=protection?.canPause?(protection.pauseOptions??[5,10,15].map(minutes=>({minutes,title:localizedFormat('Pause for %d minutes',minutes)}))).map(option=>({id:String(option.minutes),title:option.title,symbol:'pause.circle'})):[];
  const pause=(id:string)=>{if(pauseOptions.some(option=>option.id===id))void run({type:'protection.pause',minutes:Number(id) as 5|10|15});};
  // The Guard stays mounted as the real measured destination of the shared
  // onboarding animation. Its retained controls must not compete with the
  // visible modal's Open Guard action in touch or accessibility navigation.
  return <View style={{flex:1}} collapsable={false} pointerEvents={shared.setup?'none':'auto'}
    accessibilityElementsHidden={!!shared.setup} importantForAccessibility={shared.setup?'no-hide-descendants':'auto'}>
    <Screen wide><StoryColumns primary={<View testID="guard.panel" ref={anchors.panel} collapsable={false}><ProtectionHero ready={ready} title={protection?.title ?? 'Protection Off'}
    materialIntent={protection?.materialIntent}
    active={focused&&foreground&&!concealed}
    description={protection?.subtitle ?? 'Tap once to add local protection'}
    accessibilityActions={[{name:'playSudoku',label:localized('Play Sudoku')},{name:'changeGuardian',label:localized('Change Lava Guard')}]}
    onAccessibilityAction={event=>{if(event.nativeEvent.actionName==='playSudoku')nav.navigate('Sudoku');else if(event.nativeEvent.actionName==='changeGuardian')reveal();}}
    mascot={<View ref={anchors.mascot} collapsable={false} style={{opacity:shared.setup&&shared.setup.phase!=='released'?0:1}}><Guardian testID="guard.mascot" look={look} mood={mood} gesturesEnabled={focused&&foreground}
      onGesture={value=>{if(value==='end')gesture('end');else if(focused&&foreground){if(value==='reveal')reveal();else if(value==='tap')mascotTap();else gesture('start');}}}/></View> }
    action={<View testID="guard.action" ref={anchors.action} collapsable={false}><ContextMenu testID="guard.pause-menu" actions={pauseOptions} onAction={pause}>
      <LavaActionButton stablePill tone={protection?.actionTone} title={ready?'Open Guard':protection?.action ?? 'Turn On'} subtitle={protection?.canPause?'Long-press for pause options':undefined}
        disabled={protection?.disabled} busy={protection?.configuring} onPress={command}
        accessibilityActions={pauseOptions.map(option=>({name:option.id,label:localized(option.title)}))} onAccessibilityAction={event=>pause(event.nativeEvent.actionName)}/>
    </ContextMenu></View>}>
    {protection?.needsDNSProviderChange&&<Link title="Change DNS provider" onPress={()=>nav.navigate('DNS')} footer/>}
    {protection?.needsVPNSetup&&<Link title="Review VPN setup" onPress={()=>nav.navigate('VPNChaining')} footer/>}
  </ProtectionHero></View>} secondary={<StoryStack>
    <GuardSummaries today={todaySummary(live,session)} filter={activeFilterSummary(live,session)} onActivity={()=>nav.navigate('Activity')} onFilters={()=>nav.navigate('Filters')}/>
    <Group testID="guard.tools">
      {(!app||live?.domainCheckAvailable)&&<Row intent="page" testID="guard.check-domain" title="Check a domain" icon="magnifyingglass" onPress={()=>nav.navigate('DomainCheck')}/>}
      <ExploreInvitation attention={live?.discoveries?.['ios27Patch.settings']===true} onPress={()=>nav.navigate('Explore')}/>
    </Group>
  </StoryStack>}/></Screen></View>;
}
export function ComponentsScreen() {const onLayout=usePresentationNativeLayout();return <View style={{flex:1}} onLayout={onLayout}><LavaComponentGallery/></View>;}

export function RuntimeError({message, retry}: {message: string; retry: () => void}) {
  return <StorySurface><Copy role="body">{message}</Copy><LavaActionButton title="Try again" onPress={retry}/></StorySurface>;
}

export {CustomEntryScreen} from './CustomEntryScreen';
