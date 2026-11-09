import {createContext,useContext,useLayoutEffect,useRef,useState,type PropsWithChildren} from 'react';
import type {DNSChoice} from '../app/contract';

type Draft={tiers:DNSChoice[];context:string;systemDNS?:DNSChoice|null;systemDNSOriginalID?:string|null};
type CustomPickerDraft={token:string;context:string;choice?:DNSChoice};
const emptyDraft:Draft={tiers:[],context:''};
const Context=createContext<{
  draft:Draft;setDraft:(value:Draft)=>void;customPickerDraft:CustomPickerDraft|null;
  beginCustomPicker:(token:string,context:string)=>void;
  completeCustomPicker:(token:string,context:string,choice:DNSChoice)=>boolean;
  cancelCustomPicker:(token?:string)=>void;
}|null>(null);
export function DNSEditorProvider({children,retirementKey,available=true}:PropsWithChildren<{retirementKey?:string;available?:boolean}>){
  const owner=useRef({key:retirementKey,available,epoch:0,authorizationEpoch:0});
  if(owner.current.key!==retirementKey||owner.current.available!==available)owner.current={key:retirementKey,available,
    epoch:owner.current.epoch+(owner.current.key!==retirementKey?1:0),authorizationEpoch:owner.current.authorizationEpoch+1};
  const {epoch,authorizationEpoch}=owner.current;
  const [state,setState]=useState<{epoch:number;draft:Draft;picker:CustomPickerDraft|null}>({epoch,draft:emptyDraft,picker:null});
  const current=useRef(state);current.current=state;
  const admitted=()=>available&&owner.current.available&&owner.current.epoch===epoch&&owner.current.authorizationEpoch===authorizationEpoch;
  const draft=available&&state.epoch===epoch?state.draft:emptyDraft;
  const customPickerDraft=available&&state.epoch===epoch?state.picker:null;
  useLayoutEffect(()=>{setState(value=>value.epoch===epoch?value:{epoch,draft:emptyDraft,picker:null});},[epoch]);
  const update=(next:typeof state)=>{current.current=next;setState(next);};
  const setDraft=(value:Draft)=>{if(!admitted())return;const picker=current.current.picker?.context===value.context?current.current.picker:null;update({epoch,draft:value,picker});};
  const beginCustomPicker=(token:string,context:string)=>{if(admitted())update({...current.current,picker:{token,context}});};
  const completeCustomPicker=(token:string,context:string,choice:DNSChoice)=>{
    const state=current.current;if(!admitted()||state.epoch!==epoch||state.picker?.token!==token||state.picker.context!==context||state.draft.context!==context)return false;
    update({...state,picker:{token,context,choice}});return true;
  };
  const cancelCustomPicker=(token?:string)=>{if(owner.current.epoch!==epoch||!token&&owner.current.authorizationEpoch!==authorizationEpoch)return;const state=current.current;if(!token||state.picker?.token===token)update({...state,picker:null});};
  return <Context.Provider value={{draft,setDraft,customPickerDraft,beginCustomPicker,completeCustomPicker,cancelCustomPicker}}>{children}</Context.Provider>;
}
export function useDNSEditor(){const value=useContext(Context);if(!value)throw new Error('DNS editor unavailable');return value;}
export function useOptionalDNSEditor(){return useContext(Context);}
export const isCustomDNS=(choice:DNSChoice)=>choice.id.split('|')[0]==='custom-dns';
// Native stage results and saved rows carry a protocol-specific ID. Raw stamp base64 and URL
// paths remain case-sensitive; the native policy owns normalized operational duplicate checks.
export const sameDNS=(a:DNSChoice,b:DNSChoice)=>a.id===b.id&&(!isCustomDNS(a)||a.primary.trim()===b.primary.trim()&&a.secondary.trim()===b.secondary.trim());
// Native-generated display labels have an empty source name; authored and older wire names stay verbatim.
export const selection=(choice:DNSChoice)=>({id:choice.id,name:choice.nameIsDefault===true?'':choice.name,primary:choice.primary,secondary:choice.secondary,...(choice.isEnabled===false?{isEnabled:false}:{})});

/**
 * The custom-resolver editor's structural guard. It is a screen-level check only: the owner
 * (`CustomResolverValidator` + `ResolverSelectionPolicy`) remains authoritative on every real
 * address/transport/plan refusal, which the editor surfaces verbatim. This only stops an empty
 * submission before it reaches the owner.
 */
export type CustomDNSEditorProblem = 'missing-primary';

export function customDNSEditorProblem(value: {name: string; primary: string}): CustomDNSEditorProblem | null {
  if (!value.primary.trim()) return 'missing-primary';
  return null;
}

export function customDNSEditorProblemCopy(_problem: CustomDNSEditorProblem): string {
  return 'Enter the resolver address.';
}
