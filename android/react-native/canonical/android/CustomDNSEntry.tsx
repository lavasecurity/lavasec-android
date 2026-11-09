import {useEffect,useRef,useState} from 'react';
import {Alert,AppState,TextInput,View} from 'react-native';
import {useIsFocused,useRoute,type RouteProp} from '@react-navigation/native';
import type {AppCommand,DNSChoice} from '../app/contract';
import {localized} from '../app/presentation';
import {useTextScale} from '../app/text-metrics';
import {LavaActionButton} from '../src';
import {colors} from '../src/colors';
import {Copy,Screen,Section} from '../review/primitives';
import {Group,Info,InputRow,toolbarButton,useToolbar} from '../review/scaffold';
import {useReview} from '../review/ReviewContext';
import {useReviewNavigation,type ReviewRoutes} from '../review/navigation';
import {customDNSEditorProblem,customDNSEditorProblemCopy,useDNSEditor} from '../review/dns-editor';

const report=(error:unknown)=>{const message=(error as Error).message;if(message!=='Authentication cancelled.')Alert.alert('Lava',message);};

/**
 * The Android custom-resolver editor. The shared route previously handed off to a native page
 * container with no content; this is the minimal RN form that collects the identity the
 * canonical editor collects (name, primary, secondary) and issues the canonical
 * `dns.customDraft`/`dns.customStage` commands against the live resolver owner. The parent DNS
 * screen commits the whole ladder. A picker-provided token keeps its existing draft;
 * direct entry opens one. The native snapshot seeds the fields once without rewriting IME edits.
 */
export function CustomDNSEntryScreen() {
  const route=useRoute<RouteProp<ReviewRoutes,'CustomEntry'>>();
  const nav=useReviewNavigation();const {app,live}=useReview();
  const {draft,completeCustomPicker,cancelCustomPicker}=useDNSEditor();const focused=useIsFocused();
  const pickerEntry=!!route.params.id;const liveRef=useRef(live);liveRef.current=live;
  const mounted=useRef(true);const focusRef=useRef(focused);focusRef.current=focused;const attempt=useRef(0);
  const [editorToken,setEditorToken]=useState<string|undefined>(route.params.id||undefined);
  const [initial,setInitial]=useState<DNSChoice|null>(null);
  const [name,setName]=useState('');const [primary,setPrimary]=useState('');const [secondary,setSecondary]=useState('');
  const [problem,setProblem]=useState<string|null>(null);const [busy,setBusy]=useState(false);
  const pending=useRef(false);const token=useRef<string|undefined>(route.params.id);
  const opened=useRef(!!route.params.id);const hydrated=useRef(false);
  useEffect(()=>{mounted.current=true;const listener=AppState.addEventListener('change',state=>{if(state!=='active'){attempt.current++;if(token.current)cancelCustomPicker(token.current);}});
    return()=>{mounted.current=false;attempt.current++;listener.remove();};},[]);
  useEffect(()=>{if(!focused)attempt.current++;},[focused]);
  // The picker already opened its draft. Only direct entry needs a new native session.
  useEffect(()=>{
    if(!app||opened.current)return;opened.current=true;
    const started=++attempt.current;const sourceContext=liveRef.current?.dns.tiersContext;
    void app.command<string>({type:'dns.customDraft'}).then(id=>{
      if(typeof id!=='string')return;
      if(!mounted.current||!focusRef.current||attempt.current!==started||AppState.currentState!=='active'||
        sourceContext!==liveRef.current?.dns.tiersContext){
        void app.command({type:'customEntry.dismiss',id}).catch(()=>{});return;
      }
      token.current=id;setEditorToken(id);
    }).catch(error=>{if(mounted.current&&focusRef.current&&attempt.current===started)report(error);});
  },[app]);
  useEffect(()=>{
    if(hydrated.current||!editorToken||live?.dns.customDraftToken!==editorToken||!live.dns.customDraft)return;
    hydrated.current=true;const draft=live.dns.customDraft;
    setInitial(draft);setName(draft.name);setPrimary(draft.primary);setSecondary(draft.secondary);
  },[editorToken,live?.dns.customDraftToken,live?.dns.customDraft]);
  // Teardown is local state only and never authenticates, matching the canonical dismiss.
  const staged=useRef(false);
  useEffect(()=>()=>{if(token.current){if(!staged.current)cancelCustomPicker(token.current);void app?.command({type:'customEntry.dismiss',id:token.current}).catch(()=>{});}},[app]);
  useToolbar({title:'Custom DNS',headerBackVisible:false,
    unstable_headerLeftItems:()=>[toolbarButton('Close','xmark',()=>nav.goBack())]},[]);
  const save=async()=>{
    if(!app||pending.current||!initial)return;
    if(live?.dns.customDraftToken!==editorToken){setProblem('This DNS editor has changed. Close it and open it again.');return;}
    const invalid=customDNSEditorProblem({name,primary});
    if(invalid){setProblem(customDNSEditorProblemCopy(invalid));return;}
    const context=live?.dns?.custom?.context;
    if(context===undefined){setProblem('DNS settings are not available right now.');return;}
    if(pickerEntry&&draft.context!==live?.dns.tiersContext){setProblem('DNS settings changed. Reopen the editor before saving.');return;}
    const sourceContext=live?.dns.tiersContext;const started=++attempt.current;
    pending.current=true;setBusy(true);setProblem(null);
    try{
      const values={context,name:name.trim(),primary:primary.trim(),secondary:secondary.trim()};
      if(pickerEntry){
        const choice=await app.command<DNSChoice>({type:'dns.customStage',id:editorToken!,...values});
        if(!mounted.current||!focusRef.current||attempt.current!==started||AppState.currentState!=='active')return;
        if(liveRef.current?.dns.customDraftToken!==editorToken||liveRef.current?.dns.custom?.context!==context||liveRef.current?.dns.tiersContext!==sourceContext||
          !completeCustomPicker(editorToken!,draft.context,choice))throw new Error('DNS settings changed. Reopen the editor before saving.');
        staged.current=true;
      }else await app.command({type:'dns.custom',...values} as AppCommand);
      nav.goBack();
    }catch(error){const message=(error as Error).message;if(mounted.current&&focusRef.current&&attempt.current===started&&message!=='Authentication cancelled.')setProblem(message);}
    finally{pending.current=false;if(mounted.current)setBusy(false);}
  };
  return <Screen>
    <Section title="Custom DNS"><Info icon="server.rack" title="Your own resolver" description="Enter the name and address of a DNS-over-HTTPS, TLS or QUIC resolver you trust. Lava saves it only if this device can use it." />
      {initial?<Group>
        <InputRow title="Name"><EditorField testID="custom.dns.name" label="Name" placeholder="Home DNS" initialValue={initial.name} autoFocus onChange={setName}/></InputRow>
        <InputRow title="Primary address"><EditorField testID="custom.dns.primary" label="Primary address" placeholder="https://dns.example/query" initialValue={initial.primary} onChange={setPrimary}/></InputRow>
        <InputRow title="Secondary address (optional)"><EditorField testID="custom.dns.secondary" label="Secondary address" placeholder="https://dns2.example/query" initialValue={initial.secondary} onChange={setSecondary}/></InputRow>
      </Group>:<Copy role="caption" center>Opening DNS editor…</Copy>}
    </Section>
    {problem&&<Info warning title="Cannot save this resolver" description={problem}/>}
    <Copy role="caption" center color={colors.secondaryText}>Custom DNS is saved only after Lava accepts the address.</Copy>
    <View style={{padding:16}}><LavaActionButton title={busy?'Saving…':'Save resolver'} disabled={busy||!initial} onPress={()=>void save()} /></View>
  </Screen>;
}

/**
 * A single-line editor field. `DomainInput` always autofocuses, so the three-field form uses this
 * thinner wrapper to autofocus only the first field while keeping the same measured height and
 * label treatment the shared inputs use.
 */
function EditorField({testID,label,placeholder,initialValue,autoFocus,onChange}:{testID:string;label:string;placeholder:string;initialValue:string;autoFocus?:boolean;onChange:(value:string)=>void}) {
  const scale=useTextScale();
  const [measured,setMeasured]=useState({scale:0,height:0});
  // Frozen defaultValue seeds the native buffer only when this editor mounts. The shared native
  // field spec has no initial-text property, so this local wrapper uses the same Android TextInput
  // directly; subsequent snapshots never become a controlled value or rewrite composition.
  return <TextInput testID={testID} autoFocus={autoFocus} accessibilityLabel={localized(label)} placeholder={localized(placeholder)}
    defaultValue={initialValue} autoCapitalize="none" autoCorrect={false} onChangeText={onChange}
    onLayout={event=>setMeasured({scale,height:event.nativeEvent.layout.height})}
    style={{minHeight:48,color:colors.primaryText,fontSize:17*scale,height:measured.scale===scale?Math.max(22,measured.height):22*scale}} />;
}
