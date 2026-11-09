import {useContext,useEffect,useRef,useState} from 'react';
import {AppState,Platform,View} from 'react-native';
import {useNavigation} from '@react-navigation/native';
import {LavaActionButton} from '../src';
import {colors} from '../src/colors';
import {foundation} from '../src/foundation';
import {Alert,localized} from '../app/presentation';
import type {AppCommand,FeedbackState,FeedbackPreview} from '../app/contract';
import {mayInteractWithPresentation} from '../app/read-cache';
import {usePresentationAuthority} from '../app/use-presentation-readiness';
import {useReview} from './ReviewContext';
import {AndroidPresentationInteractionContext} from './presentation-owner.android';
import {Copy,Section,Symbol} from './primitives';
import {Group,Info,Link,Toggle,toolbarButton,useToolbar,ListRow} from './scaffold';
import {FlowSheet,FormPanel,FormField,FormDivider,FormSteps,SelectableCode,CharacterCounter,FormIntro,FormParagraph,FormReviewValue,FormCardTitle,DiagnosticValue} from './form-scaffold';

export function FeedbackFlow({id,state,dismissAttempt}:{id:string;state:FeedbackState;dismissAttempt:number}){
  const {app,live}=useReview();const nav=useNavigation();const [fields,setFields]=useState({site:state.site,details:state.details,email:state.email});
  const presentationAuthority=usePresentationAuthority(app);
  const nativeReady=useContext(AndroidPresentationInteractionContext);
  const receipt=Platform.OS!=='android'?true:nativeReady;
  const interactive=!!receipt;
  const [previews,setPreviews]=useState<FeedbackPreview[]|undefined>();const [pending,setPending]=useState(false);
  const busy=state.busy||pending;const submission=useRef(false);const mounted=useRef(true);const attempts=useRef(dismissAttempt);
  const fieldEpochs=useRef({site:0,details:0,email:0});const previewEpoch=useRef(0);const sent=useRef(false);
  const admission=useRef({app,id,interactive,receipt,generation:live?.generation,hostID:live?.nativeHostID,readRevision:live?.security?.readRevision});
  admission.current={app,id,interactive,receipt,generation:live?.generation,hostID:live?.nativeHostID,readRevision:live?.security?.readRevision};
  const canInteract=()=>{
    const current=admission.current,latest=app?.getSnapshot?.().snapshot;
    return mounted.current&&current.app===app&&current.id===id&&current.interactive&&AppState.currentState==='active'&&mayInteractWithPresentation(app)&&
      (typeof app?.getSnapshot!=='function'||!!latest&&current.generation===latest.generation&&current.hostID===latest.nativeHostID&&current.readRevision===latest.security?.readRevision);
  };
  const captureAdmission=()=>{
    const captured=admission.current;
    return()=>canInteract()&&admission.current.receipt===captured.receipt&&admission.current.generation===captured.generation&&
      admission.current.hostID===captured.hostID&&admission.current.readRevision===captured.readRevision;
  };
  const controlsBusy=busy||!interactive;
  const dismissalGuarded=useRef(false);
  const pendingDismiss=useRef<(()=>boolean)|undefined>(undefined);
  const captureVisit=()=>{
    const captured=admission.current;
    return()=>{
      const current=admission.current,latest=app?.getSnapshot?.().snapshot;
      return mounted.current&&current.app===captured.app&&current.id===captured.id&&current.generation===captured.generation&&current.hostID===captured.hostID&&
        (typeof app?.getSnapshot!=='function'||!!latest&&latest.generation===captured.generation&&latest.nativeHostID===captured.hostID&&(!latest.foregroundFlow||latest.foregroundFlow.id===captured.id));
    };
  };
  const [fieldResets,setFieldResets]=useState({site:0,details:0,email:0});
  const currentFields=useRef(fields);
  const acceptedEpochs=useRef({site:0,details:0,email:0});
  const drains=useRef<Partial<Record<keyof typeof fields,Promise<boolean>>>>({});
  const navigating=useRef(false);
  const editing=useRef<keyof typeof fields|undefined>(undefined);
  const pendingBlur=useRef<{field:keyof typeof fields;resolve:()=>void}|undefined>(undefined);
  const focus=(field:keyof typeof fields)=>{editing.current=field;};
  const blur=(field:keyof typeof fields)=>{
    if(editing.current===field)editing.current=undefined;
    if(pendingBlur.current?.field===field){pendingBlur.current.resolve();pendingBlur.current=undefined;}
  };
  const currentState=useRef(state);currentState.current=state;
  const acceptCorrection=(field:keyof typeof fields,value:string,epoch:number,result:FeedbackState)=>{
    if(result&&mounted.current&&canInteract()&&fieldEpochs.current[field]===epoch&&result[field]!==value&&currentFields.current[field]!==result[field]){
      currentFields.current={...currentFields.current,[field]:result[field]};
      setFields(current=>({...current,[field]:result[field]}));
      setFieldResets(current=>({...current,[field]:current[field]+1}));
    }
  };
  const drainField=(field:keyof typeof fields):Promise<boolean>=>{
    const existing=drains.current[field];if(existing)return existing;
    if(acceptedEpochs.current[field]===fieldEpochs.current[field])return Promise.resolve(true);
    // One native write per field may be in flight. Edits that arrive during it
    // replace the local pending value instead of queuing thousands of snapshots.
    const current=captureAdmission();
    const drain=(async()=>{
      while(mounted.current&&app&&current()&&!currentState.current.busy&&!currentState.current.sent){
        const epoch=fieldEpochs.current[field],value=currentFields.current[field];
        let result:FeedbackState;
        try{result=await app.command<FeedbackState>({type:'feedback.change',id,field,value});}catch{return false;}
        if(!result||!mounted.current||!current())return false;
        acceptCorrection(field,value,epoch,result);acceptedEpochs.current[field]=epoch;
        if(fieldEpochs.current[field]===epoch)return true;
      }
      return false;
    })();
    drains.current[field]=drain;
    void drain.finally(()=>{if(drains.current[field]===drain)delete drains.current[field];});
    return drain;
  };
  const run=async(command:AppCommand)=>{const current=captureAdmission();if(command.type!=='foreground.dismiss'&&!current())return;try{return await app?.command(command);}catch(error){if(current())Alert.alert('Lava',(error as Error).message);}};
  const dismiss=()=>{if(!busy)void run({type:'foreground.dismiss',id});};
  const close=()=>{
    if(busy)return false;
    if(!canInteract()){
      if(!previews&&!state.dirty&&!Object.values(fields).some(value=>value.length>0)){dismiss();return true;}
      return false;
    }
    if(previews){setPreviews(undefined);return true;}
    if(state.dirty||Object.values(fields).some(value=>value.length>0)){
      const current=captureAdmission();
      Alert.alert('Discard feedback?','Your feedback draft will be removed.',[
        {text:'Cancel',style:'cancel'},{text:'Discard',style:'destructive',onPress:()=>{if(current())dismiss();}}]);
    }else dismiss();
    return true;
  };
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;++previewEpoch.current;pendingBlur.current?.resolve();pendingBlur.current=undefined;};},[]);
  useEffect(()=>{
    const refresh=()=>{if(AppState.currentState==='active'&&presentationAuthority)void app?.command({type:'feedback.enter',id}).then(()=>{
      // A text event queued behind the bounded entry flush may have crossed a
      // privacy retirement. Retain the local buffer and reconcile it only after
      // native authority returns; no draft is written while concealed.
      if(!mounted.current||!canInteract()||currentState.current.busy||currentState.current.sent)return;
      for(const field of ['site','details','email'] as const)void drainField(field);
    }).catch(()=>{});};
    refresh();const lifecycle=AppState.addEventListener('change',value=>{if(value==='active')refresh();else{++previewEpoch.current;setPreviews(undefined);}});
    return()=>lifecycle.remove();
  },[app,id,live?.security.readRevision,presentationAuthority]);
  useEffect(()=>{
    if(attempts.current!==dismissAttempt){pendingDismiss.current=dismissAttempt>attempts.current?captureVisit():undefined;attempts.current=dismissAttempt;}
    const current=pendingDismiss.current;
    if(!current)return;
    if(!current()){pendingDismiss.current=undefined;return;}
    // Native Back is already consumed. Retain only its same-visit request while
    // covered; a fresh Ready permits confirmation, never automatic discard.
    if(close())pendingDismiss.current=undefined;
  },[dismissAttempt,app,id,live?.generation,live?.nativeHostID,live?.security?.readRevision,presentationAuthority,receipt,busy]);
  useEffect(()=>{if(state.sent&&!sent.current){sent.current=true;nav.setOptions({title:localized('Feedback sent')});}},[state.sent]);
  useToolbar({title:previews?'Information Sent':state.sent?'Feedback sent':'Feedback',headerBackVisible:false,
    unstable_headerLeftItems:()=>state.sent?[]:[toolbarButton(previews?'Back':'Cancel',previews?'chevron.left':'xmark',close,busy||!interactive&&(!!previews||state.dirty||Object.values(fields).some(value=>value.length>0)))],
    unstable_headerRightItems:()=>[]},[busy,interactive,state.dirty,state.sent,!!previews,fields]);
  const change=(field:keyof typeof fields,value:string)=>{
    if(!canInteract())return;
    currentFields.current={...currentFields.current,[field]:value};
    setFields(current=>({...current,[field]:value}));++fieldEpochs.current[field];
    // This visit-bound, metadata-only guard bypasses the diagnostics queue. It
    // cannot authorize a read, change report context, or unlock a sending form.
    if(!dismissalGuarded.current&&app){
      dismissalGuarded.current=true;
      void app.command({type:'foreground.dirty',id,dirty:true}).catch(()=>{dismissalGuarded.current=false;});
    }
    void drainField(field);
  };
  const withCommittedFields=async(operation:()=>Promise<unknown>)=>{
    const current=captureAdmission();if(controlsBusy||!current()||navigating.current)return;navigating.current=true;
    // Every transition that removes an editor must first commit composition
    // and receive its blur. A preview cannot retain focus on an unmounted field.
    const released=editing.current?new Promise<void>(resolve=>{pendingBlur.current={field:editing.current!,resolve};}):undefined;
    setPending(true);
    try{
      await released;
      const accepted=await Promise.all((['site','details','email'] as const).map(drainField));
      if(accepted.every(Boolean)&&mounted.current&&current()&&!currentState.current.busy&&!currentState.current.sent)
        await operation();
    }finally{navigating.current=false;if(mounted.current)setPending(false);}
  };
  const showPreview=async()=>{const current=captureAdmission();if(controlsBusy||!current()||navigating.current)return;const epoch=++previewEpoch.current;try{
    await withCommittedFields(async()=>{
      const result=await app?.command<FeedbackPreview[]>({type:'feedback.preview',id});
      if(result&&mounted.current&&epoch===previewEpoch.current&&current())setPreviews(result);
    });
  }catch(error){if(current())Alert.alert('Lava',(error as Error).message);}};
  const submit=async()=>{const current=captureAdmission();if(!app||controlsBusy||!current()||submission.current)return;submission.current=true;setPending(true);
    try{await app.command({type:'feedback.submit',id,review:state.review});}
    catch(error){if(current())Alert.alert('Lava',(error as Error).message);}
    finally{submission.current=false;if(mounted.current)setPending(false);}
  };
  if(previews)return <FlowSheet feedback>
    <FormIntro summary="These examples show the technical summary Lava can send when you turn on optional diagnostics"/>
    <Section title="Information sent"><View style={{gap:10}}>{previews.length===0?<FormPanel><FormParagraph>Lava will show App & Device, VPN Status, Tunnel Lifecycle, Network & Resolver Health, Filter Snapshot, and Local Activity Summary when a local summary is ready.</FormParagraph></FormPanel>:
      previews.map(section=><FormPanel key={section.id}><View style={{gap:5}}><FormCardTitle>{section.title}</FormCardTitle><FormParagraph>{section.purpose}</FormParagraph></View>
        <FormDivider/><View style={{gap:8}}>{section.items.map(item=><DiagnosticValue key={item.label} label={item.label} value={item.value}/>)}</View></FormPanel>)}</View></Section>
    <Section title="Lifecycle log examples"><FormPanel><View style={{gap:10}}>
      <FormReviewValue label="App" value="enable-begin, enable-finished, reconnect-requested"/>
      <FormReviewValue label="Tunnel" divider dividerGap={10} value="startTunnel-ready, network-path-changed, resolver-reset"/>
      <FormReviewValue label="Details" divider dividerGap={10} value={localized('VPN status, network kind, resolver status, failure counters')}/>
    </View></FormPanel><FormParagraph textRole="note">Lifecycle entries use safe event names and counters. Recent DNS and domain events are not included.</FormParagraph></Section>
  </FlowSheet>;
  if(state.sent)return <FlowSheet centered footer={<LavaActionButton title="Done" onPress={dismiss}/>}>
    <View style={{alignItems:'center',gap:foundation.space.xl}}>
      <Symbol name="checkmark.circle.fill" size={56} pointSize={56}/><Copy role="heading" center accessibilityRole="header">Feedback sent</Copy>
      <Copy role="body" center color={colors.secondaryText}>{state.normalizedEmail?'Thank you, Lava will look into this and reach out if needed':'Thank you, Lava will look into this'}</Copy>
      <View style={{gap:foundation.space.sm,alignItems:'center'}}><Copy role="caption" color={colors.secondaryText}>Report ID:</Copy>
        <SelectableCode>{state.receipt}</SelectableCode>
        <LavaActionButton title={state.copied?'Copied!':'Copy ID'} role="panel" disabled={controlsBusy||!state.receipt} onPress={()=>void run({type:'feedback.copy',id})}/>
      </View>
    </View>
  </FlowSheet>;
  const step=(value?:number)=>withCommittedFields(()=>run(value===undefined?{type:'feedback.step',id,next:true}:{type:'feedback.step',id,step:value}));
  const footer=<View style={{flexDirection:'row',gap:12}}>
    {state.step>0&&<View style={{flex:1}}><LavaActionButton title="Back" role="secondary" disabled={controlsBusy} onPress={()=>step(state.step-1)}/></View>}
    <View style={{flex:1}}><LavaActionButton title={state.step===0?'Continue':state.step===1?'Review':state.error?'Retry':busy?'Submitting':'Submit'}
      disabled={controlsBusy||(state.step===0?!state.topic:!state.canContinue)||(state.step===2&&!state.prepared)}
      onPress={state.step===2?()=>void submit():()=>void step()}/></View>
  </View>;
  return <FlowSheet feedback footer={footer}><View testID="feedback.flow" style={{gap:18}}>
    <FormSteps titles={['Topic','Details','Review']} current={state.step} furthest={state.furthest} onSelect={step} disabled={controlsBusy}/>
    {state.step===0&&<><FormIntro summary="Lava only sends feedback after you review it and tap Submit"/>
      <Section title="Choose a topic"><Group>{state.topics.map(topic=><ListRow key={topic.id} verbatimTitle title={topic.title} selected={state.topic===topic.id} disabled={controlsBusy}
        onPress={()=>{if(!canInteract())return;if(topic.id!=='websiteAccess'){currentFields.current={...currentFields.current,site:''};++fieldEpochs.current.site;setFields(current=>({...current,site:''}));}void run({type:'feedback.topic',id,topic:topic.id});}}/>)}</Group></Section></>}
    {state.step===1&&<><Section title="Tell us more"><View style={{gap:10}}><FormPanel>
      {state.topic==='websiteAccess'&&<><FormField title="Site or domain" placeholder="Site or domain" value={fields.site} resetRevision={fieldResets.site} characterLimit={300} onChangeText={value=>change('site',value)} onFocus={()=>focus('site')} onBlur={()=>blur('site')} keyboardType="url" editable={!controlsBusy}/><FormDivider/></>}
      <View style={{gap:4}}><FormField title="Details" placeholder="What were you trying to do? What did Lava do instead?" multiline grows value={fields.details} resetRevision={fieldResets.details} characterLimit={5000} onChangeText={value=>change('details',value)} onFocus={()=>focus('details')} onBlur={()=>blur('details')} editable={!controlsBusy} minHeight={96} style={{textAlignVertical:'top'}}/>
        <CharacterCounter count={state.count} limit={5000}/></View>
      <FormDivider/><FormField title="Email for follow-up (optional)" placeholder="Email for follow-up (optional)" keyboardType="email-address" value={fields.email} resetRevision={fieldResets.email} characterLimit={320} onChangeText={value=>change('email',value)} onFocus={()=>focus('email')} onBlur={()=>blur('email')} editable={!controlsBusy}/>
    </FormPanel><Toggle title="Include optional diagnostic" titleRole="cardTitle" standalone value={state.diagnostics} disabled={controlsBusy} onChange={value=>void run({type:'feedback.diagnostics',id,value})}/></View></Section>
      <View style={{gap:foundation.space.explanationToLink}}><FormParagraph textRole="note">Optional diagnostics include anonymized Lava Data like VPN status, network logs, and filter snapshot. They help the Lava team better investigate what went wrong.</FormParagraph><Link title="See what information is sent" onPress={()=>void showPreview()} footer/></View></>}
    {state.step===2&&<Section title="Review and submit"><FormPanel>
      {[['Topic',state.topics.find(topic=>topic.id===state.topic)?.title??localized('Not selected')],...(state.topic==='websiteAccess'?[['Site or domain',state.normalizedSite]]:[]),
        ['Details',state.normalizedDetails||localized('Not provided')],['Email',state.normalizedEmail||localized('Not provided')],['Diagnostics',localized(state.diagnostics?'Sent':'Not sent')]].map(([label,value],index)=><FormReviewValue key={label} label={label!} value={value!} divider={index>0}/>)}
    </FormPanel></Section>}
    {!!state.error&&<Info title="Could not send feedback" description={state.error} icon="exclamationmark.triangle.fill"/>}
  </View></FlowSheet>;
}
