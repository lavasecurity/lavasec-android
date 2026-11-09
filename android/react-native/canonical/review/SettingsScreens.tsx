import {logNames} from './session';
import {useLocalLogActions} from './local-log-actions';
import {Alert} from '../app/presentation';
import {AppCommandDetachedError} from '../app/store';
import {mayInteractWithPresentation,presentationOwnerScope} from '../app/read-cache';
import {usePlusEntry} from './plus-entry';
import {plusMessages,usePlusIntents,mayResumePlusIntent,appUnlockAuthorized,retryablePlusResume,PlusResumePending,type PlusReason} from './plus-intents';
import type {ReviewRoutes} from './navigation';
import {billingTerms} from './billing-terms';
import {useAppAction,useExclusiveAppAction} from '../app/actions';
import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {AccessibilityInfo, AppState, Linking, Platform, View} from 'react-native';
import {StackActions,useRoute,type RouteProp,useIsFocused} from '@react-navigation/native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {LavaChoice} from '../src';
import {colors} from '../src/colors';
import {Choice, Copy, Row, Screen, Section, Symbol} from './primitives';
import {Link, LinkGroup, ListRow, PasscodeEntry, Quiet, QuietFooter, Sheet, Toggle} from './scaffold';
import {useReview} from './ReviewContext';
import {localized,localizedFormat} from '../app/presentation';
import {LavaPlusStory} from './plus-scaffold';
import {previewNotice, useReviewNavigation} from './navigation';

import {connectionStages} from './connection-model';
import {ConnectionPanel,StoryColumns,StoryStack} from './story-scaffold';
import {SettingsControl,SettingsDisclosure,SettingsGlyph,SettingsGroup,SettingsGuardRow,SettingsGuardPreview,SettingsGuardSpotlight,SettingsInset,SettingsIntro,SettingsLoading,SettingsMessage,SettingsPrice,SettingsStack,SettingsStatus,SettingsSubscriptionStatus,SettingsSurface,SettingsTextPreview,SettingsTextSlider,settingsStyles} from './settings-scaffold';

/**
 * Confirmation copy for the local-log classes the Android bridge owner clears. The native snapshot
 * does not project a `confirmations` map, so the Privacy screen uses these instead of falling
 * through to the review-build notice for the three kinds that are not handled inline.
 */
export function SettingsScreen() {
  const nav = useReviewNavigation(); const {live,app,session} = useReview();
  // The feedback report is a native handoff. Route it through the exclusive action slot so a
  // double tap cannot open the report twice, matching the purchase/sign-in/backup handoffs.
  const report=useExclusiveAppAction('report');
  const stages=connectionStages(live,session);
  return <Screen wide><StoryColumns primary={<ConnectionPanel stages={stages}
    onSelect={stage=>stage.destination?nav.navigate(stage.destination):nav.navigate('Explore',{part:'phone'})}
    onExplore={()=>nav.navigate('Explore')}/>}
    secondary={<StoryStack>
      <SettingsGroup title="Your Lava">
        <Row intent="page" icon="person.crop.circle" title="Account & Backup" onPress={() => nav.navigate('Account')} />
        <Row intent="page" icon="slider.horizontal.3" title="Customization" onPress={() => nav.navigate('Customization')} />
        <Row intent="page" icon="shield.fill" title={live?.plus?.enabled?'Thank you for using Lava Plus':'Get Lava Plus today'} onPress={() => nav.navigate('Upgrade')} />
      </SettingsGroup>
      <SettingsGroup title="Privacy & security">
        <Row intent="page" icon="eyeglasses" testID="row.Privacy & data" title="Privacy & Data" onPress={() => nav.navigate('Privacy')} />
        <Row intent="page" icon="lock.fill" title="Security" onPress={() => nav.navigate('Security')} />
      </SettingsGroup>
      <SettingsGroup title="Support">
        <Row intent="external" icon="questionmark.circle" title="Help" onPress={() => { void Linking.openURL('https://lavasecurity.app/support/'); }} />
        <Row intent="page" icon="ladybug" title="Feedback" onPress={() => app ? void report.run({type:'native.flow',flow:'feedback'}) : nav.navigate('Feedback')} />
        <Row intent="page" icon="doc.text" title="Legal Notices" onPress={() => nav.navigate('Legal')} />
      </SettingsGroup>
      <SettingsGroup title="Advanced">
        <Row intent="page" icon="waveform.path.ecg.rectangle" testID="row.Network activity" title="Network Activity" onPress={() => nav.navigate('Network')} />
        <Row intent="page" icon="number" testID="row.Nerd stats" title="Nerd Stats" onPress={() => nav.navigate('Stats')} />
        {live?.qaTools&&<Row intent="page" icon="square.grid.2x2" title="Design system" onPress={()=>nav.navigate('Components')}/>}
        {live?.qaTools&&<Row intent="page" icon="hammer" title="Device QA" onPress={()=>nav.navigate('DeviceQA')}/>}
      </SettingsGroup>
      <Copy role="caption" color={colors.secondaryText}>{live ? `${localizedFormat('Lava %1$@ (build %2$@)',live.version,live.build)}${live.sourceRevision?` · ${live.sourceRevision.slice(0,12)}`:''}` : 'Lava · UI preview'}</Copy>
    </StoryStack>}/></Screen>;
}

export function AccountScreen() {
  const {app,live}=useReview(); const run=useAppAction();
  const signIn=useExclusiveAppAction('signIn',!!live?.account.busy);
  const backup=useExclusiveAppAction('backup',!!live?.backup.busy);
  const focused=useIsFocused();
  useEffect(()=>{
    if(!app||!focused||!live?.account.signedIn||live.backup.remoteStatus===undefined)return;
    const refresh=()=>{void Promise.resolve(app.command({type:'backup.refresh'})).catch(()=>{});};
    refresh();
    const subscription=AppState.addEventListener('change',state=>{if(state==='active')refresh();});
    return()=>subscription.remove();
  },[app,focused,live?.account.signedIn,live?.backup.enablement?.value]);
  const maintain=(button:string,type:'backup.delete'|'backup.disable')=>{
    if(backup.isBusy())return;
    const confirmation=live?.confirmations?.[button]??{title:button+'?',action:button,message:type==='backup.delete'
      ?"Permanently deletes your account's encrypted backup — this can't be undone. Backup stays on, and a fresh copy uploads next time."
      :"Turns off backup on this device and permanently deletes your account's copy. This can't be undone — you can set up a new backup later."};
    return new Promise<void>(resolve=>Alert.alert(confirmation.title,confirmation.message,[{text:'Cancel',style:'cancel',onPress:resolve},{text:confirmation.action,style:'destructive',onPress:()=>{void backup.run({type}).finally(resolve);}}],{onDismiss:resolve}));
  };
  const enablement=live?.backup.enablement;
  const signedIn=!!live?.account.signedIn;
  // Signed-out backup cannot upload, so present Off without changing the saved
  // native preference. Signed-in missing capabilities remain unknown.
  const enabled=signedIn?(enablement?.value??null):false;
  const needsAttention=live?.backup.needsAttention??false;
  const pendingDeletion=enablement?.state==='deletionPending'||!!live?.backup.deletionPending;
  const backupDetail=needsAttention?live?.backup.detail:undefined;
  // `enabled===null` is an owner-unknown state (a pending deletion or unavailable evidence). Prefer
  // the owner's own summary over a hardcoded "Needs attention", so a device whose backup service is
  // not configured reads its honest owner copy instead of a fabricated alert. `enabled===false`
  // keeps the canonical "No backup" for a confirmed-off device.
  const backupSummary=!signedIn?'Ready after sign-in':backupDetail||(enabled?live?.backup.remoteStatus??live?.backup.summary:enabled===false?'No backup':live?.backup.summary||live?.backup.detail||'Needs attention');
  const canShowTools=enabled===true&&signedIn;
  const setup=()=>{if(signedIn&&enablement?.canEnable&&!backup.isBusy())backup.run({type:'native.flow',flow:'backupSetup'});};
  const disable=()=>{if(signedIn&&enablement?.canDisable&&!backup.isBusy())maintain('Turn off & delete backup','backup.disable');};
  return <Screen>
    <SettingsIntro summary="Use Lava without an account, or sign in to back up your settings securely."/>
    <Section title="Account">
      <SettingsSurface tone={signedIn?'green':'neutral'}>
        <ListRow testID="account.status" title={signedIn?'Signed in':live?.account.busy?'Signing in':'Not signed in'} icon="person.crop.circle"
          leading={signedIn?<Symbol name="checkmark.circle.fill" tone="white"/>:undefined}
          disabled={signedIn&&signIn.busy} onPress={signedIn?()=>signIn.run({type:'native.flow',flow:'account'}):undefined}/>
      </SettingsSurface>
      <SettingsSurface>
        <ListRow action title={live?.account.appleTitle??'Sign in with Apple'} disabled={signIn.busy} leading={<SettingsGlyph name="apple.logo" busy={live?.account.appleBusy}/>}
          onPress={()=>signIn.run(live?.account.appleConnected?{type:'native.flow',flow:'account'}:{type:'account.apple'})}/>
        <ListRow action title={live?.account.googleTitle??'Sign in with Google'} disabled={signIn.busy} leading={<SettingsGlyph name="google.signin" busy={live?.account.googleBusy}/>}
          onPress={()=>signIn.run(live?.account.googleConnected?{type:'native.flow',flow:'account'}:{type:'account.google'})}/>
      </SettingsSurface>
    </Section>
    <Section title="Encrypted Backup">
      <SettingsSurface>
        {enabled===null
          ? <SettingsStatus icon="lock.icloud" title="Enable backup" description={backupSummary} busy={!!live?.backup.busy}/>
          : <Toggle title="Enable backup" summary={backupSummary} value={enabled} optimistic={false}
              disabled={!signedIn||backup.busy||!(enabled?enablement?.canDisable:enablement?.canEnable)}
              onChange={next=>{if(next)setup();else disable();}}/>}
        {canShowTools&&<ListRow action title={live?.backup.backingUp?'Backing Up':'Back Up Now'} icon="icloud.and.arrow.up.fill" leading={live?.backup.backingUp?<SettingsGlyph name="icloud.and.arrow.up.fill" busy/>:undefined}
          disabled={backup.busy||!enablement?.canBackUp} onPress={()=>backup.run({type:'backup.now'})}/>}
        {signedIn&&!pendingDeletion&&(canShowTools||enablement?.canRestore)&&<ListRow action title="Restore Backup" icon="icloud.and.arrow.down.fill"
          disabled={backup.busy||!enablement?.canRestore} onPress={()=>backup.run({type:'native.flow',flow:'backupRestore'})}/>}
        {pendingDeletion&&<ListRow action title="Turn off & delete backup" icon="trash" color={colors.errorText}
          disabled={backup.busy||!enablement?.canRetryDeletion} onPress={()=>maintain('Turn off & delete backup','backup.disable')}/>}
      </SettingsSurface>
      {canShowTools&&<>
        <SettingsSurface footer="Lava waits 5 minutes after your last settings change before it tries an automatic upload.">
          <Toggle title="Automatic Backup" accessibilityHint="Lava waits 5 minutes after your last settings change before it tries an automatic upload."
            value={!!live?.backup.automatic} disabled={backup.busy||!enablement?.canChangeAutomatic}
            onChange={value=>run({type:'settings.set',key:'backup.automatic',value})}/>
        </SettingsSurface>
        <SettingsSurface><ListRow action title="Delete backup"
          icon="trash" disabled={backup.busy||!enablement?.canDisable} color={colors.errorText}
          onPress={()=>maintain('Delete backup','backup.delete')}/></SettingsSurface>
      </>}
    </Section>
  </Screen>;
}

export function CustomizationScreen() {
  const nav = useReviewNavigation(); const {appearance, session, setSession,app,live,look} = useReview(); const run=useAppAction();
  const {snapshot, error} = useSyncExternalStore(appearance.subscribe, appearance.getSnapshot);
  return <Screen>
    <SettingsIntro summary="Change Lava's look and feel. Protection stays the same."/>
    <SettingsGuardPreview look={look} title={live?.guards?.find(g=>g.id===look)?.title??localized('Original')} subtitle={live?.guards?.find(g=>g.id===look)?.subtitle||undefined} onPress={()=>nav.navigate('Guardian')}/>
    <Section title="Appearance">
      <Choice label="Appearance" options={['Light','Dark','System']} value={snapshot?.preference==='light'?'Light':snapshot?.preference==='dark'?'Dark':'System'} onChange={value=>app?run({type:'settings.set',key:'appearance',value:value.toLowerCase()}):appearance.setPreference(value.toLowerCase() as 'system'|'light'|'dark')}/>
      {error&&<SettingsInset><SettingsMessage warning>{error}</SettingsMessage></SettingsInset>}
    </Section>
    <SettingsGroup title="Text Size">
      <SettingsTextPreview/>
      <Toggle title="Match System" value={session.matchTextSize} onChange={value=>setSession({...session,matchTextSize:value})}/>
      {!session.matchTextSize&&<SettingsTextSlider value={session.textSize} disabled={false} onChange={value=>setSession({...session,textSize:value})}/>}
    </SettingsGroup>
    <SettingsGroup title="Notifications & haptics">
      <Toggle title="App Haptics" value={session.haptics} onChange={value=>setSession({...session,haptics:value})}/>{Object.entries(session.notifications).map(([title,value])=><Toggle key={title} title={title} value={value} onChange={next=>setSession({...session,notifications:{...session.notifications,[title]:next}})}/>)}</SettingsGroup>
    {(!live||live.liveActivityPause.available)&&<SettingsGroup title="Live Activities (beta)" footer="Shows Lava status on the Lock Screen and Dynamic Island when available.">
      <Toggle title="Use Live Activities" accessibilityHint="Shows Lava status on the Lock Screen and Dynamic Island when available." value={session.liveActivities} onChange={value=>setSession({...session,liveActivities:value})}/>
      {session.liveActivities&&<SettingsControl title={live?.liveActivityPause.label??localizedFormat('Pause length: %d min',5)}><LavaChoice presentation="stepper" testID="Live Activity pause length" label={live?.liveActivityPause.label??localizedFormat('Pause length: %d min',5)} options={(live?.liveActivityPause.minutes??Array.from({length:30},(_,i)=>i+1)).map(minutes=>({value:String(minutes),label:String(minutes)}))} value={String(live?.liveActivityPauseMinutes??5)} onValueChange={value=>run({type:'settings.set',key:'liveActivityPauseMinutes',value:Number(value)})}/></SettingsControl>}
    </SettingsGroup>}
    <SettingsGroup title="Language"><Row intent="external" title="Change in system settings" onPress={()=>{void Linking.openSettings();}}/></SettingsGroup>
  </Screen>;
}

export function GuardianScreen() {
  const upgrade=usePlusEntry();const selectionIntents=usePlusIntents();
  const nav = useReviewNavigation(); const {session,setSession,app,live,look,setLook} = useReview();
  const selectionPending=useRef<{promise:Promise<void>}|undefined>(undefined);const iconPending=useRef(false);const [changingIcon,setChangingIcon]=useState(false);
  const selectionOwner=useRef({app,intents:selectionIntents,mounted:true,epoch:0});selectionOwner.current.app=app;selectionOwner.current.intents=selectionIntents;
  useEffect(()=>{
    selectionOwner.current.mounted=true;
    const lifecycle=AppState.addEventListener('change',state=>{if(state!=='active')selectionOwner.current.epoch++;});
    return()=>{selectionOwner.current.mounted=false;selectionOwner.current.epoch++;lifecycle.remove();};
  },[]);
  const focused=useIsFocused();const selectionVisit=useRef({active:false,epoch:0});
  useEffect(()=>{
    selectionVisit.current.active=focused&&AppState.currentState==='active';
    const lifecycle=AppState.addEventListener('change',state=>{
      if(state!=='active')selectionVisit.current.epoch++;
      selectionVisit.current.active=focused&&state==='active';
    });
    return()=>{selectionVisit.current.active=false;selectionVisit.current.epoch++;lifecycle.remove();};
  },[focused]);
  // A paid selection waits for the earlier native write; it is not a successful
  // no-op. Keep the original source owner while waiting, even under Upgrade.
  const captureSelectionOwner=()=>{
    const source=app?.getSnapshot?.().snapshot;
    return {app,intents:selectionIntents,connection:app?.getConnectionEpoch?.(),generation:source?.generation,host:source?.nativeHostID,scope:source?presentationOwnerScope(source):undefined,resumeEpoch:undefined as number|undefined};
  };
  const select=(id:string,resume=false,owner=captureSelectionOwner(),isCurrent?:()=>boolean):Promise<void>=>{
    if(resume&&owner.resumeEpoch===undefined)owner.resumeEpoch=selectionOwner.current.epoch;
    if(!app){setLook(id);return Promise.resolve();}
    const owns=()=>{
      const source=app.getSnapshot?.().snapshot;
      return selectionOwner.current.mounted&&selectionOwner.current.app===owner.app&&selectionOwner.current.intents===owner.intents&&
        app.getConnectionEpoch?.()===owner.connection&&selectionOwner.current.epoch===owner.resumeEpoch&&(isCurrent?.()??true)&&
        source?.generation===owner.generation&&source?.nativeHostID===owner.host&&
        (source?presentationOwnerScope(source):undefined)===owner.scope;
    };
    const execute=()=>{
      if(resume&&(!owns()||!mayInteractWithPresentation(app)))throw new AppCommandDetachedError('beforeStart');
      const request={promise:Promise.resolve()};
      request.promise=(resume?app.command({type:'settings.set',key:'look',value:id},()=>owns()&&mayInteractWithPresentation(app)):
        app.command({type:'settings.set',key:'look',value:id})).then(()=>{
        if(resume&&!owns())throw new AppCommandDetachedError('afterStart');
      }).finally(()=>{if(selectionPending.current===request)selectionPending.current=undefined;});
      selectionPending.current=request;return request.promise;
    };
    const pending=selectionPending.current;
    return pending?resume?pending.promise.then(execute,execute):pending.promise:execute();
  };
  const selectGuarded=(id:string)=>{
    if(selectionPending.current)return;
    const epoch=selectionVisit.current.epoch;
    void select(id).catch(error=>{
      if(selectionVisit.current.active&&selectionVisit.current.epoch===epoch&&mayInteractWithPresentation(app)&&
          !(error instanceof AppCommandDetachedError)&&error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);
    });
  };
  const matchIcon=(value:boolean)=>{
    if(!app){setSession({...session,matchIcon:value});return;}if(iconPending.current)return;iconPending.current=true;setChangingIcon(true);
    return app.command({type:'settings.set',key:'matchIcon',value}).catch(error=>{if(error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);}).finally(()=>{iconPending.current=false;setChangingIcon(false);});
  };
  const variants=live?.guards??[{id:'original',title:localized('Original'),description:localized('A Lava a day keeps bad domains away.'),tip:localized('Keep Lava protecting you to unlock more Guards, or upgrade to unlock them all.')}];
  return <Sheet>
    <SettingsGuardSpotlight look={look} variants={variants}/>
    <SettingsGroup title="Choose your Guard" testID="guardian.options">{live?live.guards.map(guard=><SettingsGuardRow
      key={guard.id} testID={`guardian.option.${guard.id}`} look={guard.id} title={guard.title} subtitle={guard.subtitle||undefined}
      selected={look===guard.id} locked={!guard.selectable} onPress={()=>{if(guard.selectable)selectGuarded(guard.id);else{const owner=captureSelectionOwner();upgrade('guards',async isCurrent=>{await select(guard.id,true,owner,isCurrent);});}}}/>):[0,3,7,14,30,60,90,120].map(days=><SettingsGuardRow
      key={days} look="original" title={days?localizedFormat('Use Lava %d days',days):localized('Original')} subtitle={days?localizedFormat('Currently at: %d days',0):undefined}
      selected={days===0} locked={days>0} onPress={()=>{if(days)upgrade('guards');}}/>)}</SettingsGroup>
    {Platform.OS!=='android'&&<SettingsSurface><Toggle title="Match App Icon to Lava Guard" value={session.matchIcon} disabled={changingIcon} onChange={matchIcon}/></SettingsSurface>}
    {!live?.plus?.enabled&&<>
      <QuietFooter note="Keep Lava protecting you to unlock more Guards, or upgrade to unlock them all." title="Upgrade" onPress={()=>upgrade('guards')}/>
      <QuietFooter note="Lava Guard progress requires local logs." title="Review Privacy & Data" onPress={()=>nav.navigate('Privacy')}/>
    </>}
  </Sheet>;
}

export {DNSScreen,DNSPickerScreen} from './DNSScreen';

export function PrivacyScreen() {
  const {session,setSession,app,live} = useReview(); const run=useAppAction(); const [showDelete,setShowDelete] = useState(false);
  const {exportLogs,exportAllLogs,exportBusy,exportError,clearAllLogs,clearBusy}=useLocalLogActions();
  const disableLogs:Record<string,{title:string;message:string;action:string}>={
    'Filtering Counts':{title:'Turn off local filtering counts?',message:'Saved filtering counts will be cleared and new allowed, blocked, and local protection uptime counts will not be saved.',action:'Turn Off and Clear Counts'},
    'Domain logs':{title:'Turn off local domain history?',message:'Saved domain names will be cleared and new domain names will not be saved.',action:'Turn Off and Clear History'},
    'Network activity':{title:'Turn off local network activity?',message:'Saved network activity entries will be cleared and new network activity entries will not be saved.',action:'Turn Off and Clear Activity'},
    'Lava Guard Progress':{title:'Turn off Lava Guard progress?',message:'Saved Lava Guard progress will be cleared and new Lava Guard progress will not be saved.',action:'Turn Off and Clear Progress'},
  };
  const setLog=(title:string,next:boolean)=>{
    const apply=()=>app ? run({type:'settings.set',key:`logs.${title}`,value:next}) : setSession({...session,logs:{...session.logs,[title]:next},sudoku:title==='Lava Guard Progress'&&!next?undefined:session.sudoku});
    const confirmation=live?.confirmations?.[`disable.${title}`]??disableLogs[title];
    if(!next&&confirmation) return new Promise<void>(resolve=>Alert.alert(confirmation.title,confirmation.message,[
      {text:'Cancel',style:'cancel',onPress:resolve},{text:confirmation.action,style:'destructive',onPress:()=>{void Promise.resolve(apply()).finally(resolve);}},
    ],{onDismiss:resolve}));
    return apply();
  };
  const clearProgress=()=>Alert.alert('Clear Lava Guard progress?',
    'This removes unearned Lava Guard progress from this device. Earned Lava Guards stay unlocked.',[
      {text:'Cancel',style:'cancel'},{text:'Clear Progress',style:'destructive',onPress:()=>{
        if(app) run({type:'logs.clear',kind:'Clear Lava Guard progress'});
        else {setSession({...session,sudoku:undefined});AccessibilityInfo.announceForAccessibility(localized('Lava Guard progress cleared.'));}
      }},
    ]);
  const clearLog=(kind:string)=>{
    if(kind==='Clear all logs'){clearAllLogs();return;}
    const contract=live?.confirmations?.[kind];
    if(contract){Alert.alert(contract.title,contract.message,[{text:'Cancel',style:'cancel'},{text:contract.action,style:'destructive',onPress:()=>run({type:'logs.clear',kind})}]);return;}
    if(kind==='Clear Lava Guard progress'){clearProgress();return;}
    previewNotice();
  };
  return <Screen>
    <SettingsIntro summary="Domain history and network activity stay on this device for 7 days. Counts and Guard progress last longer."/>
    <SettingsGroup title="Local Logs">{logNames.map(title=><Toggle key={title} title={title} value={session.logs[title]===true} optimistic={false} onChange={next=>setLog(title,next)}/>)}</SettingsGroup>
    <SettingsSurface footer="Anyone with an exported ZIP can read its contents. Full logs include domain history.">
      <ListRow action icon="square.and.arrow.up" title="Export diagnostics" accessibilityHint="Exports diagnostics and settings without domain history." disabled={exportBusy} onPress={exportLogs}/>
      <ListRow action icon="square.and.arrow.up" title="Export all logs" accessibilityHint="Includes all retained domain history." disabled={exportBusy} onPress={exportAllLogs}/>
    </SettingsSurface>
    {!!exportError&&<SettingsMessage warning>{exportError}</SettingsMessage>}
    <SettingsDisclosure title="Delete Local Logs" icon="trash" testID="privacy.delete-options" expanded={showDelete} onChange={setShowDelete}>
      <SettingsSurface>{['Clear filtering counts','Clear domain history','Clear network activity','Clear Lava Guard progress'].map(title=><ListRow key={title} action title={title} color={colors.errorText} icon="trash" onPress={()=>clearLog(title)}/>)}
      <ListRow action title="Clear all logs" color={colors.errorText} icon="trash" disabled={clearBusy} onPress={()=>clearLog('Clear all logs')}/></SettingsSurface>
    </SettingsDisclosure>
  </Screen>;
}

export function SecurityScreen() {
  const pending=useRef(false);const [updating,setUpdating]=useState(false);
  const nav = useReviewNavigation(); const {session,setSession,app,live} = useReview(); const run=useAppAction();
  const hasMethod=live?.security.hasAuthenticationMethod??(session.passcode||session.biometrics);
  const setProtection=(title:string,next:boolean)=>{
    if(!hasMethod||pending.current||live?.security.updatingSurface)return;
    if(!app){setSession({...session,protectedActions:{...session.protectedActions,[title]:next}});return;}
    pending.current=true;setUpdating(true);
    return app.command({type:'settings.set',key:`protectedActions.${title}`,value:next}).catch(error=>{if(!(error instanceof AppCommandDetachedError)&&error.message!=='Authentication cancelled.')Alert.alert('Lava',error.message);}).finally(()=>{pending.current=false;setUpdating(false);});
  };
  return <Screen>
    <SettingsIntro summary="Choose which parts of Lava need a passcode or Face ID."/>
    <SettingsGroup title="Authentication method">
      <Toggle title="Passcode" optimistic={false} disabled={live?.security.unavailable} value={session.passcode} onChange={()=>app?run({type:'native.flow',flow:'passcode'}):nav.navigate('Passcode')}/>
      {(!live||live.security.showBiometrics)&&<Toggle title={live?.security.biometricTitle??'Face ID'} optimistic={false} value={session.biometrics} disabled={live?live.security.unavailable||!session.biometrics&&!live.security.canEnableBiometrics:!session.passcode} onChange={value=>setSession({...session,biometrics:value})}/>}
      {!!live?.security.status&&<SettingsInset><SettingsMessage>{live.security.status}</SettingsMessage></SettingsInset>}
    </SettingsGroup>
    <SettingsGroup title="Use authentication for" footer="Choose which actions ask for authentication. All choices start off.">
      {Object.entries(session.protectedActions).map(([title,value])=><Toggle key={title} title={title} value={hasMethod&&value} disabled={!hasMethod} pending={updating||live?.security.updatingSurface} onChange={next=>setProtection(title,next)}/>)}
    </SettingsGroup>
  </Screen>;
}

export function PasscodeScreen() {
  // Never retain credentials in the UI fixture. The keypad lets the reviewer
  // inspect the native input surface; completing it reports the service boundary.
  const [code,setCode] = useState('');
  const insets = useSafeAreaInsets();
  return <PasscodeEntry topInset={insets.top} value={code} onChange={value=>{setCode(value);if(value.length===4){setCode('');previewNotice();}}}/>;
}

export function UpgradeScreen() {
  const route=useRoute<RouteProp<ReviewRoutes,'Upgrade'>>();const nav=useReviewNavigation();
  const {app,live}=useReview();const focused=useIsFocused();const intents=usePlusIntents();
  const interactive=useSyncExternalStore(app?.subscribe??(()=>()=>{}),()=>mayResumePlusIntent(app));
  const resumed=useRef(false);const current=useRef(focused);current.current=focused;
  // A native purchase/restore presentation can resign the app active while the entitlement flips
  // true. `live.plus.enabled` alone is not a dep that changes when the app later foregrounds, so a
  // pending intent would strand; carry an explicit foreground signal so the effect re-runs and
  // resumes once `mayResumePlusIntent` is satisfied. Unknown/initial state counts as foreground
  // (only an explicit background/inactive is not), matching the shared app-state convention.
  const [appForeground,setAppForeground]=useState(()=>AppState.currentState!=='background'&&AppState.currentState!=='inactive');
  // A retry driver: a refused resume (App Unlock still engaged) releases the once-guard and bumps
  // this counter, forcing the effect to re-evaluate. It is required for the race where the native
  // grant landed while the failed promise was still pending, so the derived authorization dep did
  // not change again after the catch; without it the registered intent would strand.
  const [resumeAttempt,setResumeAttempt]=useState(0);
  const appUnlockGrant=appUnlockAuthorized(live);
  // Bound the retry to one per concrete unlock grant. An unbounded bump re-runs the same refused
  // resume on every rejection — a synchronous setState loop (React "Maximum update depth exceeded").
  // A genuine grant change is carried by `appUnlockGrant`/`unlockGrantKey` in the deps below, so a
  // latch that only re-arms for a different grant cannot strand a later, newly-issued grant.
  const retryGrant=useRef<string|undefined>(undefined);
  const unlockGrantKey=appUnlockGrant?JSON.stringify([live?.security?.readRevision,live?.security?.authenticatedSurfaces??[]]):'locked';
  // A bounded retry identity: any owner/entitlement prerequisite that can change the resume's
  // outcome (the current-turn grant and the VPN prerequisite) re-arms exactly one retry, so a
  // persistent failure cannot loop while a later prerequisite refresh still retries.
  const resumeRetryKey=`${unlockGrantKey}|${live?.vpn?.needsPlus??false}`;
  // Promise settlement can happen after a newer prerequisite frame committed. Read the current
  // frame in the catch, rather than the frame captured when this particular attempt started.
  const latestResume=useRef({key:resumeRetryKey,live,intents,intent:route.params?.intent});
  latestResume.current={key:resumeRetryKey,live,intents,intent:route.params?.intent};
  useEffect(()=>{const subscription=AppState.addEventListener('change',state=>setAppForeground(state!=='background'&&state!=='inactive'));return()=>subscription.remove();},[]);
  useEffect(()=>{resumed.current=false;retryGrant.current=undefined;},[intents,route.params?.intent]);
  useEffect(()=>{if(!focused||!appForeground||route.params?.embedded||!route.params?.reason||!live?.plus.enabled||!appUnlockGrant||resumed.current||!mayResumePlusIntent(app))return;
    resumed.current=true;const intentId=route.params.intent;const resume=intentId?intents.peek(intentId):undefined;
    if(intentId&&!resume)return;
    void Promise.resolve().then(()=>resume?.()).then(destination=>{
      // Consume the intent only after the resume actually completed, so a rejected resume is not
      // silently destroyed and can be retried (the effect re-runs on the next deps change).
      const owned=!intentId||latestResume.current.intents===intents&&latestResume.current.intent===intentId&&intents.peek(intentId)===resume;
      if(intentId)intents.remove(intentId);
      if(!owned||!current.current||!mayResumePlusIntent(app)){
        if(destination)void app?.retirePlusDestination(destination);
        return;
      }
      if(destination)nav.dispatch(StackActions.replace(destination.name,destination.params));else nav.goBack();
      retryGrant.current=undefined;
    }).catch(error=>{
      if(!current.current||intentId&&(latestResume.current.intents!==intents||latestResume.current.intent!==intentId||intents.peek(intentId)!==resume))return;
      // A retired continuation is not an owned error or a request to replay it.
      if(Platform.OS==='android'&&error instanceof AppCommandDetachedError)return;
      // A transient prerequisite refusal (e.g. the VPN family has not yet republished after the
      // entitlement flip) is not a user cancellation: keep the intent registered and let the next
      // prerequisite change retry it. `resumeRetryKey` carries the VPN prerequisite so the effect
      // re-runs when the refreshed family lands.
      if(error instanceof PlusResumePending){
        resumed.current=false;
        const currentKey=latestResume.current.key;
        // An unchanged prerequisite waits for its next frame. If that frame already arrived
        // while the rejected operation was pending, re-evaluate once against the CURRENT frame.
        if(currentKey!==resumeRetryKey&&retryGrant.current!==currentKey){
          retryGrant.current=currentKey;setResumeAttempt(attempt=>attempt+1);
        }
        return;
      }
      if((error as Error).message==='Authentication cancelled.'){
        // The user cancelled the system auth prompt. Latch the once-guard so it is never
        // auto-retried or replayed; a fresh entry (or source retirement) governs the next attempt.
        return;
      }
      // The resume failed. Release the once-guard so a later deps change can retry instead of
      // stranding a disabled Confirm; a lock refusal additionally forces an immediate re-evaluation.
      resumed.current=false;
      // A non-lock, non-cancel refusal (e.g. a temporarily refused `filter.review` recapture) also
      // schedules exactly one re-render so the registered callback is not stranded. Bounded by the
      // same retry identity, so a persistent failure settles instead of looping.
      const currentKey=latestResume.current.key;
      if(retryGrant.current===currentKey)return;
      retryGrant.current=currentKey;
      if(!retryablePlusResume(error,latestResume.current.live)&&current.current&&mayResumePlusIntent(app))Alert.alert('Lava',(error as Error).message);
      setResumeAttempt(attempt=>attempt+1);
    });
  },[focused,appForeground,interactive,live?.plus.enabled,appUnlockGrant,app,intents,route.params?.embedded,route.params?.reason,route.params?.intent,unlockGrantKey,resumeRetryKey,resumeAttempt]);
  useEffect(()=>()=>{current.current=false;},[]);
  useEffect(()=>()=>{if(route.params?.intent)intents.remove(route.params.intent);},[intents,route.params?.intent]);
  return <LavaPlusPage reason={route.params?.reason}/>;
}

export function LavaPlusPage({reason}:{reason?:PlusReason}) {
  const {app,live,look}=useReview();const {run,busy}=useExclusiveAppAction('purchase',!!live?.plus.busy);
  const focused=useIsFocused();
  useEffect(()=>{if(!app||!focused)return;void app.command({type:'purchase.refresh'}).catch(error=>{if(!(Platform.OS==='android'&&error instanceof AppCommandDetachedError))Alert.alert('Lava',error.message);});return()=>{void app.command({type:'purchase.clearMessage'}).catch(()=>{});};},[app,focused]);
  return <Screen>
    {reason&&<SettingsSurface tone="green"><ListRow title={plusMessages[reason]} testID="plus.context"/></SettingsSurface>}
    <LavaPlusStory/>
    {live?.plus.enabled ? <><SettingsSubscriptionStatus look={live.look} expiration={live.plus.expiration}/><SettingsSurface><ListRow action title="Manage Subscription" icon="creditcard.circle" disabled={busy} onPress={()=>run({type:'purchase.manage'})}/><ListRow action title="Restore Purchase" icon="arrow.clockwise.circle" disabled={busy} onPress={()=>run({type:'purchase.restore'})}/></SettingsSurface></> : live?.plus.checking ? <SettingsLoading title="Checking Lava Security Plus"/> : <SettingsStack><SettingsStack><Copy role="heading">Choose a plan</Copy><Copy color={colors.secondaryText}>... and a pitch for your parent</Copy></SettingsStack>
      {(!app&&([['Yearly','"Paying by the year beats paying by the month."','$29.99'],['Monthly','"We already saved this by unplugging appliances."','$3.99']] as const).map(([title,pitch,price]) => <SettingsSurface key={title}><ListRow action title={title} subtitle={pitch} trailing={<Copy weight="600" color={colors.safeGreen}>{price}</Copy>} onPress={previewNotice} /></SettingsSurface>))}
      {live?.plus.offers.map(offer=><SettingsSurface key={offer.id}><ListRow action title={offer.title} subtitle={offer.subtitle} trailing={<SettingsPrice price={offer.price} commitment={offer.commitmentPrice}/>} disabled={busy} onPress={()=>run({type:"purchase.buy",id:offer.id})}/></SettingsSurface>)}
      <Copy color={colors.secondaryText}>or if you have already made a purchase</Copy><SettingsSurface><ListRow action title="Restore Purchase" icon="arrow.clockwise.circle" disabled={busy} onPress={()=>run({type:"purchase.restore"})} /></SettingsSurface>
    </SettingsStack>}
    <SettingsStack>
      <Quiet>{billingTerms(Platform.OS,live?.plus.showsYearlyPaidMonthly).disclosure}</Quiet>
      <LinkGroup><Link title={billingTerms(Platform.OS).title} onPress={()=>{void Linking.openURL(billingTerms(Platform.OS).url);}} /><Link title="Privacy Policy" onPress={()=>{void Linking.openURL('https://lavasecurity.app/privacy/');}} /></LinkGroup>
    </SettingsStack>
    {!!live?.plus.message&&<Quiet>{live.plus.message}</Quiet>}
  </Screen>;
}
