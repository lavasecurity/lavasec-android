import {useEffect,useRef,useState} from 'react';
import {AppState,View} from 'react-native';
import Credential from '../specs/LavaBackupCredentialNativeComponent.android';
import {AccountBackupFlow} from '../src/flows/account-backup/AccountBackupFlow';
import type {AccountBackupCommand,AccountBackupFlowState} from '../src/flows/account-backup/types';
import {Alert,localized} from '../app/presentation';
import {mayInteractWithPresentation,domainReviewPrivacyScope} from '../app/read-cache';
import {usePresentationNativeLayout} from '../app/use-presentation-readiness';
import {useTextScale} from '../app/text-metrics';
import {useReview} from './ReviewContext';
import {toolbarButton,useToolbar} from './scaffold';

/** RN arranges the shared stages; secure native fields never emit credential text. */
export function AndroidAccountBackup({id,state}:{id:string;state:AccountBackupFlowState}){
  const {app,live}=useReview();const onLayout=usePresentationNativeLayout();const scale=useTextScale();
  const [height,setHeight]=useState(160);const mounted=useRef(true);
  const authority=live?domainReviewPrivacyScope(live):'';
  const current=useRef({id,authority});current.current={id,authority};
  const booleanRows=new Set(['Protection on this device','Primary DNS enabled','Fallback DNS enabled',
    'Filtering Counts','Domain diagnostics','Network Activity','Lava Guard progress']);
  const renderedState={...state,review:state.review?.map(section=>({...section,rows:section.rows.map(row=>({
    ...row,title:row.title==='Primary DNS enabled'||row.title==='Fallback DNS enabled'
      ?`${localized(row.title.replace(' enabled',''))} · ${localized('Enabled')}`:row.title,
    before:booleanRows.has(row.title)?localized(row.before):row.before,
    after:booleanRows.has(row.title)?localized(row.after):row.after,
  }))}))??null};
  const active=()=>mounted.current&&current.current.id===id&&current.current.authority===authority&&
    AppState.currentState==='active'&&mayInteractWithPresentation(app)&&app?.getSnapshot().snapshot?.foregroundFlow?.id===id;
  const close=()=>{void app?.retireAccountBackup(id).catch(()=>{});};
  const send=(command:AccountBackupCommand,confirmed?:boolean)=>{
    if(command.action==='close'||command.action==='back'){close();return;}
    if(!active())return;
    return app?.command({type:'accountBackup.command',id,...command,...(confirmed?{confirmed:true}:{})})
      .catch(error=>{if(active())Alert.alert('Lava',error.message);});
  };
  useToolbar({title:state.title,headerBackVisible:false,unstable_headerLeftItems:()=>[
    toolbarButton('Close','xmark',close,false)],unstable_headerRightItems:()=>[]},[id,state.title]);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;close();};},[app,id]);
  return <View style={{flex:1}} onLayout={onLayout}>
    <AccountBackupFlow state={renderedState} command={send}
      deviceKeyHint={{symbol:'key',text:'A local unlock key stays on this device.'}}
      credentials={<Credential ownerID={id} fontScale={scale} style={{height,minHeight:48}}
        onSizeChange={event=>{const next=event.nativeEvent.height;if(Number.isFinite(next)&&next>=48&&next<=4096)setHeight(next);}}/>}
      confirmAccountDeletion={()=>{
        if(!active())return;
        Alert.alert('Delete my Lava account','This permanently deletes your Lava account and its online backup.',[
          {text:'Cancel',style:'cancel'},
          {text:'Delete',style:'destructive',onPress:()=>{if(active())void send({action:'account.delete'},true);}},
        ]);
      }}/>
  </View>;
}
