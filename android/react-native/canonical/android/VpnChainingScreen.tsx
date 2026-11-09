import {useEffect,useRef,useState} from 'react';
import {Alert} from '../app/presentation';
import type {AppSnapshot,VPNDraft} from '../app/contract';
import {LavaIconButton,LavaToggleControl} from '../src';
import {AccessorySlot,Group,ListRow,Quiet,QuietFooter,Toggle} from '../review/scaffold';
import {OrderedListAction,SettingsIntro,useSettingsEditToolbar} from '../review/settings-scaffold';
import {Screen,Section,Symbol} from '../review/primitives';
import {useReview} from '../review/ReviewContext';
import {useReviewNavigation} from '../review/navigation';
import {usePlusEntry} from '../review/plus-entry';
import {PlusResumePending} from '../review/plus-intents';

/** The projected canonical `vpn` snapshot family (omitted when no live chained owner exists). */
type Vpn = NonNullable<AppSnapshot['vpn']>;

// Per-visit draft ids stay unique within a JS session so a late command for a superseded visit
// cannot mutate the current native draft.
let vpnVisitSerial = 0;

/** Ordered VPN editor. Native sheets own configuration text and credential staging; this
 * screen receives only public row metadata and generation/revision fenced draft identities.
 */
export function VpnChainingScreen() {
  const upgrade=usePlusEntry();
  const {app,live}=useReview();
  const nav=useReviewNavigation();
  const vpn=live?.vpn;
  const [{draft,busy},setMode]=useState<{draft?:VPNDraft;busy:boolean}>({busy:false});
  const draftRef=useRef<VPNDraft|undefined>(undefined);
  const pending=useRef(false);
  const opening=useRef<Promise<string>|undefined>(undefined);
  const mounted=useRef(true);
  const togglePending=useRef(false);
  const editing=!!draft;
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;const id=draftRef.current?.id;draftRef.current=undefined;
    if(id)void app?.command({type:'vpn.cancel',id}).catch(()=>{});};},[app]);
  // Prefer the live draft metadata the owner emitted (it can advance a revision without a command);
  // an out-of-order snapshot never regresses the display past what the screen already staged.
  const nativeDraft=draft&&vpn?.draft?.id===draft.id&&vpn.draft.revision>=draft.revision?vpn.draft:draft;
  const latestDraft=useRef(nativeDraft);latestDraft.current=nativeDraft;
  const rows=nativeDraft?.rows??vpn?.rows??[];
  // Only the live owner's confirmed fact may gate retirement/access. A background snapshot
  // redacts `plus.enabled` to false and omits the `vpn` family, so neither is an entitlement
  // loss; treating the redaction as one would cancel a draft (and an in-flight `vpn.begin`
  // document import) on every foreground transition, including the picker that stops the activity.
  const needsPlus=!!vpn?.needsPlus;
  // Renewal must not revive a begin request that crossed an entitlement loss.
  const access=useRef({needsPlus,epoch:0});
  if(access.current.needsPlus!==needsPlus)access.current={needsPlus,epoch:access.current.epoch+1};
  // Keep the prerequisite visible, but don't disclose paid setup until Plus is
  // confirmed. A saved ON preference can survive an expired subscription.
  const setup=!!vpn?.setup&&!needsPlus;
  useEffect(()=>{
    const id=draftRef.current?.id;if(!needsPlus||!id)return;
    draftRef.current=undefined;setMode({busy:false});
    void app?.command({type:'vpn.cancel',id}).catch(()=>{});
  },[app,needsPlus]);
  const enabled=!!vpn?.enabled;
  const canChangeFallback=!!vpn?.canChangeFallback;
  const locked=busy||!!vpn?.busy;
  const report=(error:unknown)=>{if((error as Error).message!=='Authentication cancelled.'&&!(error instanceof PlusResumePending))Alert.alert('Lava',(error as Error).message);};
  const stage=(value:VPNDraft,begin=false)=>{
    if(!mounted.current||access.current.needsPlus||!begin&&draftRef.current?.id!==value.id)return;
    draftRef.current=value;setMode(mode=>({...mode,draft:value}));
  };
  const ensureDraft=():Promise<string>=>{
    // A Plus-entitlement refusal here is a TRANSIENT prerequisite: the owner publishes
    // `plus.enabled` and rebuilds the `vpn` family from independent collectors, so a resume that
    // observes the old `needsPlus` must stay registered and retry when the refreshed family lands.
    // Throwing a distinct pending error (not `Authentication cancelled.`) stops `UpgradeScreen`
    // from latching it as a user cancellation and stranding the paid action.
    if(access.current.needsPlus)return Promise.reject(new PlusResumePending());
    if(opening.current)return opening.current;
    if(draftRef.current)return Promise.resolve(draftRef.current.id);
    if(!app)return Promise.reject(new Error("VPN chaining isn't available. Review its settings."));
    const id=`vpn-${Date.now()}-${++vpnVisitSerial}`;
    const epoch=access.current.epoch;
    // An unconfigured owner imports and commits the first rotation from a document; the snapshot
    // generation is the empty not-configured sentinel. A configured owner only opens the draft and
    // fences it against the committed generation.
    const request=app.command<VPNDraft>({type:'vpn.begin',id,generation:vpn?.generation??''}).then(value=>{
      if(!mounted.current||access.current.needsPlus||access.current.epoch!==epoch){void app.command({type:'vpn.cancel',id}).catch(()=>{});throw new PlusResumePending();}
      stage(value,true);return id;
    }).finally(()=>{if(opening.current===request)opening.current=undefined;});
    opening.current=request;return request;
  };
  const run=async(action:(id:string)=>Promise<void>)=>{if(!app||pending.current||access.current.needsPlus)return;pending.current=true;
    try{await action(await ensureDraft());}catch(error){report(error);}finally{pending.current=false;}};
  const add=()=>{if(!app||pending.current||needsPlus)return;pending.current=true;
    void ensureDraft().then(async id=>{
      // A draft whose only row was removed still exists, so ensureDraft short-circuits and sends no
      // command. Re-add by staging a picked replacement through the same vpn.edit picker.
      const count=draftRef.current?.rows.length??0;
      // vpn.begin already opens the first native sheet on an unconfigured owner.
      // A configured selection appends its second row; an empty staged deletion can add again.
      if(count<2&&(vpn?.setup||count===0))stage(await app.command<VPNDraft>({type:'vpn.edit',id,index:count}));
    }).catch(report).finally(()=>{pending.current=false;});};
  const toggle=async(key:'setup'|'enabled'|'fallback',value:boolean)=>{
    if(!app||togglePending.current||busy)return;
    if(key==='setup'){
      // Android refuses `vpn.toggle setup` for both values (`vpn-unsupported-toggle`): the setup
      // gate is derived from the stored rotation and there is no supported setup-off (a configured
      // rotation is removed through the editor). Import is the only setup path: the `vpn.begin`
      // document import, gated by Plus for a Free user. A setup-off attempt is a no-op (the toggle
      // is also disabled once on), so an entitled user never hits the unsupported command.
      if(!value)return;
      if(needsPlus)upgrade('vpn',async()=>{await ensureDraft();});else void ensureDraft().catch(report);
      return;
    }
    togglePending.current=true;
    try{await app.command({type:'vpn.toggle',key,value});}catch(error){report(error);}
    finally{togglePending.current=false;}
  };
  const open=(index:number)=>{if(vpn?.needsPlus){upgrade('vpn',async()=>{await app!.command({type:'vpn.edit',id:await ensureDraft(),index});});return;}void run(async id=>stage(await app!.command<VPNDraft>({type:'vpn.edit',id,index})));};
  const swap=()=>{if(vpn?.needsPlus){upgrade('vpn');return;}void run(async id=>stage(await app!.command<VPNDraft>({type:'vpn.swap',id})));};
  const toggleRow=async(index:number,value:boolean)=>{
    if(!app||togglePending.current||locked||editing)return;togglePending.current=true;
    try{await app.command({type:'vpn.rowToggle',index,value,generation:vpn?.generation??''});}catch(error){report(error);}
    finally{togglePending.current=false;}
  };
  const remove=(index:number)=>{if(vpn?.needsPlus){upgrade('vpn');return;}void run(async id=>stage(await app!.command<VPNDraft>({type:'vpn.remove',id,index})));};
  const reset=()=>void run(async id=>stage(await app!.command<VPNDraft>({type:'vpn.reset',id})));
  const discard=()=>{if(pending.current)return;const id=draftRef.current?.id;draftRef.current=undefined;setMode({busy:false});
    if(id)void app?.command({type:'vpn.cancel',id}).catch(report);};
  const cancel=()=>{if(nativeDraft?.changed)Alert.alert('Discard changes?','Your saved VPN settings will stay active.',[
    {text:'Cancel',style:'cancel'},{text:'Discard',style:'destructive',onPress:discard}]);else discard();};
  const commit=async()=>{
    if(!app||access.current.needsPlus||pending.current||togglePending.current||!draftRef.current)return;
    // Android commits even a "no changes" visit: the FIRST rotation is committed eagerly by
    // `vpn.begin` and stays pending until `vpn.commit` clears the marker (a cancel would remove
    // it), so short-circuiting on `changed === false` would silently discard a just-imported
    // configuration. `vpn.commit` with nothing staged is a no-op success over the live rotation.
    const committing=latestDraft.current??draftRef.current;draftRef.current=committing;
    pending.current=true;setMode({draft:committing,busy:true});
    try{await app.command({type:'vpn.commit',id:committing.id});draftRef.current=undefined;setMode({busy:false});}
    catch(error){report(error);setMode(mode=>({...mode,busy:false}));}finally{pending.current=false;}
  };
  useSettingsEditToolbar({editing,busy:locked,canEdit:!needsPlus&&!!vpn?.canEdit,
    onEdit:()=>{void ensureDraft().catch(report);},onCancel:cancel,onSave:()=>void commit()});
  if(!app)return null;
  if(!vpn)return <Unavailable title="VPN chaining is not available" detail="This device cannot run VPN chaining yet."/>;
  if(vpn.unavailable)return <Unavailable title="VPN chaining is unavailable" detail="Lava can't read the saved WireGuard configuration right now."/>;
  // The editor is reachable from release UI: the shared Settings/Explore connection stage links
  // here whenever the native chained owner projects the `vpn` family, entitled or not. A Free
  // user keeps the prerequisite visible and taps it into the contextual Plus flow; the paid setup
  // stays hidden behind it.
  return <Screen>
    <SettingsIntro summary="Lava filters first, then sends allowed DNS requests through your WireGuard VPN."/>
    <Section title="Prerequisite" footer="Get a WireGuard config from your VPN provider."><Group>
      <Toggle title="I have a WireGuard configuration" value={setup} disabled={locked||setup} onChange={value=>toggle('setup',value)}/>
    </Group></Section>
    {!needsPlus&&<>
      <Section title="WireGuard configuration">
        <Group testID="vpn.configuration-panel" footer={editing&&vpn.canEdit&&!vpn.needsRepair?
        <OrderedListAction count={rows.length} addTitle="Add configuration" disabled={locked} onAdd={add} onSwap={swap}/>:undefined}>
        {rows.length?rows.map((row,index)=><ListRow key={index} testID={index===0?'vpn.configuration-row':`vpn.configuration-row.${index+1}`} verbatimTitle separateTrailing
          title={row.name} metadata={row.mode}
          leading={<Symbol name={`${index+1}.circle`} tone="primary"/>}
          disabled={locked||!vpn.canEdit}
          onPress={editing?()=>open(index):undefined}
          trailing={<AccessorySlot switchable>{editing
            ?<LavaIconButton title="Remove" item={row.name} icon="remove" role="destructive" disabled={locked} onPress={()=>remove(index)}/>
            :<LavaToggleControl verbatimTitle title={row.name} testID={`vpn.row-toggle.${index}`} value={enabled&&row.isEnabled!==false}
                disabled={locked||!vpn.canEdit} onValueChange={value=>void toggleRow(index,value)}/>}</AccessorySlot>}/>)
          :!editing&&<ListRow action testID="vpn.add-configuration" title="Add configuration" icon="plus" disabled={locked||!vpn.canEdit} onPress={add}/>}
      </Group>
      {editing&&vpn?.needsRepair&&<QuietFooter note="" title="Delete configuration" onPress={reset}/>}
    </Section>
    {rows.length>1&&<Quiet>The order determines which VPN your traffic uses and when it uses both.</Quiet>}
    {(setup||!!nativeDraft)&&<>
      <Section title="VPN chaining"><Group>
        <Toggle title="Chain DNS through my VPN" summary="Only allowed DNS requests use the tunnel." value={enabled}
          disabled={locked||(!enabled&&!vpn.canEnable)||(!!nativeDraft&&!setup)}
          onChange={value=>toggle('enabled',value)}/>
      </Group></Section>
      <Section title="DNS fallback"><Group>
        <Toggle title="Use Lava DNS settings as fallback" value={canChangeFallback&&!!vpn.fallback}
          disabled={locked||!canChangeFallback} onChange={value=>toggle('fallback',value)}/>
      </Group>
        {canChangeFallback?<QuietFooter note="Uses your enabled DNS choices when VPN DNS cannot answer. Known Wi-Fi sign-in pages can use Device DNS when enabled. Full-tunnel VPNs keep DNS inside the VPN." title="Review DNS settings" onPress={()=>nav.navigate('DNS')}/>
          :<Quiet>DNS fallback is unavailable with an active full-tunnel VPN.</Quiet>}
      </Section>
    </>}
    {!!vpn.restriction&&!(rows.length&&nativeDraft?.changed)&&<Quiet>{vpn.restriction}</Quiet>}
    {!!vpn.rotationNote&&<Quiet>{vpn.rotationNote}</Quiet>}
    {!!vpn.error&&<Quiet>{vpn.error}</Quiet>}
    </>}
  </Screen>;
}

function Unavailable({title,detail}:{title:string;detail:string}) {
  return <Screen><Section title="VPN chaining"><Group>
    <ListRow title={title} subtitle={detail} leading={<Symbol name="exclamationmark.triangle" tone="error"/>}/>
  </Group></Section></Screen>;
}
