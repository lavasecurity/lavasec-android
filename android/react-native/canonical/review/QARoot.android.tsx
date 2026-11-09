import {useEffect,useRef,useState,type PropsWithChildren} from 'react';
import {ActivityIndicator,DeviceEventEmitter,NativeModules,Pressable,ScrollView,StyleSheet,View,type LayoutChangeEvent} from 'react-native';
import {SafeAreaProvider,SafeAreaView} from 'react-native-safe-area-context';
import {PresentationContext,Text,configurePresentation,localized} from '../app/presentation';
import {applyAndroidLocale} from '../android/locale';
import {useTextScale} from '../app/text-metrics';
import {WindowSizeContext,type WindowSize} from '../src/window-layout';
import {QAScreen,QADetailScreen,type QAPort} from '../src/flows/qa/QAScreens';
import type {QAAction,QAState} from '../src/flows/qa/contract';
import {FlowSymbol} from '../src/flows/layout.android';
import {Copy,Section} from './primitives';
import {LavaActionButton,LavaCard} from '../src';
import {colors} from '../src/colors';
import {BufferedInput} from './BufferedInput';

type Projection={revision:number;interactive:boolean;state:QAState;canaryStatus:string;labels?:{suffix:string;canary:string}};
type Envelope={ownerID:string;revision:number;projection:Projection|null};
type Port={getQaPresentation:(id:string)=>Promise<string>;qaPresentationCommand:(id:string,request:string)=>Promise<string>;
  qaPresentationLayout:(id:string,visit:string,width:number,height:number)=>Promise<boolean>};
const native=()=>NativeModules.NativeLavaApp as Port|undefined;
export function parseQAPresentation(raw:string,ownerID:string):Envelope|null{
 try{
  const value=JSON.parse(raw) as Envelope;
  if(!value||value.ownerID!==ownerID||!Number.isSafeInteger(value.revision)||value.revision<0)return null;
  if(value.projection===null)return value;
  const p=value.projection,s=p?.state;
  if(!s||typeof s.id!=='string'||s.id.length>128||typeof p.interactive!=='boolean'||typeof p.canaryStatus!=='string'||
    !Array.isArray(s.sections)||s.sections.length>64||typeof s.search!=='string'||typeof s.probeSuffix!=='string'||typeof s.canaryIP!=='string')return null;
  const rows=(rows:unknown):boolean=>Array.isArray(rows)&&rows.length<=600&&rows.every(r=>r&&typeof r.id==='string'&&typeof r.title==='string'&&typeof r.subtitle==='string');
  if(!s.sections.every(section=>typeof section.title==='string'&&rows(section.rows)))return null;
  if(s.detail&&(!['result','suite','check'].includes(s.detail.kind)||typeof s.detail.id!=='string'||typeof s.detail.title!=='string'||s.detail.rows&&!rows(s.detail.rows)))return null;
  return value;
 }catch{return null;}
}
/** Secondary surface of the admitted native page; it never reads the app or secure stores. */
export default function QARoot({ownerID,locale='en',textScale}:{ownerID:string;locale?:string;textScale?:number}){
 const [projection,setProjection]=useState<Projection|null>(null);
 const revision=useRef(-1),mounted=useRef(false);
 const ramps=['body','headline','subheadline','footnote','caption1','caption2','title1','title2','title3','largeTitle'];
 const presentation=applyAndroidLocale({locale,textScales:textScale===undefined?null:Object.fromEntries(ramps.map(ramp=>[ramp,textScale]))});
 configurePresentation(presentation);
 const receive=(raw:string)=>{
  if(!mounted.current)return;const message=parseQAPresentation(raw,ownerID);
  if(!message||message.revision<=revision.current)return;
  revision.current=message.revision;setProjection(message.projection);
 };
 useEffect(()=>{
  mounted.current=true;revision.current=-1;
  const listener=DeviceEventEmitter.addListener('lavaQaPresentation',receive);
  void native()?.getQaPresentation(ownerID).then(receive).catch(()=>{});
  return()=>{mounted.current=false;listener.remove();};
 },[ownerID]);
 const send=(action:QAAction)=>{
  if(!projection)return;
  const state=projection.state;
  void native()?.qaPresentationCommand(ownerID,JSON.stringify({id:state.id,...(state.detail?{detail:state.detail.id}:{}),...action})).then(receive).catch(()=>{});
 };
 return <PresentationContext.Provider value={presentation}><SafeAreaProvider style={{flex:1,width:'100%'}}>
  {projection?<QAVisit key={projection.state.id} ownerID={ownerID} projection={projection} send={send}/>:<View style={{flex:1,backgroundColor:colors.groupedBackground}}><ActivityIndicator accessibilityLabel={localized('Loading')}/></View>}
 </SafeAreaProvider></PresentationContext.Provider>;
}
function QAPage({children,port,spacing}:PropsWithChildren<{port:QAPort;spacing:number}>){
 return <ScrollView testID="qa.scroll" style={{flex:1}} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag"
  contentInsetAdjustmentBehavior="never" contentContainerStyle={{width:'100%',maxWidth:560,alignSelf:'center',padding:16,paddingBottom:32,gap:spacing}}
  pointerEvents={port.interactive?'auto':'none'} accessibilityElementsHidden={!port.interactive} importantForAccessibility={port.interactive?'auto':'no-hide-descendants'}>{children}</ScrollView>;
}
function QAVisit({ownerID,projection,send}:{ownerID:string;projection:Projection;send:(action:QAAction)=>void}){
 const {state,interactive}=projection;const scale=useTextScale();const [viewport,setViewport]=useState<WindowSize|null>(null);
 const layout=(event:LayoutChangeEvent)=>{
  const {width,height}=event.nativeEvent.layout;
  if(width<=0||height<=0)return;
  setViewport(old=>old?.width===width&&old?.height===height?old:{width,height});
  void native()?.qaPresentationLayout(ownerID,state.id,width,height).catch(()=>{});
 };
 const port:QAPort={send,configuration:null,interactive,localize:localized,textScale:scale,fieldBackground:colors.cardBackground,quietPressFeedback:true,
  monospaceFont:'monospace',minimumFieldHeight:48,fieldLabel:placeholder=>placeholder==='192-168-1-20.sslip.io'?(projection.labels?.suffix??localized('Custom Probe Suffix')):placeholder.startsWith('resolver IP')?(projection.labels?.canary??localized('Resolver IP')):localized(placeholder),fieldLimits:{suffix:253,canary:64,evidence:4096},page:QAPage,
  glyph:(name,destructive)=><FlowSymbol name={name} tone={destructive?'orange':'green'} size={24}/>,
  searchField:props=><BufferedInput testID="qa_search" accessibilityLabel={localized(props.label)} placeholder={localized(props.label)}
    value={props.value} onChangeText={props.onChange} editable={interactive} maxLength={200} clearButtonMode="while-editing" autoCorrect={false}
    style={{minHeight:48,borderRadius:16,paddingHorizontal:12,fontSize:16*scale,color:colors.primaryText,backgroundColor:colors.cardBackground}} allowFontScaling={false}/>,
  configurationSection:<Section title="WireGuard configuration"><LavaCard><LavaActionButton title="VPN chaining" role="panel" disabled={!interactive} onPress={()=>send({action:'row',row:'chained-editor'})}/></LavaCard></Section>,
  guidedActions:<LavaActionButton title="Settings" role="panel" disabled={!interactive} onPress={()=>send({action:'settings'})}/>,
  canaryStatus:<Copy role="supporting" verbatim>{projection.canaryStatus}</Copy>,
 };
 return <WindowSizeContext.Provider value={viewport}><SafeAreaView testID="qa.shared" edges={['top','bottom','left','right']} onLayout={layout} style={{flex:1,backgroundColor:colors.groupedBackground}}>
  <View style={{flexDirection:'row',alignItems:'center',minHeight:56,paddingHorizontal:8,gap:8}}>
   <Pressable accessibilityRole="button" accessibilityLabel={localized(state.detail?'Close':'Back')} onPress={()=>send({action:state.detail?'detail.close':'close'})}
    style={{minWidth:48,minHeight:48,justifyContent:'center',alignItems:'center'}}>
    {({pressed})=><>{pressed&&<View pointerEvents="none" style={[StyleSheet.absoluteFill,{backgroundColor:colors.primaryText,opacity:.06}]}/>}<FlowSymbol name={state.detail?'xmark':'chevron.left'} size={24}/></>}
   </Pressable>
   <Text verbatim accessibilityRole="header" style={{flex:1,fontSize:18,fontWeight:'600',color:colors.primaryText}}>{state.detail?.title??localized('Device QA')}</Text>
   {!state.detail&&<Pressable testID="qa_result" accessibilityRole="button" accessibilityLabel={localized('View QA result')} disabled={!state.hasResult||!interactive} focusable={state.hasResult&&interactive}
    onPress={()=>send({action:'result'})} style={{minWidth:48,minHeight:48,alignItems:'center',justifyContent:'center',opacity:state.hasResult?1:.4}}>
    {({pressed})=><>{pressed&&<View pointerEvents="none" style={[StyleSheet.absoluteFill,{backgroundColor:colors.primaryText,opacity:.06}]}/>}<FlowSymbol name="info.circle" size={24}/></>}
   </Pressable>}
   {state.staging&&<ActivityIndicator accessibilityLabel={localized('Loading')}/>}
  </View>
  <View style={{flex:1,display:state.detail?'none':'flex'}} pointerEvents={state.detail?'none':'auto'} accessibilityElementsHidden={!!state.detail} importantForAccessibility={state.detail?'no-hide-descendants':'auto'}>
   <QAScreen state={state} port={port}/>
  </View>
  {state.detail&&<QADetailScreen key={state.detail.id} state={state.detail} port={port}/>}
 </SafeAreaView></WindowSizeContext.Provider>;
}
