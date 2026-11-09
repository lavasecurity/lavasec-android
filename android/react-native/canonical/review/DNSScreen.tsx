import {useEffect,useRef,useState} from 'react';
import {AppState,Linking,Platform} from 'react-native';
import {useIsFocused,useRoute,type RouteProp} from '@react-navigation/native';
import {usePlusEntry} from './plus-entry';
import {Alert,localized} from '../app/presentation';
import type {DNSChoice} from '../app/contract';
import {translations} from '../app/translations';
import {CANONICAL_LOCALE,resolveLavaLocale} from '../../src/i18n/locales';
import {LavaActionButton,LavaIconButton,LavaToggleControl} from '../src';
import {Screen,Section,Symbol} from './primitives';
import {colors} from '../src/colors';
import {AccessorySlot,AddAction,Group,ListRow,Quiet,toolbarButton,useToolbar} from './scaffold';
import {OrderedListAction,useSettingsEditToolbar,SettingsIntro,SettingsInset,SettingsSurface,SettingsGlyph} from './settings-scaffold';
import {CatalogSheet} from './story-scaffold';
import {useReview} from './ReviewContext';
import {useReviewNavigation,type ReviewRoutes} from './navigation';
import {isCustomDNS,selection,sameDNS,useDNSEditor} from './dns-editor';

const report=(error:unknown)=>{if((error as Error).message!=='Authentication cancelled.')Alert.alert('Lava',(error as Error).message);};
const openSettings=()=>{void Linking.openSettings().catch(report);};
const dnsRowMetadata=(choice?:DNSChoice)=>[choice?.transport,choice?.metadata].filter(Boolean).join(' · ');
// Editable names stay raw. Only the native owner can mark a nonempty label as app copy.
const dnsChoiceName=(choice:DNSChoice,locale:string)=>{
  const unnamed=isCustomDNS(choice)&&!choice.name.trim();
  const name=unnamed?'Custom DNS':choice.name;
  const table=translations[CANONICAL_LOCALE[resolveLavaLocale(locale)]]??{};
  return choice.nameIsDefault===true||unnamed?table[name]??name:name;
};
export function DNSScreen(){
  const nav=useReviewNavigation();const {app,live}=useReview();const {draft,setDraft}=useDNSEditor();
  const retireDraft=useRef(setDraft);retireDraft.current=setDraft;
  const [{editing,busy},setMode]=useState({editing:false,busy:false});const pending=useRef(false);
  const setBusy=(busy:boolean)=>setMode(mode=>({...mode,busy}));
  const setEditing=(editing:boolean)=>setMode(mode=>({...mode,editing}));
  const editable=live?.dns.editable!==false;const canEdit=editable;const tiers=editing?draft.tiers:live?.dns.tiers??[];
  useEffect(()=>{if(!editable){setEditing(false);setDraft({tiers:[],context:''});}},[editable]);
  const systemDNS=editing?draft.systemDNS:live?.dnsPatch?.provider;
  const systemChanged=editing&&(draft.systemDNS?.id??null)!==(draft.systemDNSOriginalID??null);
  useEffect(()=>()=>retireDraft.current({tiers:[],context:''}),[]);
  const edit=()=>{setDraft({tiers:live?.dns.tiers??[],context:live?.dns.tiersContext??'',systemDNS:live?.dnsPatch?.provider??null,systemDNSOriginalID:live?.dnsPatch?.provider?.id??null});setEditing(true);};
  const cancel=()=>{const discard=()=>{setEditing(false);setDraft({tiers:[],context:''});};
    if(systemChanged||JSON.stringify(draft.tiers)!==JSON.stringify(live?.dns.tiers))Alert.alert('Discard changes?','Your saved DNS settings will stay active.',[{text:'Cancel',style:'cancel'},{text:'Discard',style:'destructive',onPress:discard}]);else discard();};
  const commit=async()=>{if(!app||pending.current)return;pending.current=true;setBusy(true);
    try{
      if(systemChanged){
        if((live?.dnsPatch?.provider?.id??null)!==(draft.systemDNSOriginalID??null))throw new Error('System DNS changed. Reopen the editor before saving.');
        await app.command(draft.systemDNS?{type:'settings.set',key:'dnsPatchProvider',value:draft.systemDNS.id}:{type:'settings.set',key:'dnsPatchRemove',value:true});
        // A later tier-save failure must not pretend the completed system operation is
        // still pending. Keep the remaining tier draft retryable against actual state.
        setDraft({...draft,systemDNSOriginalID:draft.systemDNS?.id??null});
      }
      if(editable&&JSON.stringify(draft.tiers.map(selection))!==JSON.stringify((live?.dns.tiers??[]).map(selection)))await app.command({type:'dns.tiers',context:draft.context,tiers:draft.tiers.map(selection)});
      setMode({editing:false,busy:false});
    }catch(e){report(e);setBusy(false);}finally{pending.current=false;}};
  const save=()=>{if(pending.current)return;
    if(systemChanged&&!draft.systemDNS)Alert.alert('Remove DNS profile?','Save your DNS changes and remove Lava’s System DNS profile?',[
      {text:'Cancel',style:'cancel'},{text:'Remove DNS profile',style:'destructive',onPress:()=>void commit()},
    ]);else void commit();};
  useSettingsEditToolbar({editing,busy,canEdit:editable&&canEdit&&!!app,saveDisabled:!tiers.length,
    onEdit:edit,onCancel:cancel,onSave:()=>void save()});
  const toggleTier=async(index:number,value:boolean)=>{
    if(!app||pending.current||editing)return;
    pending.current=true;
    try{await app.command({type:'dns.toggle',context:live?.dns.tiersContext??'',index,value});}
    catch(error){report(error);}finally{pending.current=false;}
  };
  const removeTier=(index:number)=>{
    const remaining=tiers.filter((_,i)=>i!==index);
    setDraft({...draft,tiers:remaining.some(row=>row.isEnabled!==false)?remaining:remaining.map(row=>({...row,isEnabled:true}))});
  };
  const command=async(key:string)=>{if(!app||pending.current)return false;pending.current=true;setBusy(true);
    try{await app.command({type:'settings.set',key,value:true});return true;}catch(e){report(e);return false;}finally{pending.current=false;setBusy(false);}};
  const state=live?.dnsPatch?.state;const installed=state==='enabled'||state==='disabled';const enabled=state==='enabled';
  // Readback, independently of catalog matching, proves profile removal is available.
  const canUninstall=!!systemDNS;
  const checking=state==='checking'||!state;
  const profileBusy=busy||live?.dnsPatch?.busy||checking;
  const profileTitle=checking?'Checking profile…':state==='error'?'Unable to check profile':enabled?'Profile installed and selected':'Select Lava DNS profile';
  const profileNote=checking||enabled?undefined:state==='error'?'Tap to check again.':
    'In the Settings app, go to General → VPN & Device Management → DNS. Select “Lava Security”.';
  const profileIcon=enabled?'checkmark.circle.fill':'gearshape';
  const selectProfile=async()=>{
    if(state==='error')await command('dnsPatchCheck');
    else if(installed)openSettings();
    else if(state==='different'||state==='absent'){if(await command('dnsPatchSetup'))openSettings();}
  };
  const uninstallProfile=()=>{if(!app||pending.current||profileBusy)return;
    Alert.alert('Remove DNS profile?','Remove Lava’s System DNS profile?',[{text:'Cancel',style:'cancel'},{text:'Remove DNS profile',style:'destructive',onPress:()=>void command('dnsPatchRemove')}]);};
  return <Screen><SettingsIntro summary={editable?'Choose who looks up website addresses for your device.':live?.dns.unavailableDetail??'With fallback off, VPN chaining uses DNS from its WireGuard configuration.'}
    action={!editable?{title:'Review VPN chaining',onPress:()=>nav.navigate('VPNChaining')}:undefined}/>
    {editable&&<><Section title="Resolution tiers in Lava" footer="Lava tries the primary DNS first, then the fallback DNS if needed."><Group footer={editing&&editable?<OrderedListAction count={tiers.length} addTitle="Add fallback DNS" disabled={busy}
      onAdd={()=>nav.navigate('DNSPicker',{target:'tier',index:tiers.length})} onSwap={()=>setDraft({...draft,tiers:[...tiers].reverse()})}/>:undefined}>
      {tiers.map((tier,index)=><ListRow key={index} verbatimTitle title={dnsChoiceName(tier,live?.presentation?.locale??'en')} metadata={dnsRowMetadata(tier)}
        leading={<Symbol name={`${index+1}.circle`} tone="primary"/>} disabled={busy}
        onPress={editing&&editable?()=>nav.navigate('DNSPicker',{target:'tier',index}):undefined}
        trailing={<AccessorySlot switchable>{editing?(editable&&tiers.length>1?<LavaIconButton title="Remove" item={tier.name} icon="remove" role="destructive" onPress={()=>removeTier(index)}/>:null)
          :<LavaToggleControl verbatimTitle title={dnsChoiceName(tier,live?.presentation?.locale??'en')} testID={`dns.tier-toggle.${index}`} value={tier.isEnabled!==false} disabled={busy||(tier.isEnabled!==false&&tiers.filter(row=>row.isEnabled!==false).length===1)} onValueChange={value=>toggleTier(index,value)}/>}</AccessorySlot>}/>)}
    </Group></Section>
    {live?.dnsPatch?.available&&<Section title="System DNS">
      <Group footer={editing&&!systemDNS?<SettingsInset><AddAction title="Add System DNS" onPress={()=>nav.navigate('DNSPicker',{target:'profile'})}/></SettingsInset>:undefined}>
        {systemDNS?<ListRow verbatimTitle title={dnsChoiceName(systemDNS,live?.presentation?.locale??'en')} metadata={dnsRowMetadata(systemDNS)}
          leading={<Symbol name="s.circle" tone="primary"/>} disabled={profileBusy}
          onPress={editing?()=>nav.navigate('DNSPicker',{target:'profile'}):undefined}
          trailing={<AccessorySlot>{editing?<LavaIconButton title="Remove" item="System DNS" icon="remove" role="destructive" disabled={profileBusy} onPress={()=>setDraft({...draft,systemDNS:null})}/>:null}</AccessorySlot>}/>
          :<ListRow title={!editing&&checking?'Checking System DNS…':!editing&&state==='error'?'Unable to check System DNS':'System DNS not configured'} leading={<Symbol name="s.circle" tone="primary"/>}/>}
      </Group>
      {!editing&&systemDNS&&<SettingsSurface tone={enabled?'green':'neutral'} footer={profileNote}>
        <ListRow title={profileTitle} leading={profileBusy?<SettingsGlyph name={profileIcon} busy/>:<Symbol name={profileIcon} tone={enabled?'white':'primary'}/>} action disabled={profileBusy}
          onPress={selectProfile}/>
      </SettingsSurface>}
      <Quiet>This profile handles system DNS requests and helps Lava filter with Connectivity Assist.</Quiet>
      {!editing&&canUninstall&&<SettingsSurface><ListRow action title="Uninstall profile" icon="trash" color={colors.errorText} testID="dns.profile.uninstall" disabled={profileBusy||!app} onPress={uninstallProfile}/></SettingsSurface>}
    </Section>}
    </>}
  </Screen>;
}

export function DNSPickerScreen(){
  const upgrade=usePlusEntry();
  const nav=useReviewNavigation();const route=useRoute<RouteProp<ReviewRoutes,'DNSPicker'>>();const {target,index=0}=route.params;
  const {app,live}=useReview();const {draft,setDraft,customPickerDraft,beginCustomPicker,cancelCustomPicker}=useDNSEditor();const focused=useIsFocused();
  const [search,setSearch]=useState('');const [busy,setBusy]=useState(false);const pending=useRef(false);
  const [selected,setSelected]=useState<DNSChoice|undefined>(target==='profile'?(draft.systemDNS??undefined):draft.tiers[index]);
  const [customToken,setCustomToken]=useState<string>();
  const mounted=useRef(true);const openAttempt=useRef(0);const focusRef=useRef(focused);focusRef.current=focused;
  const liveRef=useRef(live);liveRef.current=live;const tokenRef=useRef(customToken);tokenRef.current=customToken;
  const appRef=useRef(app);appRef.current=app;
  // A contextual Plus return for `openCustom` runs while this route is blurred behind Upgrade.
  // Returning focus here, backgrounding or unmounting before its `dns.customDraft` settles means
  // the user abandoned the Upgrade route, so the late result must be retired rather than adopted.
  const resumeEpoch=useRef(0);const priorFocus=useRef(focused);
  // Retire both native picker ownership and the local token so a fresh entry or Save Selection
  // is never blocked by a draft no editor owns.
  const retireCustomToken=(token?:string)=>{
    if(!token)return;
    cancelCustomPicker(token);
    void appRef.current?.command({type:'customEntry.dismiss',id:token}).catch(()=>{});
    if(tokenRef.current===token)setCustomToken(undefined);
  };
  useEffect(()=>{mounted.current=true;const listener=AppState.addEventListener('change',state=>{if(state!=='active'){openAttempt.current++;resumeEpoch.current++;}});
    return()=>{mounted.current=false;openAttempt.current++;resumeEpoch.current++;if(tokenRef.current)retireCustomToken(tokenRef.current);listener.remove();};},[]);
  useEffect(()=>{if(!focused)openAttempt.current++;else if(!priorFocus.current)resumeEpoch.current++;priorFocus.current=focused;},[focused]);
  const [custom,setCustom]=useState<DNSChoice|undefined>(selected&&isCustomDNS(selected)?selected:undefined);
  useEffect(()=>{
    if(Platform.OS!=='android'){
      if(customToken&&live?.dns.customDraftToken===customToken&&live.dns.customDraft){setCustom(live.dns.customDraft);setSelected(live.dns.customDraft);setCustomToken(undefined);}return;
    }
    if(!focused||!customToken)return;
    if(customPickerDraft?.token!==customToken){retireCustomToken(customToken);return;}
    if(customPickerDraft.context!==draft.context||customPickerDraft.context!==live?.dns.tiersContext){retireCustomToken(customToken);return;}
    // The native seed hydrates the form only. A successful token-owned validation is the
    // sole way a completed choice returns to this picker, still without changing saved tiers.
    // A staged choice outlives the native draft (CustomEntry dismisses it as it hands the choice
    // back), so adopt it BEFORE reconciling against the live native owner.
    if(customPickerDraft.choice){setCustom(customPickerDraft.choice);setSelected(customPickerDraft.choice);retireCustomToken(customToken);return;}
    // A contextual Upgrade resume installs the local picker token while this route is still blurred.
    // If the user abandons Upgrade, UpgradeScreen retires only the NATIVE custom-entry draft (a
    // result it no longer considers current) and never `cancelCustomPicker`, so the picker can stay
    // locally token-owned with "Save Selection" disabled forever. With no staged choice, a local
    // token whose native draft is gone is dead and must be retired (clearing local ownership too).
    if(live?.dns.customDraftToken!==customToken)retireCustomToken(customToken);
  },[focused,customToken,customPickerDraft,draft.context,live?.dns.tiersContext,live?.dns.customDraftToken,live?.dns.customDraft]);
  useEffect(()=>()=>{if(tokenRef.current)retireCustomToken(tokenRef.current);},[]);
  const offered=target==='profile'?live?.dnsPatch?.choices??[]:[...(live?.dns.choices??[]),...(custom?[custom]:[])];
  const choices=offered.filter((choice,index)=>offered.findIndex(other=>sameDNS(other,choice))===index);
  const query=search.trim().toLocaleLowerCase();
  const sections=(target==='profile'?['DoT','DoH']:['Device','DoH','DoT','IP','DoQ']).map(title=>({title,items:choices.filter(choice=>choice.transport===title&&
    [choice.name,dnsChoiceName(choice,live?.presentation?.locale??'en'),title,localized(title)].some(value=>value.toLocaleLowerCase().includes(query)))})).filter(section=>section.items.length);
  const duplicate=target==='tier'&&!!selected&&draft.tiers.some((choice,i)=>i!==index&&sameDNS(choice,selected));
  const save=async()=>{if(!focusRef.current||tokenRef.current||!selected||duplicate||pending.current)return;pending.current=true;setBusy(true);
    try{if(target==='profile'){setDraft({...draft,systemDNS:selected});}
      else {const tiers=[...draft.tiers];tiers[index]={...selected,isEnabled:draft.tiers[index]?.isEnabled!==false};setDraft({...draft,tiers});}nav.goBack();
    }catch(e){report(e);}finally{pending.current=false;setBusy(false);}};
  const openCustom=()=>{
    if(tokenRef.current)retireCustomToken(tokenRef.current);
    if(!live?.limits.allowsCustomDNS){upgrade('customDNS',async()=>{
      if(!app)return;
      const sourceContext=draft.context;
      if(Platform.OS==='android'&&sourceContext!==liveRef.current?.dns.tiersContext)throw new Error('DNS settings changed. Reopen the editor before saving.');
      const resume=resumeEpoch.current;
      const id=await app.command<string>({type:'dns.customDraft',choice:custom});
      // The contextual return runs with this picker blurred behind Upgrade. If the user left
      // that route (back, background, unmount) or the owner/context rotated while the native
      // draft opened, retire it instead of adopting a picker the user abandoned; only a live
      // verified return opens the editor and proceeds exactly once.
      if(!mounted.current||AppState.currentState!=='active'||resumeEpoch.current!==resume||
        sourceContext!==liveRef.current?.dns.tiersContext){
        retireCustomToken(id);return;
      }
      if(Platform.OS==='android')beginCustomPicker(id,sourceContext);
      setCustomToken(id);
      return {name:'CustomEntry',params:{id,kind:'dns'}};
    });return;}
    if(!app||pending.current)return;
    if(Platform.OS==='android'&&draft.context!==live.dns.tiersContext){report(new Error('DNS settings changed. Reopen the editor before saving.'));return;}
    const started=++openAttempt.current;const sourceContext=draft.context;pending.current=true;setBusy(true);
    void app.command<string>({type:'dns.customDraft',choice:custom}).then(id=>{
      if(!mounted.current||!focusRef.current||started!==openAttempt.current||AppState.currentState!=='active'||
          Platform.OS==='android'&&sourceContext!==liveRef.current?.dns.tiersContext){
        void app.command({type:'customEntry.dismiss',id}).catch(()=>{});return;
      }
      if(Platform.OS==='android')beginCustomPicker(id,sourceContext);setCustomToken(id);nav.navigate('CustomEntry',{id,kind:'dns'});
    }).catch(error=>{if(mounted.current&&focusRef.current&&started===openAttempt.current)report(error);})
      .finally(()=>{pending.current=false;if(mounted.current)setBusy(false);});
  };
  useToolbar({title:'Choose DNS',unstable_headerRightItems:()=>target==='tier'?[toolbarButton('Add custom DNS','plus',openCustom,busy)]:[],
  },[target,busy,draft,custom,live?.limits,selected]);
  return <CatalogSheet sections={sections} categoryTitles={(target==='profile'?['DoT','DoH']:['Device','DoH','DoT','IP','DoQ']).filter(title=>choices.some(choice=>choice.transport===title))} search={search} onSearch={setSearch} searchLabel="Search DNS providers or transports"
    renderRow={choice=><ListRow key={`${choice.id}|${choice.primary}|${choice.secondary}`} verbatimTitle title={dnsChoiceName(choice,live?.presentation?.locale??'en')} metadata={choice.metadata} selected={!!selected&&sameDNS(choice,selected)} disabled={busy||target==='tier'&&draft.tiers.some((row,i)=>i!==index&&sameDNS(row,choice))} onPress={()=>setSelected(choice)}/>}
    footer={<LavaActionButton title="Save Selection" disabled={!focused||!!customToken||!selected||duplicate||busy} onPress={()=>void save()}/>}
    empty={<Group><ListRow title="No DNS providers found"/></Group>}/>;
}
