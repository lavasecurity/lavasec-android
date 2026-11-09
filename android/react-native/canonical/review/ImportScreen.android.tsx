import {useContext,useEffect,useRef,useState} from 'react';
import {ActivityIndicator,View} from 'react-native';
import type {AppCommand} from '../app/contract';
import {Alert,Text,localized,localizedFormat} from '../app/presentation';
import {mayInteractWithPresentation} from '../app/read-cache';
import {usePresentationAuthority,usePresentationReadiness} from '../app/use-presentation-readiness';
import {ImportFlow} from '../src/flows/import/ImportFlow';
import {importTitles,type ImportAction,type ImportState} from '../src/flows/import/contract';
import {colors} from '../src/colors';
import {LavaActionButton} from '../src';
import {useReview} from './ReviewContext';
import {useReviewNavigation} from './navigation';
import {usePlusEntry} from './plus-entry';
import {toolbarButton,useToolbar} from './scaffold';
import {AndroidPresentationInteractionContext} from './presentation-owner.android';
import {PresentationCover} from './PresentationCover';
import {validateImportInput,importInputProblemCopy} from './import-model';

type ReviewedImport={
  reviewID:string;blocklistIds:string[];blockedDomains:string[];allowedDomains:string[]|null;
  customBlocklists?:{id:string;name:string}[];
  canConfirm:boolean;dropped:{kind:string;label:string}[];unknownSourceIds:string[];
  replacementGroups:{title:string;removed:string[];added:string[]}[];
};
const initial=():ImportState=>({stage:'chooseMethod',stageID:'0',direction:'forward',code:'',error:'',cameraDenied:false,
  checkingCatalog:false,catalogCheckFailed:false,decodingPhoto:false,canDismiss:true,alertID:''});

/** Shared stages with native acquisition, bounded review and exact-visit publication. */
export function ImportScreen(){
  const {app,live}=useReview();const nav=useReviewNavigation();const upgrade=usePlusEntry();
  const authority=usePresentationAuthority(app);const receipt=useContext(AndroidPresentationInteractionContext);
  const [state,setState]=useState<ImportState>(initial);const [entryError,setEntryError]=useState('');
  const [entryAttempt,setEntryAttempt]=useState(0);const [ready,setReady]=useState(false);
  const current=useRef(state);current.current=state;
  const owner=useRef<{retired:boolean;id?:string;ready:Promise<string>}|undefined>(undefined);
  const review=useRef<ReviewedImport|undefined>(undefined);const target=useRef<string|undefined>(undefined);const source=useRef<'code'|'scan'|'photo'>('code');
  const pending=useRef(false);const [busy,setBusy]=useState(false);const serial=useRef(0);
  const update=(patch:Partial<ImportState>)=>{current.current={...current.current,...patch};setState(current.current);};
  const move=(stage:ImportState['stage'],patch:Partial<ImportState>={})=>update({stage,stageID:String(++serial.current),direction:'forward',error:'',...patch});
  const cancel=(id:string)=>{void app?.command({type:'native.flow',flow:'importCancel',visitID:id} as AppCommand).catch(()=>{});};
  useEffect(()=>{
    const visit={retired:false,id:undefined as string|undefined,ready:Promise.resolve('')};
    owner.current=visit;review.current=undefined;target.current=undefined;pending.current=false;
    setState(initial());setEntryError('');setReady(false);setBusy(false);
    visit.ready=app?app.command<{visitID:string}>({type:'native.flow',flow:'importBegin'}).then(value=>{
      if(!value?.visitID)throw new Error(localized('Importing cannot continue'));
      visit.id=value.visitID;
      if(visit.retired){cancel(value.visitID);return '';}
      setReady(true);return value.visitID;
    }).catch(error=>{if(!visit.retired)setEntryError((error as Error).message);return '';}):Promise.resolve('');
    return()=>{visit.retired=true;if(visit.id)cancel(visit.id);};
  },[app,live?.generation,live?.nativeHostID,entryAttempt]);
  usePresentationReadiness(app,!!app,ready||!!entryError,`import:${entryAttempt}`);
  const close=()=>{const visit=owner.current;if(visit){visit.retired=true;if(visit.id)cancel(visit.id);}nav.goBack();};
  const active=()=>!!owner.current&&!owner.current.retired&&mayInteractWithPresentation(app)&&
    (!app?.getSnapshot||!!receipt&&(receipt===true||receipt.app===app));
  const display=(value:ReviewedImport)=>{
    if(!value?.reviewID||!Array.isArray(value.blockedDomains)||!Array.isArray(value.replacementGroups))
      throw new Error(localized('Importing cannot continue'));
    review.current=value;
    update({review:{
      blocked:[...value.blocklistIds.map(id=>({id:`list:${id}`,title:live?.blocklistNames[id]??id,
        metadata:live?.blocklistMetadata[id]??localized('Blocklist'),verbatim:true})),
        ...(value.customBlocklists??[]).map(row=>({id:`custom:${row.id}`,title:row.name,metadata:localized('Custom blocklist'),verbatim:true})),
        ...value.blockedDomains.map(domain=>({id:`domain:${domain}`,title:domain,metadata:localized('Single domain'),verbatim:true}))],
      allowed:value.allowedDomains,canImport:value.canConfirm,showUpgrade:value.dropped.some(row=>row.kind==='REQUIRES_UPGRADE'),
      upgradeRows:value.dropped.filter(row=>row.kind==='REQUIRES_UPGRADE').map(row=>row.label),
      warnings:[...value.dropped.filter(row=>row.kind!=='REQUIRES_UPGRADE').map(row=>({message:localizedFormat('%@: Not imported on this device',row.label),quiet:false})),
        ...value.unknownSourceIds.map(id=>({message:localizedFormat('%@: Not imported on this device',id),quiet:true}))],
    }});
  };
  const command=async(action:ImportAction)=>{
    if(action.type==='import.dismiss'||action.type==='import.done'){close();return;}
    if(!active())return;
    if(action.type==='import.code'){update({code:action.value,error:''});return;}
    if(pending.current)return;
    const visit=owner.current!;pending.current=true;setBusy(true);
    const owns=()=>owner.current===visit&&!visit.retired;
    const send=async<T,>(flow:string,extra:Record<string,unknown>={})=>{
      const visitID=await visit.ready;
      if(!visitID||!owns()||!app)throw new Error(localized('Importing cannot continue'));
      const result=await app.command<T>({type:'native.flow',flow,visitID,...extra} as AppCommand);
      if(!owns())throw new Error('retired-import');
      return result;
    };
    try{
      switch(action.type){
        case 'import.method': {
          source.current=action.method;target.current=undefined;review.current=undefined;
          if(action.method==='code'){move('enterCode',{review:undefined});break;}
          move(action.method==='scan'?'scanCode':'chooseMethod',{decodingPhoto:action.method==='photo',cameraDenied:false});
          const value=await send<ReviewedImport>(action.method==='scan'?'importScan':'import');
          display(value);move('confirm',{decodingPhoto:false});break;
        }
        case 'import.continue': {
          const problem=validateImportInput(action.value);
          if(problem){update({error:importInputProblemCopy(problem)});break;}
          update({code:action.value});target.current=undefined;
          display(await send<ReviewedImport>('importCode',{code:action.value.trim()}));move('confirm');break;
        }
        case 'import.targets':
          move('chooseReplace',{targets:(live?.filters??[]).map(filter=>({id:filter.id,name:filter.name,summary:filter.count,locked:filter.frozen}))});break;
        case 'import.target': {
          const filter=live?.filters.find(row=>row.id===action.target);if(!filter||!review.current)break;
          if(filter.frozen){upgrade('frozenFilter');break;}
          const value=await send<ReviewedImport>('importTarget',{reviewID:review.current.reviewID,target:filter.id});
          display(value);target.current=filter.id;
          move('reviewReplacement',{replacement:{name:filter.name,removed:localized('The selected filter keeps its name. Review the content changes below.'),
            groups:value.replacementGroups.map(group=>({...group,
              removed:group.title==='Blocklists'?group.removed.map(id=>live?.blocklistNames[id]??id):group.removed,
              added:group.title==='Blocklists'?group.added.map(id=>live?.blocklistNames[id]??id):group.added}))}});break;
        }
        case 'import.add':case 'import.replace':case 'import.confirmActive': {
          const reviewed=review.current;if(!reviewed?.canConfirm)break;
          const replace=action.type!=='import.add'?target.current:undefined;
          if(action.type!=='import.add'&&!replace)break;
          if(replace===live?.session.activeFilterID&&action.type!=='import.confirmActive'){
            const confirmation=reviewed.reviewID;
            Alert.alert('Replace the filter in effect?','The imported filter will replace the contents of the filter in effect.',[
              {text:'Cancel',style:'cancel'},
              {text:'Replace',style:'destructive',onPress:()=>{if(owns()&&review.current?.reviewID===confirmation)void command({type:'import.confirmActive',confirmation});}},
            ]);break;
          }
          if(action.type==='import.confirmActive'&&action.confirmation!==reviewed.reviewID)break;
          move('applying');review.current=undefined;
          await send('importApply',{reviewID:reviewed.reviewID,...(replace?{target:replace,confirmed:true}:{})});
          move('completed',{completed:{message:localized('Filter imported'),unavailable:''}});break;
        }
        case 'import.back':
          if(state.stage==='reviewReplacement'){move('chooseReplace',{direction:'backward'});break;}
          if(state.stage==='chooseReplace'&&review.current){display(await send<ReviewedImport>('importTarget',{reviewID:review.current.reviewID}));target.current=undefined;move('confirm',{direction:'backward'});break;}
          review.current=undefined;target.current=undefined;
          move(state.stage==='confirm'&&source.current==='code'?'enterCode':'chooseMethod',{direction:'backward',review:undefined});break;
        case 'import.upgrade': {
          const reviewed=review.current;if(!reviewed)break;
          upgrade('fullImport',async isCurrent=>{
            if(!owns()||isCurrent?.()===false||review.current?.reviewID!==reviewed.reviewID)return;
            const value=await send<ReviewedImport>('importTarget',{reviewID:reviewed.reviewID});
            if(!owns()||isCurrent?.()===false)return;
            target.current=undefined;display(value);move('confirm');
          });break;
        }
        case 'import.cameraSettings':await app?.command({type:'system.open',target:'settings'});break;
        default:break;
      }
    }catch(error){
      if(owns()){
        const message=(error as Error).message;
        if(current.current.stage==='applying'){review.current=undefined;move(source.current==='code'?'enterCode':'chooseMethod',{review:undefined});}
        if(current.current.stage==='scanCode'){
          if((error as Error & {code?:string}).code==='share-permission-denied')update({cameraDenied:true});
          else move('chooseMethod');
        }
        update({decodingPhoto:false,error:message==='Authentication cancelled.'||message==='Import cancelled.'?'':message});
      }
    }finally{if(owns()){pending.current=false;setBusy(false);}}
  };
  const root=state.stage==='chooseMethod'||state.stage==='completed';
  useToolbar({title:importTitles[state.stage],
    unstable_headerLeftItems:()=>!root&&state.stage!=='applying'?[toolbarButton('Back','chevron.left',()=>void command({type:'import.back'}),busy)]:[],
    unstable_headerRightItems:()=>[toolbarButton('Close','xmark',close)]},[state.stage,state.stageID,ready,busy]);
  if(!authority)return <PresentationCover testID="import-privacy-cover"/>;
  if(entryError)return <PresentationCover><Text verbatim>{entryError}</Text><LavaActionButton title="Try again" onPress={()=>setEntryAttempt(value=>value+1)}/></PresentationCover>;
  return <View style={{flex:1,backgroundColor:colors.groupedBackground}}>
    {!!state.error&&state.stage!=='enterCode'&&<Text verbatim accessibilityRole="alert" style={{padding:16,color:colors.errorText}}>{state.error}</Text>}
    <ImportFlow state={state} interactive={ready&&active()} port={{command,scanner:<View style={{flex:1,alignItems:'center',justifyContent:'center'}}><ActivityIndicator/><Text>Scan a QR code</Text></View>}}/>
  </View>;
}
